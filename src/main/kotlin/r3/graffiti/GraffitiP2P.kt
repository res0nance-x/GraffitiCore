package r3.graffiti

import r3.content.Content
import r3.content.ContentMeta
import r3.content.FileContent
import r3.encryption.EncryptedContinuousOutputStream
import r3.hash.hash256
import r3.io.*
import r3.key.hash256
import r3.math.EncryptedSequence
import r3.net.discover.*
import r3.net.tcp.BridgeTCPNode
import r3.net.tcp.TCPBlock
import r3.net.tcp.TCPBlockHandler
import r3.net.tcp.TCPFileStream
import r3.net.tcp.TCPNode
import r3.net.tcp.TCPPack
import r3.net.tcp.TCPServer
import r3.pack.BinaryPack
import r3.pack.DirPack
import r3.pke.*
import r3.source.BinarySource
import r3.source.FileSink
import r3.source.FileSource
import r3.source.ListWritable
import r3.source.Source
import r3.source.StringWritable
import r3.thread.async
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/*
Peer-to-peer synchronization flow:

// 1. Connection & Mutual Authentication Handshake (both directions on connect)
A → B:  ChallengeMessage { nonce: ByteArray(32) }
B → A:  ChallengeResponseMessage { peer: Peer, signature: ByteArray, isRelay: Boolean }
        (Connection closed immediately if signature verification fails)

// 2. Initial Sync (triggered automatically after authentication)
A → B:  QueryMessage { authorFilter, recipientFilter, queryTime: Long }
B → A:  QueryResponseMessage { serverTime: Long } (header)
        + ListWritable<EncryptedContentHeader> (file body with author, recipient, key)

// 3. Dynamic Peer Resolution (if A receives headers from unknown authors)
A → B:  PeerRequestMessage { keys: List<PeerKey> }
B → A:  PeerResponseMessage { peers: List<Peer> }
        (A validates peer key hash against MX key before saving)

// 4. Content Transfer
A → B:  ContentRequestMessage { keys: List<EncryptedMetaKey> }
B → A:  EncryptedContentMessage { eMeta: EncryptedContentMetaData } (header)
        + content body (in-memory ByteArray if <= 256 B, otherwise File)

// 5. Push (when a node receives or locally creates new content)
A → B:  QueryResponseMessage { serverTime: 0L } (header)
        + ListWritable<EncryptedContentHeader> (file body, typically 1 item)
B:      Verifies / requests peer if needed, then issues ContentRequestMessage
 */

class GraffitiP2P(
	val graffitiDir: File,
	relayEnabledAtStartup: Boolean = false,
	bridgeEnabledAtStartup: Boolean = false
) {
	val peerDir = File(graffitiDir, "peer").also { it.mkdirs() }
	val identityDir = File(graffitiDir, "identity").also { it.mkdirs() }
	val contentDir = File(graffitiDir, "content").also { it.mkdirs() }
	val tmpDir = File(graffitiDir, "tmp").also { it.mkdirs() }
	var portalDir: File = File(graffitiDir, "portal").also { it.mkdirs() }

	private val indexStore = GraffitiIndexStore(graffitiDir)
	val metaIndexFile: File get() = indexStore.metaIndexFile
	val metaIndexBakFile: File get() = indexStore.metaIndexBakFile
	val deletedIndexFile: File get() = indexStore.deletedIndexFile
	val ignoredIndexFile: File get() = indexStore.ignoredIndexFile

	val requestTracker = NodeRequestTracker()
	val portalManager = PortalManager(this, requestTracker)
	val bridgeManager = BridgeManager(this, requestTracker)

	var defaultP2PPort: Int = 0
	var relayMaxContentSizeBytes: Long = 50 * 1024 * 1024L // 50MB default for relays
	var autoDownloadThresholdBytes: Long = 50 * 1024 * 1024L // 50MB default for clients
	val contentHolders = ConcurrentHashMap<EncryptedMetaKey, MutableSet<TCPNode>>()

	companion object {
		const val PING_INTERVAL_MS = 60_000L  // client sends a ping every 60 s in active mode
		const val PING_TIMEOUT_MS = 3 * PING_INTERVAL_MS  // client closes after 180 s of silence (3 missed pings)
		const val BACKGROUND_IDLE_GRACE_MS = 15_000L // keep outbound connection open at least 15 s during background sync
		const val BACKGROUND_QUIET_MS = 5_000L // require 5 s of no read/write activity before background disconnect
		const val BACKGROUND_SYNC_INTERVAL_MS = 15 * 60 * 1000L // reconnect every 15 minutes in background mode
		const val MAX_STREAM_SIZE = 100 * 1024 * 1024 * 1024L // 100 GB
		const val DEFAULT_STREAM_TIMEOUT_MS = 300_000L // 5 minutes
		const val SMALL_CONTENT_THRESHOLD_BYTES = 256L
		const val MAX_SMALL_CONTENT_ITEMS = 10000
		const val MAGIC_META_INDEX = GraffitiIndexStore.MAGIC_META_INDEX
		const val MAGIC_DELETED_INDEX = GraffitiIndexStore.MAGIC_DELETED_INDEX
		const val MAGIC_IGNORED_INDEX = GraffitiIndexStore.MAGIC_IGNORED_INDEX
		const val INDEX_VERSION: Short = GraffitiIndexStore.INDEX_VERSION
		const val MAX_META_ENTRY_BYTES = GraffitiIndexStore.MAX_META_ENTRY_BYTES
		const val META_INDEX_COMPACT_THRESHOLD = GraffitiIndexStore.META_INDEX_COMPACT_THRESHOLD
	}

	// ── Backing maps (caches) ──────────────────────────────────────────────────
	private val identityCache = ConcurrentHashMap<IdentityKey, Identity>()
	private val peerCache = ConcurrentHashMap<PeerKey, Peer>()
	private val metaCache = ConcurrentHashMap<EncryptedMetaKey, EncryptedContentMetaData>()
	private val metaTimeCache = ConcurrentHashMap<EncryptedMetaKey, Long>()
	private val decryptedMetaCache = ConcurrentHashMap<EncryptedMetaKey, Pair<ContentMeta, Password256>>()
	private val smallContentCache = ConcurrentHashMap<EncryptedMetaKey, ByteArray>()
	private val deletedCache = ConcurrentHashMap.newKeySet<EncryptedMetaKey>()
	private val ignoredIdentitiesCache = ConcurrentHashMap.newKeySet<IdentityKey>()
	private val ignoredContentCache = ConcurrentHashMap.newKeySet<EncryptedMetaKey>()
	private val uncompactedDeletes = AtomicInteger(0)
	private val activeStreamVerifiers = ConcurrentHashMap<EncryptedMetaKey, StreamingHashVerifier>()
	private val contentSenderExecutor = Executors.newCachedThreadPool { r ->
		Thread(r, "Graffiti-ContentSender").apply { isDaemon = true }
	}

	internal fun runOnContentSender(task: () -> Unit) {
		contentSenderExecutor.execute(task)
	}

	@Volatile
	var onContentProgress: ((key: EncryptedMetaKey, bytesWritten: Long, totalBytes: Long) -> Unit)? = null

	@Volatile
	var onContentDownloadFailed: ((key: EncryptedMetaKey, error: String) -> Unit)? = null

	private val headerContentLengths = ConcurrentHashMap<EncryptedMetaKey, Long>()

	fun getContentSize(key: EncryptedMetaKey): Long {
		smallContentCache[key]?.let { return it.size.toLong() }
		val f = File(contentDir, "$key")
		return if (f.exists()) f.length() else 0L
	}

	internal fun getSmallContentBytes(key: EncryptedMetaKey): ByteArray? = smallContentCache[key]

	fun getKnownContentSize(key: EncryptedMetaKey): Long {
		val diskSize = getContentSize(key)
		if (diskSize > 0L) return diskSize
		getDecryptedMeta(key)?.first?.length?.takeIf { it > 0L }?.let { return it }
		headerContentLengths[key]?.takeIf { it > 0L }?.let { return it }
		return 0L
	}

	private val metaWarmupExecutor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "Graffiti-MetaWarmup").apply {
			isDaemon = true
			priority = Thread.NORM_PRIORITY - 1
		}
	}
	private val isWarmupActive = AtomicBoolean(false)
	private val pendingWarmupKeys = AtomicReference<List<EncryptedMetaKey>?>(null)

	fun triggerMetaWarmup(keys: List<EncryptedMetaKey>) {
		if (keys.isEmpty()) return
		pendingWarmupKeys.set(keys)
		scheduleWarmupLoop()
	}

	private fun scheduleWarmupLoop() {
		if (isWarmupActive.compareAndSet(false, true)) {
			metaWarmupExecutor.execute {
				try {
					while (true) {
						val batch = pendingWarmupKeys.getAndSet(null) ?: break
						for (k in batch) {
							if (decryptedMetaCache.containsKey(k)) continue
							try {
								getDecryptedMeta(k)
							} catch (_: Exception) {
							}
						}
					}
				} finally {
					isWarmupActive.set(false)
					if (pendingWarmupKeys.get() != null) {
						scheduleWarmupLoop()
					}
				}
			}
		}
	}

	// ── Server identity ───────────────────────────────────────────────────────
	val serverIdentity: Identity

	// ── Message Arrival Ordering ──────────────────────────────────────────────
	private val lastAssignedTimestamp = AtomicLong(0L)
	val queryOrderTimestamps = ConcurrentHashMap<EncryptedMetaKey, Long>()

	fun nextMonotonicTimestamp(): Long {
		val now = System.currentTimeMillis()
		return lastAssignedTimestamp.updateAndGet { prev ->
			if (now > prev) now else prev + 1L
		}
	}

	var activeWatermarkSupplier: (() -> Long?)? = null

	@Volatile
	private var relayEnabled = relayEnabledAtStartup

	@Volatile
	private var bridgeEnabled = bridgeEnabledAtStartup

	// ── Storage Size & Index Delegation ───────────────────────────────────────
	val totalContentSize = AtomicLong(0L)
	private var defaultQuota = 0L

	@Volatile
	private var storageQuotaBytes: Long = defaultQuota

	fun getQuotaBytes(): Long = storageQuotaBytes

	fun setQuotaBytes(bytes: Long) {
		storageQuotaBytes = bytes
		try {
			indexStore.saveQuota(bytes)
			checkAndEnforceQuota()
		} catch (e: Exception) {
			log("Failed to save quota: ${e.message}")
		}
	}

	fun saveDeletedIndex() = indexStore.saveDeletedIndex(deletedCache)
	fun saveIgnoredIndex() = indexStore.saveIgnoredIndex(ignoredIdentitiesCache, ignoredContentCache)
	fun saveMetaIndex() = indexStore.saveMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)

	private fun appendDeletedIndex(key: EncryptedMetaKey) =
		indexStore.appendDeletedIndex(key, deletedCache)

	private fun appendMetaIndex(eMeta: EncryptedContentMetaData, timestamp: Long, serializedBytes: ByteArray? = null) =
		indexStore.appendMetaIndex(eMeta, timestamp, serializedBytes, metaCache, metaTimeCache, deletedCache, uncompactedDeletes)

	@Synchronized
	fun purgeOldestHalfOverall(): Int {
		data class MsgEntry(val key: EncryptedMetaKey, val lastModified: Long)

		val allMessages = metaCache.keys.map { key ->
			MsgEntry(key, metaTimeCache[key] ?: 0L)
		}.sortedBy { it.lastModified }
		val target = storageQuotaBytes / 2
		val iter = allMessages.iterator()
		var count = 0
		while (iter.hasNext() && totalContentSize.get() > target) {
			if (deleteMessage(iter.next().key)) {
				count++
			}
		}
		if (count > 0 && uncompactedDeletes.get() > 0) {
			saveMetaIndex()
		}
		return count
	}

	@Synchronized
	fun checkAndEnforceQuota() {
		val quota = storageQuotaBytes
		if (quota <= 0) return
		val currentSize = totalContentSize.get()
		if (currentSize > quota) {
			log("Quota exceeded: $currentSize > $quota. Purging oldest half...")
			val purgedCount = purgeOldestHalfOverall()
			log("Purged $purgedCount messages. New total size: ${totalContentSize.get()}")
		}
	}

	init {
		cleanTempFiles(maxAgeMs = 0L)
		val serverIdenFile = File(graffitiDir, "serverIdentity")
		serverIdentity = if (serverIdenFile.exists()) {
			serverIdenFile.toDataInputStream().use { Identity.read(it) }
				.also { log("Loaded server identity: ${it.key.name}") }
		} else {
			Identity().also { iden ->
				serverIdenFile.writeBytes(iden.serialize())
				log("Created new server identity: ${iden.key.name}")
			}
		}
		identityDir.listFiles { f -> f.isFile }?.forEach { file ->
			runCatching { file.readBytes().toDataInputStream().use { Identity.read(it) } }
				.getOrNull()?.let { identityCache[it.key] = it }
		}
		peerDir.listFiles { f -> f.isFile }?.forEach { file ->
			runCatching { file.readBytes().toDataInputStream().use { Peer.read(it) } }
				.getOrNull()?.let { peerCache[it.key] = it }
		}
		indexStore.loadOrMigrateDeleted(deletedCache)
		indexStore.loadOrMigrateIgnored(ignoredIdentitiesCache, ignoredContentCache)
		indexStore.loadOrMigrateMeta(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)

		var totalBytes = 0L
		contentDir.listFiles { f -> f.isFile }?.forEach { file ->
			val len = file.length()
			totalBytes += len
			if (len <= SMALL_CONTENT_THRESHOLD_BYTES && smallContentCache.size < MAX_SMALL_CONTENT_ITEMS) {
				runCatching {
					val key = EncryptedMetaKey(file.name)
					smallContentCache[key] = file.readBytes()
				}
			}
		}
		totalContentSize.set(totalBytes)
		storageQuotaBytes = indexStore.loadQuota(defaultQuota)

		val maxExistingTime = metaTimeCache.values.maxOrNull() ?: 0L
		val now = System.currentTimeMillis()
		val initialTs = if (maxExistingTime in (now + 1)..(now + 86_400_000L)) {
			maxExistingTime
		} else {
			now
		}
		lastAssignedTimestamp.set(initialTs)
	}

	// ── P2P Protocol Message Dispatch ─────────────────────────────────────────

	val contentHandler: (TCPNode, ByteArray, File?) -> Unit =
		contentHandler@{ node: TCPNode, rawHead: ByteArray, file: File? ->
			try {
				val type = StringWritable.read(rawHead.toDataInputStream()).str
				when (type) {
					QueryMessage.type -> handleQueryMessage(node, rawHead)
					QueryResponseMessage.type -> handleQueryResponseMessage(node, rawHead, file)
					ContentRequestMessage.type -> handleContentRequestMessage(node, rawHead)
					ContentNotFoundMessage.type -> handleContentNotFoundMessage(node, rawHead)
					MetaRequestMessage.type -> handleMetaRequestMessage(node, rawHead)
					EncryptedMetaMessage.type -> handleEncryptedMetaMessage(node, rawHead)
					EncryptedContentMessage.type -> handleEncryptedContentMessage(node, rawHead, file)
					PingMessage.type -> node.sendPriority(StringWritable(PongMessage.type).serialize())
					PongMessage.type -> { /* lastActivityMs already refreshed by TCPNode on block receipt */ }
					ChallengeMessage.type -> handleChallengeMessage(node, rawHead)
					ChallengeResponseMessage.type -> handleChallengeResponseMessage(node, rawHead)
					PeerRequestMessage.type -> handlePeerRequestMessage(node, rawHead)
					PeerResponseMessage.type -> handlePeerResponseMessage(node, rawHead)
					PortalListRequestMessage.type -> portalManager.handleListRequest(node, rawHead)
					PortalListResponseMessage.type -> portalManager.handleListResponse(node, rawHead, file)
					PortalChunkRequestMessage.type -> portalManager.handleChunkRequest(node, rawHead)
					PortalChunkResponseMessage.type -> portalManager.handleChunkResponse(rawHead, file)
					BridgeListRequestMessage.type -> bridgeManager.handleListRequest(node, rawHead)
					BridgeListResponseMessage.type -> bridgeManager.handleListResponse(node, rawHead)
					else -> debug("Unknown message type: $type from ${node.remoteAddress}")
				}
			} catch (e: Exception) {
				logNodeError(node, "Error in contentHandler: ${e.message}")
			}
		}

	private fun handleQueryMessage(node: TCPNode, rawHead: ByteArray) {
		val msg = QueryMessage.read(rawHead.toDataInputStream())
		nodeQueryMap[node] = msg
		val isAskingAll = msg.isAskingAllRecipients()
		val currentTs = nextMonotonicTimestamp()
		val serverTime = activeWatermarkSupplier?.invoke()?.let { minOf(it, currentTs) } ?: currentTs
		val metaList = mutableListOf<EncryptedContentMetaData>()
		metaCache.values.forEach { eMeta ->
			val msgTime = metaTimeCache[eMeta.key] ?: 0L
			if (msg.queryTime > 0L && msgTime <= msg.queryTime) return@forEach
			if (isAskingAll && isIgnoredForRelay(eMeta)) return@forEach
			if (!msg.matches(eMeta)) return@forEach
			val contentExists =
				smallContentCache.containsKey(eMeta.key) || File(contentDir, "${eMeta.key}").exists()
			if (contentExists) metaList.add(eMeta)
		}
		metaList.sortBy { metaTimeCache[it.key] ?: 0L }
		node.send(
			QueryResponseMessage(serverTime).serialize(),
			ListWritable(metaList.map { it.toHeader(getContentSize(it.key)) }).serialize()
		)
		debug("Query from ${node.remoteAddress} (since=${msg.queryTime}): returning ${metaList.size} metadata items (out of ${metaCache.size})")
	}

	private fun handleQueryResponseMessage(node: TCPNode, rawHead: ByteArray, file: File?) {
		val resp = QueryResponseMessage.read(rawHead.toDataInputStream())
		val serverTime = resp.serverTime
		if (file == null) return
		val headerList = file.toDataInputStream().use { dis ->
			ListWritable.read(dis, EncryptedContentHeader::read).list
		}
		val myIdentityKeys = listIdentities().map { it.key }.toSet()
		val myPeerKeys = listPeers().map { it.key }.toSet()
		val wantContent = mutableListOf<EncryptedMetaKey>()
		val wantMeta = mutableListOf<EncryptedMetaKey>()
		val wantPeers = mutableSetOf<PeerKey>()
		debug("Received QueryResponse from ${node.remoteAddress}: ${headerList.size} header item(s)")
		headerList.forEach { msgHeader ->
			contentHolders.computeIfAbsent(msgHeader.key) { ConcurrentHashMap.newKeySet() }.add(node)
			if (msgHeader.contentLength > 0L) {
				headerContentLengths[msgHeader.key] = msgHeader.contentLength
			}
			if (isMessageDeleted(msgHeader.key)) return@forEach
			if (msgHeader.author.arr.contentEquals(msgHeader.recipient.arr)) {
				debug("Ignoring header ${msgHeader.key.name} from ${node.remoteAddress}: author and recipient are the same")
				return@forEach
			}
			if (relayEnabled && relayMaxContentSizeBytes > 0L && msgHeader.contentLength > relayMaxContentSizeBytes) {
				debug("Relay ignoring header ${msgHeader.key.name} from ${node.remoteAddress}: size (${msgHeader.contentLength}) exceeds relay limit ($relayMaxContentSizeBytes)")
				return@forEach
			}
			if (!relayEnabled &&
				msgHeader.recipient !in myIdentityKeys &&
				PeerKey(msgHeader.recipient) !in myPeerKeys
			) {
				debug("Ignoring off-recipient meta ${msgHeader.key.name} from ${node.remoteAddress}")
				return@forEach
			}
			if (whitelistEnabled && !relayEnabled && msgHeader.author !in myPeerKeys) {
				debug("Ignoring meta ${msgHeader.key.name} from ${node.remoteAddress}: author ${msgHeader.author.name} not in whitelist")
				return@forEach
			}
			val metaExists = metaCache.containsKey(msgHeader.key)
			val contentExists =
				smallContentCache.containsKey(msgHeader.key) || File(contentDir, "${msgHeader.key}").exists()
			if (metaExists && contentExists) return@forEach

			if (metaExists && !contentExists) {
				if (!relayEnabled && autoDownloadThresholdBytes > 0L && msgHeader.contentLength > autoDownloadThresholdBytes) {
					debug("Large content ${msgHeader.key.name} (${msgHeader.contentLength} bytes) awaiting manual download")
					return@forEach
				}
			}

			queryOrderTimestamps.computeIfAbsent(msgHeader.key) { nextMonotonicTimestamp() }
			val now = System.currentTimeMillis()
			val pendingSince = pendingContentRequests[msgHeader.key]
			val isPending = pendingSince != null && (now - pendingSince) < 30_000L
			if (isPending) return@forEach
			val authorPeer = getPeerByKey(msgHeader.author)
			val isLargeForClient = !relayEnabled && autoDownloadThresholdBytes > 0L && msgHeader.contentLength > autoDownloadThresholdBytes
			if (authorPeer == null) {
				pendingPeerContent.computeIfAbsent(msgHeader.author) { ConcurrentHashMap() }[msgHeader.key] = node
				wantPeers.add(msgHeader.author)
			} else {
				pendingContentRequests[msgHeader.key] = now
				inFlightNodeRequests[msgHeader.key] = node
				if (isLargeForClient) {
					wantMeta.add(msgHeader.key)
				} else {
					wantContent.add(msgHeader.key)
				}
			}
		}
		if (wantPeers.isNotEmpty()) {
			node.send(PeerRequestMessage(wantPeers.toList()).serialize())
			debug("Requested ${wantPeers.size} peer(s) from ${node.remoteAddress}")
		}
		if (wantMeta.isNotEmpty()) {
			node.send(MetaRequestMessage(wantMeta).serialize())
			debug("Requested metadata for ${wantMeta.size} large message(s) from ${node.remoteAddress}")
		}
		if (wantContent.isNotEmpty()) {
			node.send(createContentRequestMessage(wantContent).serialize())
		} else if (wantPeers.isEmpty() && wantMeta.isEmpty()) {
			debug("No content requests needed from ${node.remoteAddress} after QueryResponse")
		}

		if (wantContent.isEmpty() && wantPeers.isEmpty() && wantMeta.isEmpty()) {
			if (serverTime > 0L) {
				setPeerQueryTime(node, serverTime)
			}
		} else if (serverTime > 0L) {
			pendingBatchWatermark[node] = maxOf(pendingBatchWatermark[node] ?: 0L, serverTime)
			val set = batchPendingKeys.computeIfAbsent(node) { ConcurrentHashMap.newKeySet() }
			set.addAll(wantContent)
			set.addAll(wantMeta)
		}
	}

	private fun handleContentRequestMessage(node: TCPNode, rawHead: ByteArray) {
		val req = ContentRequestMessage.read(rawHead.toDataInputStream())
		debug("Received ContentRequest from ${node.remoteAddress}: ${req.keys.joinToString { it.name }} (offsets: ${req.offsets.size})")
		contentSenderExecutor.execute {
			try {
				val isAskingAll = nodeQueryMap[node]?.isAskingAllRecipients() == true
				val missingKeys = mutableListOf<EncryptedMetaKey>()
				val availableItems = mutableListOf<Triple<EncryptedMetaKey, EncryptedContentMetaData, ByteArray?>>()
				req.keys.forEach { key ->
					if (node.isClosed()) return@forEach
					if (isMessageDeleted(key)) {
						missingKeys.add(key)
						return@forEach
					}
					val eMeta = metaCache[key]
					if (isAskingAll && eMeta != null && isIgnoredForRelay(eMeta)) {
						debug("Skipping sending ignored content ${key.name} to ${node.remoteAddress}")
						missingKeys.add(key)
						return@forEach
					}
					val smallBytes = smallContentCache[key]
					if (eMeta != null && (smallBytes != null || File(contentDir, "$key").exists())) {
						availableItems.add(Triple(key, eMeta, smallBytes))
					} else {
						missingKeys.add(key)
					}
				}
				if (missingKeys.isNotEmpty() && !node.isClosed()) {
					node.sendPriority(ContentNotFoundMessage(missingKeys).serialize())
					debug("Sent ContentNotFound for ${missingKeys.size} key(s) to ${node.remoteAddress}")
				}
				availableItems.forEach { (key, eMeta, smallBytes) ->
					if (node.isClosed()) return@forEach
					val contentFile = if (smallBytes == null) File(contentDir, "$key") else null
					if (smallBytes != null || contentFile?.exists() == true) {
						val fileSize = smallBytes?.size?.toLong() ?: contentFile?.length() ?: 0L
						val reqOffset = req.offsets[key] ?: 0L
						val validOffset = if (reqOffset in 0L until fileSize) reqOffset else 0L

						if (smallBytes != null) {
							val payload = if (validOffset > 0L) smallBytes.copyOfRange(validOffset.toInt(), smallBytes.size) else smallBytes
							node.send(EncryptedContentMessage(eMeta, fileSize, validOffset).serialize(), payload)
						} else if (contentFile != null) {
							if (validOffset > 0L) {
								contentFile.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { istream ->
									var toSkip = validOffset
									while (toSkip > 0L) {
										val skipped = istream.skip(toSkip)
										if (skipped <= 0L) break
										toSkip -= skipped
									}
									node.send(EncryptedContentMessage(eMeta, fileSize, validOffset).serialize(), istream)
								}
							} else {
								node.send(EncryptedContentMessage(eMeta, fileSize, 0L).serialize(), contentFile)
							}
						}
						logOutgoing(node, key.name, fileSize)
					}
				}
			} catch (e: Exception) {
				logNodeError(node, "Error serving ContentRequest: ${e.message}")
			}
		}
	}

	private fun handleContentNotFoundMessage(node: TCPNode, rawHead: ByteArray) {
		val msg = ContentNotFoundMessage.read(rawHead.toDataInputStream())
		debug("Received ContentNotFound from ${node.remoteAddress} for ${msg.keys.size} key(s)")
		msg.keys.forEach { key ->
			contentHolders[key]?.remove(node)
			val attempt = activeDownloadAttempts[key]
			if (attempt != null && attempt.currentNode == node && !attempt.streamStarted) {
				tryNextNodeForDownload(key)
			} else if (inFlightNodeRequests[key] == node) {
				inFlightNodeRequests.remove(key)
				pendingContentRequests.remove(key)
			}
		}
	}

	private fun handleMetaRequestMessage(node: TCPNode, rawHead: ByteArray) {
		val req = MetaRequestMessage.read(rawHead.toDataInputStream())
		debug("Received MetaRequest from ${node.remoteAddress}: ${req.keys.joinToString { it.name }}")
		contentSenderExecutor.execute {
			try {
				val isAskingAll = nodeQueryMap[node]?.isAskingAllRecipients() == true
				req.keys.forEach { key ->
					if (node.isClosed()) return@forEach
					if (isMessageDeleted(key)) return@forEach
					val eMeta = metaCache[key]
					if (isAskingAll && eMeta != null && isIgnoredForRelay(eMeta)) return@forEach
					if (eMeta != null) {
						node.send(EncryptedMetaMessage(eMeta).serialize())
					}
				}
			} catch (e: Exception) {
				logNodeError(node, "Error serving MetaRequest: ${e.message}")
			}
		}
	}

	private fun completeInFlightKey(node: TCPNode, eMeta: EncryptedContentMetaData) {
		contentHolders.computeIfAbsent(eMeta.key) { ConcurrentHashMap.newKeySet() }.add(node)
		pendingContentRequests.remove(eMeta.key)
		inFlightNodeRequests.remove(eMeta.key)
		batchPendingKeys[node]?.remove(eMeta.key)
		if (batchPendingKeys[node]?.isEmpty() == true) {
			batchPendingKeys.remove(node)
			val time = pendingBatchWatermark.remove(node) ?: 0L
			if (time > 0L) setPeerQueryTime(node, time)
		}
		val isNew = !metaCache.containsKey(eMeta.key)
		metaCache[eMeta.key] = eMeta
		if (isNew) {
			val ts = queryOrderTimestamps.remove(eMeta.key) ?: nextMonotonicTimestamp()
			metaTimeCache[eMeta.key] = ts
			appendMetaIndex(eMeta, ts)
		} else {
			queryOrderTimestamps.remove(eMeta.key)
		}
	}

	private fun handleEncryptedMetaMessage(node: TCPNode, rawHead: ByteArray) {
		val msg = EncryptedMetaMessage.read(rawHead.toDataInputStream())
		val eMeta = msg.eMeta
		if (isMessageDeleted(eMeta.key)) {
			debug("Ignoring meta ${eMeta.key.name} from ${node.remoteAddress}: key is deleted")
			return
		}
		val peer = getPeerByKey(eMeta.author)
		if (peer == null) {
			debug("Author peer ${eMeta.author.name} not found for meta ${eMeta.key.name}")
		} else if (!eMeta.verify(peer)) {
			logNodeError(node, "Invalid signature for meta ${eMeta.key.name}")
		} else {
			val wasNew = !metaCache.containsKey(eMeta.key)
			completeInFlightKey(node, eMeta)
			if (wasNew) {
				notifyMessageReceived(eMeta.key)
			}
		}
	}

	private fun handleEncryptedContentMessage(node: TCPNode, rawHead: ByteArray, file: File?) {
		val contentMessage = EncryptedContentMessage.read(rawHead.toDataInputStream())
		val eMeta = contentMessage.eMeta
		if (file == null) return
		if (isMessageDeleted(eMeta.key)) {
			file.delete()
			debug("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: key is deleted")
			return
		}
		if (eMeta.author.arr.contentEquals(eMeta.recipient.arr)) {
			file.delete()
			debug("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: author and recipient are the same")
			return
		}
		val peer = getPeerByKey(eMeta.author)
		if (peer == null) {
			file.delete()
			logNodeError(node, "Author peer ${eMeta.author.name} not found for content ${eMeta.key.name}")
			return
		}
		val identity = getIdentityByKey(eMeta.recipient)
		val verifier = activeStreamVerifiers.remove(eMeta.key)
		val valid = if (verifier != null) {
			try {
				val hashOk = verifier.finish()
				val sigOk = eMeta.verify(peer)
				if (hashOk && sigOk) {
					true
				} else {
					logNodeError(node, "Streaming inline verification failed for ${eMeta.key.name}: hashOk=$hashOk, sigOk=$sigOk")
					false
				}
			} finally {
				verifier.close()
			}
		} else if (identity != null) {
			eMeta.verify(peer, FileSource(file), identity)
		} else {
			eMeta.verify(peer)
		}
		if (!valid) {
			val corruptFile = File(tmpDir, "${eMeta.key}.corrupt")
			file.renameTo(corruptFile)
			logNodeError(node, "Invalid content: signature or hash mismatch for ${eMeta.key.name} (file size ${if (corruptFile.exists()) corruptFile.length() else file.length()} bytes)")
			if (activeDownloadAttempts[eMeta.key]?.currentNode == node) {
				tryNextNodeForDownload(eMeta.key)
			}
			return
		}
		val destFile = File(contentDir, "${eMeta.key}").consistentFile()
		val fileSize = file.length()
		val moved = if (file.renameTo(destFile)) {
			true
		} else {
			try {
				file.copyTo(destFile, overwrite = true, bufferSize = DEFAULT_COPY_BUFFER_SIZE)
				file.delete()
				true
			} catch (e: Exception) {
				logNodeError(node, "Failed to move content file for ${eMeta.key.name}: ${e.message}")
				false
			}
		}
		if (moved) {
			totalContentSize.addAndGet(fileSize)
			checkAndEnforceQuota()
			if (fileSize <= SMALL_CONTENT_THRESHOLD_BYTES && smallContentCache.size < MAX_SMALL_CONTENT_ITEMS) {
				smallContentCache[eMeta.key] = destFile.readBytes()
			}
			logIncoming(node, eMeta.key.name, fileSize)
			activeDownloadAttempts.remove(eMeta.key)
			completeInFlightKey(node, eMeta)
			notifyMessageReceived(eMeta.key)
			if (relayEnabled) {
				pushNewMessage(eMeta.key, excludeNode = node)
			}
		}
	}

	private fun handleChallengeMessage(node: TCPNode, rawHead: ByteArray) {
		val msg = ChallengeMessage.read(rawHead.toDataInputStream())
		try {
			val isVirtual = node is BridgeTCPNode
			node.send(
				ChallengeResponseMessage(
					serverIdentity.asPeer(),
					serverIdentity.sign(msg.nonce),
					isRelay = if (isVirtual) false else relayEnabled,
					isBridge = if (isVirtual) false else bridgeEnabled
				).serialize()
			)
		} catch (e: Exception) {
			logNodeError(node, "Challenge response failed: ${e.message}")
		}
	}

	private fun handleChallengeResponseMessage(node: TCPNode, rawHead: ByteArray) {
		val msg = ChallengeResponseMessage.read(rawHead.toDataInputStream())
		val ourNonce = nodeChallengeMap.remove(node)
		if (ourNonce != null && msg.isValidFor(ourNonce)) {
			nodePeerMap[node] = msg.peer
			nodeRelayMap[node] = msg.isRelay
			nodeBridgeMap[node] = msg.isBridge
			if (node !is BridgeTCPNode) {
				bridgeManager.getOrAssignVirtualPort(msg.peer.key)
				nodeQueryTimeMap[node.remoteAddress]?.let { addrTime ->
					peerQueryTimeMap.putIfAbsent(msg.peer.key, addrTime)
				}
			}
			notifyNodeIdentified(node, msg.peer)
			logConnected(node, msg.peer.key.name)
			if (node !in nodesWithoutAutoSync) {
				syncNode(node)
				reconcileMissingContent(node)
			}
			if (msg.isBridge && node !is BridgeTCPNode) {
				contentSenderExecutor.execute {
					try {
						bridgeManager.requestBridgePeerList(node)
					} catch (_: Exception) {}
				}
			}
		} else {
			logNodeError(node, "Auth failed")
			node.close()
		}
	}

	private fun handlePeerRequestMessage(node: TCPNode, rawHead: ByteArray) {
		val req = PeerRequestMessage.read(rawHead.toDataInputStream())
		val foundPeers = mutableListOf<Peer>()
		req.keys.forEach { key ->
			val p = peerCache[key] ?: getIdentityByKey(IdentityKey(key.arr))?.asPeer()
			if (p != null) foundPeers.add(p)
		}
		if (foundPeers.isNotEmpty()) {
			node.send(PeerResponseMessage(foundPeers).serialize())
			debug("PeerRequest from ${node.remoteAddress}: served ${foundPeers.size} peer(s)")
		}
	}

	private fun handlePeerResponseMessage(node: TCPNode, rawHead: ByteArray) {
		val resp = PeerResponseMessage.read(rawHead.toDataInputStream())
		debug("Received PeerResponse from ${node.remoteAddress}: ${resp.peers.size} peer(s)")
		resp.peers.forEach { peer ->
			val expectedKey = PeerKey(peer.mx.toByteArray().hash256())
			if (expectedKey != peer.key) {
				logNodeError(node, "Rejecting spoofed peer ${peer.key.name}: hash mismatch")
				return@forEach
			}
			savePeer(peer, resetQueryTimes = false)
			notifyPeerReceived(peer)
			val pendingMap = pendingPeerContent.remove(peer.key)
			if (pendingMap != null) {
				val nodeKeys = mutableMapOf<TCPNode, MutableList<EncryptedMetaKey>>()
				val now = System.currentTimeMillis()
				pendingMap.forEach { (metaKey, targetNode) ->
					val activeNode = if (!targetNode.isClosed()) targetNode else allNodes.firstOrNull { !it.isClosed() }
					if (activeNode != null && !isMessageDeleted(metaKey)) {
						val metaExists = metaCache.containsKey(metaKey)
						val contentExists =
							smallContentCache.containsKey(metaKey) || File(contentDir, "$metaKey").exists()
						if (!metaExists || !contentExists) {
							pendingContentRequests[metaKey] = now
							inFlightNodeRequests[metaKey] = activeNode
							nodeKeys.computeIfAbsent(activeNode) { mutableListOf() }.add(metaKey)
						}
					}
				}
				nodeKeys.forEach { (targetNode, keys) ->
					if (keys.isNotEmpty()) {
						targetNode.send(createContentRequestMessage(keys).serialize())
						debug("Requested ${keys.size} content item(s) from ${targetNode.remoteAddress} after peer resolved")
					}
				}
			}
		}
	}

	val blockHandler = TCPBlockHandler(
		tmpDir = tmpDir,
		maxStreamSize = MAX_STREAM_SIZE,
		streamTimeoutMs = DEFAULT_STREAM_TIMEOUT_MS,
		fileStreamFactory = ::createFileStreamForHeader,
		onStreamProgress = { _, _, header, bytesWritten ->
			try {
				if (header.isNotEmpty()) {
					val dis = DataInputStream(ByteArrayInputStream(header))
					val type = StringWritable.read(dis).str
					if (type == EncryptedContentMessage.type) {
						val totalLength = dis.readLong()
						val eMeta = EncryptedContentMetaData.read(dis)
						onContentProgress?.invoke(eMeta.key, bytesWritten, totalLength)
					}
				}
			} catch (_: Exception) {}
		},
		contentHandler = contentHandler
	)

	/** Send a challenge nonce to [node] and remember it so we can verify their response. */
	internal fun sendChallenge(node: TCPNode) {
		try {
			val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
			nodeChallengeMap[node] = nonce
			node.send(ChallengeMessage(nonce).serialize())
		} catch (e: Exception) {
			logNodeError(node, "sendChallenge failed: ${e.message}")
		}
	}

	fun getTCPNode(addr: InetSocketAddress, autoSync: Boolean = true): TCPNode {
		val node = connectionMap[addr]
		if (node != null && !node.isClosed()) {
			pendingReconnects.remove(addr)
			outboundTargets[addr] = autoSync
			return node
		}
		pendingReconnects.remove(addr)
		explicitlyDisconnected.remove(addr)
		outboundTargets[addr] = autoSync
		val socket = Socket()
		try {
			socket.tcpNoDelay = true
		} catch (_: Exception) {}
		socket.connect(addr)
		val newNode = TCPNode(socket, blockHandler)
		newNode.bridgeBlockInterceptor = bridgeManager::handleBridgeRawBlock
		newNode.onTransferStateChanged = {
			checkAndNotifyTransferState()
			wakeReconnectManager()
		}
		newNode.onClose = {
			val alias = nodePeerMap[newNode]?.key?.name ?: "unknown"
			val wasRelay = nodeRelayMap[newNode] == true
			logDisconnected(newNode, alias)
			connectionMap.remove(addr)
			cleanupNodeState(newNode)
			checkAndNotifyTransferState()
			notifyNodeDisconnected(newNode)

			if (!explicitlyDisconnected.remove(addr) && outboundTargets.containsKey(addr)) {
				val now = System.currentTimeMillis()
				val delayMs = if (backgroundMode) BACKGROUND_SYNC_INTERVAL_MS else 2000L
				pendingReconnects.compute(addr) { _, existing ->
					if (existing != null && backgroundMode) {
						existing.apply {
							nextAttemptMs = now + BACKGROUND_SYNC_INTERVAL_MS
							attemptCount = 0
						}
					} else {
						if (!backgroundMode) {
							log("Scheduling auto-reconnect for $addr (first attempt in ${delayMs / 1000}s)")
						}
						ReconnectTarget(
							addr = addr,
							firstFailedAtMs = now,
							nextAttemptMs = now + delayMs,
							attemptCount = 0,
							isRelay = wasRelay,
							autoSync = outboundTargets[addr] ?: autoSync
						)
					}
				}
				wakeReconnectManager()
			}
		}
		connectionMap[addr] = newNode
		if (!autoSync) {
			nodesWithoutAutoSync.add(newNode)
		}
		notifyNodeConnected(newNode, false)
		sendChallenge(newNode)
		wakePingManager()
		if (backgroundMode) {
			wakeReconnectManager()
		}
		return newNode
	}

	fun disconnect(addr: InetSocketAddress) {
		explicitlyDisconnected.add(addr)
		outboundTargets.remove(addr)
		pendingReconnects.remove(addr)
		connectionMap[addr]?.close()
		tcpServer?.nodeList?.firstOrNull { !it.isClosed() && it.remoteAddress == addr }?.close()
		wakeReconnectManager()
	}

	fun disconnect(host: String, port: Int) {
		if (port < 0) {
			disconnectBridgedPeer(host, port)
		} else {
			disconnect(InetSocketAddress(InetAddress.getByName(host), port))
		}
	}

	// ── Bridge & Portal Delegations ───────────────────────────────────────────

	fun connectBridgedPeer(host: String, virtualPort: Int, autoSync: Boolean = true): BridgeTCPNode =
		bridgeManager.connectBridgedPeer(host, virtualPort, autoSync)

	fun disconnectBridgedPeer(host: String, virtualPort: Int) =
		bridgeManager.disconnectBridgedPeer(host, virtualPort)

	fun requestBridgePeerList(bridgeNode: TCPNode, timeoutMs: Long = 3_000L): List<BridgePeerEntry> =
		bridgeManager.requestBridgePeerList(bridgeNode, timeoutMs)

	fun discoverBridgedPeers(found: (PeerAddressInfo) -> Unit) =
		bridgeManager.discoverBridgedPeers(found)

	fun getLocalPortalMetaList(): List<ContentMeta> =
		portalManager.getLocalPortalMetaList()

	fun readLocalPortalChunk(path: String, pos: Long, maxLength: Int): ByteArray? =
		portalManager.readLocalPortalChunk(path, pos, maxLength)

	fun createPortalPack(node: TCPNode? = null): TCPPack =
		portalManager.createPortalPack(node)

	internal fun onVirtualNodeCreated(node: BridgeTCPNode, inbound: Boolean, autoSync: Boolean) {
		if (!autoSync) {
			nodesWithoutAutoSync.add(node)
		}
		notifyNodeConnected(node, inbound)
		sendChallenge(node)
	}

	internal fun onVirtualNodeClosed(node: BridgeTCPNode) {
		cleanupNodeState(node)
		checkAndNotifyTransferState()
		notifyNodeDisconnected(node)
	}

	// ── Sync & Node Modes ─────────────────────────────────────────────────────

	private fun syncNode(node: TCPNode) {
		if (node.isClosed()) return
		val myIdentityKeys = listIdentities().map { it.key }.toSet()
		if (!relayEnabled && myIdentityKeys.isEmpty()) return
		val recipientFilter = if (relayEnabled) QueryCondition.ALL
		else QueryCondition(myIdentityKeys, QueryCondition.ConditionType.Include)
		val authorFilter = if (whitelistEnabled && !relayEnabled) {
			val myPeerKeys = listPeers().map { it.key }.toSet()
			QueryCondition(myPeerKeys, QueryCondition.ConditionType.Include)
		} else {
			QueryCondition.ALL
		}
		val queryTime = getPeerQueryTime(node)
		debug("Syncing with ${node.remoteAddress} (since=$queryTime, ${if (relayEnabled) "relay/all recipients" else "recipient filter ${myIdentityKeys.joinToString { it.name }}"})")
		node.send(
			QueryMessage(
				authorFilter,
				recipientFilter,
				queryTime
			).serialize()
		)
	}

	fun isRelayEnabled(): Boolean = relayEnabled
	fun setRelayEnabled(enabled: Boolean) {
		if (relayEnabled == enabled) return
		relayEnabled = enabled
		if (enabled && backgroundMode) {
			setBackgroundMode(false)
		}
		resetPeerQueryTimes()
		restartDiscoveryServers()
		if (enabled) {
			queryAllFromConnectedNodes()
		}
	}

	fun isBridgeEnabled(): Boolean = bridgeEnabled
	fun setBridgeEnabled(enabled: Boolean) {
		if (bridgeEnabled == enabled) return
		bridgeEnabled = enabled
		if (enabled && backgroundMode) {
			setBackgroundMode(false)
		}
		if (!enabled) {
			bridgeManager.onBridgeDisabled()
		}
		restartDiscoveryServers()
	}

	private fun restartDiscoveryServers() {
		val port = serverPort ?: return
		mcd?.close()
		sd?.close()
		val discoverInfo =
			PeerAddressInfo(
				ServerKey(serverIdentity.key.arr),
				tcpServer!!.peerAddressInfo.addrList,
				port,
				relayEnabled,
				bridgeEnabled
			)
		mcd = MulticastDiscoverServer(discoverInfo)
		mcd?.start(true)
		sd = ScanDiscoverServer(discoverInfo)
		sd?.start(true)
	}

	private fun queryAllFromConnectedNodes() {
		allNodes.filter { !it.isClosed() }.forEach { node ->
			queryAllFromNode(node)
		}
	}

	fun syncAllConnectedNodes() {
		allNodes.filter { !it.isClosed() }.forEach { node ->
			syncNode(node)
			reconcileMissingContent(node)
		}
	}

	private fun queryAllFromNode(node: TCPNode) {
		if (node.isClosed()) return
		val queryAll = QueryMessage(QueryCondition.ALL, QueryCondition.ALL, 0L).serialize()
		runCatching { node.send(queryAll) }
	}

	private fun removeDeadNode(node: TCPNode) {
		val removedInbound = tcpServer?.nodeList?.remove(node) == true
		var removedOutbound = false
		synchronized(connectionMap) {
			val iter = connectionMap.entries.iterator()
			while (iter.hasNext()) {
				if (iter.next().value == node) {
					iter.remove()
					removedOutbound = true
				}
			}
		}
		val removedBridged = bridgeManager.removeNode(node)
		cleanupNodeState(node)
		if (removedInbound || removedOutbound || removedBridged) {
			checkAndNotifyTransferState()
			notifyNodeDisconnected(node)
		}
	}

	fun pushNewMessage(metaKey: EncryptedMetaKey, excludeNode: TCPNode? = null) {
		val eMeta = metaCache[metaKey] ?: return
		val contentExists = smallContentCache.containsKey(metaKey) || File(contentDir, "$metaKey").exists()
		if (!contentExists) return
		if (pendingReconnects.isNotEmpty()) {
			retryPendingConnectionsImmediately()
		}
		val header = QueryResponseMessage(0L).serialize()
		val body = ListWritable(listOf(eMeta.toHeader(getContentSize(eMeta.key)))).serialize()
		allNodes.forEach { node ->
			if (node == excludeNode) return@forEach
			if (node.isClosed()) {
				removeDeadNode(node)
				return@forEach
			}
			val query = nodeQueryMap[node]
			val isAskingAll = query?.isAskingAllRecipients() == true
			if (isAskingAll && isIgnoredForRelay(eMeta)) {
				return@forEach
			}
			val isRelay = nodeRelayMap[node] ?: false
			if (isRelay || (query != null && query.matches(eMeta))) {
				try {
					node.send(header, body)
					if (node.isClosed()) {
						removeDeadNode(node)
					}
				} catch (e: Exception) {
					logNodeError(node, "Failed to forward message to node, removing: ${e.message}")
					node.close()
					removeDeadNode(node)
				}
			}
		}
	}

	// ── Identity, Peer & Content Management ───────────────────────────────────

	private fun findPeerFile(key: PeerKey): File? {
		val expected = File(peerDir, "$key")
		if (expected.exists()) return expected
		return peerDir.listFiles { f -> f.isFile }
			?.firstOrNull { f ->
				runCatching { f.toDataInputStream().use { Peer.read(it) }.key == key }.getOrDefault(false)
			}
	}

	fun listIdentities(): List<Identity> {
		val diskIdentities = identityCache.values.toList()
		val all = diskIdentities + ephemeralIdentities
		return all.distinctBy { it.key }
	}

	fun isIdentityPersistent(key: IdentityKey): Boolean = identityCache.containsKey(key)

	fun setIdentityPersistence(key: IdentityKey, persistent: Boolean): Boolean {
		val iden = listIdentities().firstOrNull { it.key == key } ?: return false
		val targetFile = File(identityDir, "$key")
		return if (persistent) {
			targetFile.writeBytes(iden.serialize())
			identityCache[key] = iden
			true
		} else {
			identityCache.remove(key)
			if (targetFile.exists()) targetFile.delete() else true
		}
	}

	fun createIdentity(seed: String): Identity {
		val byteSeed = seed.toByteArray().hash256()
		val iden = Identity(byteSeed)
		if (listIdentities().none { it.key == iden.key }) {
			ephemeralIdentities.add(iden)
		}
		resetPeerQueryTimes()
		syncAllConnectedNodes()
		return listIdentities().first { it.key == iden.key }
	}

	fun removeIdentity(key: IdentityKey): Boolean {
		setIdentityIgnored(key, false)
		identityCache.remove(key)
		val removedEphemeral = ephemeralIdentities.removeIf { it.key == key }
		val targetFile = File(identityDir, "$key")
		val removedDisk = if (targetFile.exists()) targetFile.delete() else false
		return removedEphemeral || removedDisk
	}

	fun savePeer(peer: Peer, resetQueryTimes: Boolean = true) {
		File(peerDir, "${peer.key}").writeBytes(peer.serialize())
		peerCache[peer.key] = peer
		if (resetQueryTimes) {
			resetPeerQueryTimes()
			syncAllConnectedNodes()
		}
	}

	fun listPeers(): List<Peer> = peerCache.values.toList()

	fun removePeer(key: PeerKey): Boolean {
		peerCache.remove(key)
		val file = findPeerFile(key)
		return if (file != null) {
			file.delete()
		} else {
			log("Peer file for key ${key.name} not found, cannot remove")
			false
		}
	}

	fun pkeEncrypt(
		content: Content,
		authorIdentity: Identity,
		recipientPeer: Peer,
		timestamp: Long = nextMonotonicTimestamp(),
		onProgress: ((percent: Int, status: String) -> Unit)? = null
	): EncryptedMetaKey {
		onProgress?.invoke(5, "Analyzing content...")
		val contentKey = ContentKey(content.hash256())
		val meta = ContentMeta(content)
		val pass = Password256.createPassword()
		val eMeta = meta.encrypt(authorIdentity, recipientPeer, contentKey, pass)
		val contentFile = File(contentDir, eMeta.key.toString()).consistentFile()
		onProgress?.invoke(15, "Encrypting...")

		val totalBytes = content.length
		if (totalBytes > 0 && onProgress != null) {
			val seq = EncryptedSequence.createSequence(pass)
			contentFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { fileOut ->
				EncryptedContinuousOutputStream(seq, fileOut).use { encOut ->
					content.createInputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { inp ->
						val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
						var bytesCopied = 0L
						var lastReported = 15
						while (true) {
							val read = inp.read(buffer)
							if (read <= 0) break
							encOut.write(buffer, 0, read)
							bytesCopied += read
							val pct = 15 + ((bytesCopied * 80) / totalBytes).toInt().coerceIn(0, 80)
							if (pct >= lastReported + 2) {
								lastReported = pct
								onProgress(pct, "Encrypting (${((bytesCopied * 100) / totalBytes).toInt()}%)...")
							}
						}
					}
				}
			}
		} else {
			content.encrypt(pass, FileSink(contentFile, false))
		}
		onProgress?.invoke(98, "Finalizing message...")

		val fileSize = contentFile.length()
		totalContentSize.addAndGet(fileSize)
		val ts = timestamp
		metaCache[eMeta.key] = eMeta
		metaTimeCache[eMeta.key] = ts
		appendMetaIndex(eMeta, ts)
		decryptedMetaCache[eMeta.key] = Pair(meta, pass)
		if (fileSize <= SMALL_CONTENT_THRESHOLD_BYTES && smallContentCache.size < MAX_SMALL_CONTENT_ITEMS) {
			smallContentCache[eMeta.key] = contentFile.readBytes()
		}

		checkAndEnforceQuota()
		return eMeta.key
	}

	/**
	 * Compiles a [DirPack] from [stagingDir] into a temporary `.pack` archive, encrypts it
	 * for [recipientPeer], and cleans up temporary staging files in `finally`.
	 * Shared by synchronous API pack creation and asynchronous [CommandQueue] workers.
	 */
	fun encryptPackFromStagingDir(
		stagingDir: File,
		packName: String,
		tempId: String,
		authorIdentity: Identity,
		recipientPeer: Peer,
		timestamp: Long = nextMonotonicTimestamp(),
		onPackCompiled: (() -> Unit)? = null,
		onProgress: ((percent: Int, status: String) -> Unit)? = null
	): EncryptedMetaKey {
		val tempPackFile = File(tmpDir, "pack_$tempId.pack")
		try {
			val dirPack = DirPack(stagingDir)
			val sink = FileSink(tempPackFile, append = false)
			BinaryPack.create(dirPack, sink)
			onPackCompiled?.invoke()

			val wrappedContent = MutableMetaDataContent(FileContent(tempPackFile)).apply {
				path = packName
				ext = "pack"
			}
			return pkeEncrypt(
				wrappedContent,
				authorIdentity,
				recipientPeer,
				timestamp = timestamp,
				onProgress = onProgress
			)
		} finally {
			stagingDir.deleteRecursively()
			if (tempPackFile.exists()) {
				tempPackFile.delete()
			}
		}
	}

	private var tcpServer: TCPServer? = null
	private var mcd: MulticastDiscoverServer? = null
	private var sd: ScanDiscoverServer? = null
	private val activeMulticastDiscoveries = AtomicInteger(0)

	fun isMulticastNeeded(): Boolean =
		(tcpServer != null && !tcpServer!!.socketServer.isClosed) || activeMulticastDiscoveries.get() > 0

	fun startTCPServer(port: Int) {
		val nodeList = CopyOnWriteArrayList<TCPNode>()
		TCPServer(
			blockHandler = blockHandler,
			nodeList = nodeList,
			address = InetSocketAddress(port),
			serverKey = ServerKey(serverIdentity.key.arr),
			onAccept = { node ->
				node.bridgeBlockInterceptor = bridgeManager::handleBridgeRawBlock
				node.onTransferStateChanged = { checkAndNotifyTransferState() }
				node.onClose = {
					val alias = nodePeerMap[node]?.key?.name ?: "unknown"
					logDisconnected(node, alias)
					nodeList.remove(node)
					cleanupNodeState(node)
					checkAndNotifyTransferState()
					notifyNodeDisconnected(node)
				}
				notifyNodeConnected(node, true)
				sendChallenge(node)
			}
		).also {
			tcpServer = it
			it.start(false)
			log("TCP server started on ${it.peerAddressInfo}")
			if (backgroundMode) {
				setBackgroundMode(false)
			}
			notifyMulticastStateChanged()
			val discoverInfo =
				PeerAddressInfo(
					ServerKey(serverIdentity.key.arr),
					it.peerAddressInfo.addrList,
					it.peerAddressInfo.port,
					relayEnabled,
					bridgeEnabled
				)
			mcd = MulticastDiscoverServer(discoverInfo)
			mcd?.start(true)
			log("Multicast discovery server started")
			sd = ScanDiscoverServer(discoverInfo)
			sd?.start(true)
			log("Scan discovery server started")
		}
	}

	fun stopTCPServer() {
		closeAll(listOfNotNull(tcpServer, mcd, sd))
		tcpServer = null
		mcd = null
		sd = null
		notifyMulticastStateChanged()
		pendingReconnects.clear()
		explicitlyDisconnected.clear()
		activeStreamVerifiers.values.forEach { it.close() }
		activeStreamVerifiers.clear()
		log("Server stopped")
	}

	fun shutdown() {
		outboundTargets.clear()
		pendingReconnects.clear()
		bridgeManager.closeAllVirtualNodes()
		val outboundNodes = synchronized(connectionMap) {
			val list = connectionMap.values.toList()
			connectionMap.clear()
			list
		}
		closeAll(outboundNodes)
		stopTCPServer()
		wakeReconnectManager()
		wakePingManager()
		if (uncompactedDeletes.get() > 0) {
			saveMetaIndex()
		}
	}

	fun getIdentityByKey(key: IdentityKey): Identity? =
		if (serverIdentity.key == key) serverIdentity
		else identityCache[key] ?: ephemeralIdentities.firstOrNull { it.key == key }

	fun getPeerByKey(key: PeerKey): Peer? = peerCache[key]

	fun getMetaByKey(key: EncryptedMetaKey): EncryptedContentMetaData? = metaCache[key]

	fun getMetaTimeByKey(key: EncryptedMetaKey): Long? = metaTimeCache[key]

	fun listAllMeta(): Collection<EncryptedContentMetaData> = metaCache.values

	fun getMessageKeysInTimeOrder(): List<String> =
		metaCache.keys
			.sortedBy { metaTimeCache[it] ?: 0L }
			.map { it.toString() }

	fun getDecryptedMeta(key: EncryptedMetaKey): Pair<ContentMeta, Password256>? {
		decryptedMetaCache[key]?.let { return it }
		val eMeta = metaCache[key] ?: return null
		val recipientIdentity = getIdentityByKey(eMeta.recipient) ?: return null
		return try {
			val pair = eMeta.decrypt(recipientIdentity)
			decryptedMetaCache[key] = pair
			pair
		} catch (_: Exception) {
			null
		}
	}

	fun getContent(key: EncryptedMetaKey): Content {
		val (meta, pass) = getDecryptedMeta(key)
			?: error("Failed to decrypt metadata or meta not found for key: $key")
		val smallBytes = smallContentCache[key]
		val contentSource = if (smallBytes != null) {
			BinarySource(smallBytes)
		} else {
			val contentFile = File(contentDir, "$key").consistentFile()
			if (!contentFile.exists()) {
				error("Content file not found for key name: ${key.name}")
			}
			FileSource(contentFile)
		}
		return EncryptContent(pass, contentSource, meta)
	}

	fun hasContent(key: EncryptedMetaKey): Boolean =
		smallContentCache.containsKey(key) || File(contentDir, "$key").exists()

	fun isMessageDeleted(key: EncryptedMetaKey): Boolean =
		deletedCache.contains(key)

	@Synchronized
	fun deleteMessage(key: EncryptedMetaKey): Boolean {
		setContentIgnored(key, false)
		queryOrderTimestamps.remove(key)
		val hadMeta = metaCache.containsKey(key) || isMessageDeleted(key)
		pendingContentRequests.remove(key)
		decryptedMetaCache.remove(key)
		val removedMeta = metaCache.remove(key) != null
		metaTimeCache.remove(key)
		smallContentCache.remove(key)
		if (deletedCache.add(key)) {
			appendDeletedIndex(key)
		}
		if (removedMeta && uncompactedDeletes.incrementAndGet() >= META_INDEX_COMPACT_THRESHOLD) {
			saveMetaIndex()
		}
		val contentFile = File(contentDir, "$key")
		val size = if (contentFile.exists()) contentFile.length() else 0L
		if (size > 0L) {
			if (contentFile.delete()) {
				totalContentSize.addAndGet(-size)
			} else {
				log("Failed to delete content file for ${key.name}")
			}
		}
		return hadMeta
	}

	data class ImportResult(
		val importedMessages: Int,
		val skippedMessages: Int,
		val importedPeers: Int
	)

	fun getMessagesForIdentity(identityKey: IdentityKey): List<EncryptedMetaKey> =
		metaCache.values.filter { it.recipient == identityKey }.map { it.key }

	fun exportMessages(keys: Collection<EncryptedMetaKey>, destinationFile: File): Int =
		indexStore.exportMessages(this, keys, destinationFile)

	fun importPack(source: Source): ImportResult =
		indexStore.importPack(this, source)

	internal fun putImportedMeta(
		key: EncryptedMetaKey,
		eMeta: EncryptedContentMetaData,
		timestamp: Long,
		serializedBytes: ByteArray
	) {
		metaCache[key] = eMeta
		metaTimeCache[key] = timestamp
		appendMetaIndex(eMeta, timestamp, serializedBytes)
	}

	internal fun onImportedContentStored(key: EncryptedMetaKey, contentFile: File) {
		val sz = contentFile.length()
		totalContentSize.addAndGet(sz)
		if (sz <= SMALL_CONTENT_THRESHOLD_BYTES && smallContentCache.size < MAX_SMALL_CONTENT_ITEMS) {
			smallContentCache[key] = contentFile.readBytes()
		}
	}

	fun isIgnoredForRelay(eMeta: EncryptedContentMetaData): Boolean {
		if (isContentIgnored(eMeta.key)) return true
		if (isIdentityIgnored(eMeta.recipient)) return true
		if (isIdentityIgnored(IdentityKey(eMeta.author.arr))) return true
		return false
	}

	fun isIgnoredForRelay(header: EncryptedContentHeader): Boolean {
		if (isContentIgnored(header.key)) return true
		if (isIdentityIgnored(header.recipient)) return true
		if (isIdentityIgnored(IdentityKey(header.author.arr))) return true
		return false
	}

	fun isIdentityIgnored(key: IdentityKey): Boolean = ignoredIdentitiesCache.contains(key)
	fun setIdentityIgnored(key: IdentityKey, ignored: Boolean): Boolean {
		val changed = if (ignored) {
			ignoredIdentitiesCache.add(key)
		} else {
			ignoredIdentitiesCache.remove(key)
		}
		if (changed) {
			saveIgnoredIndex()
		}
		if (!ignored) {
			pushMessagesForIdentity(key)
		}
		return changed
	}

	fun isContentIgnored(key: EncryptedMetaKey): Boolean = ignoredContentCache.contains(key)
	fun setContentIgnored(key: EncryptedMetaKey, ignored: Boolean): Boolean {
		val changed = if (ignored) {
			ignoredContentCache.add(key)
		} else {
			ignoredContentCache.remove(key)
		}
		if (changed) {
			saveIgnoredIndex()
		}
		if (!ignored) {
			pushNewMessage(key)
		}
		return changed
	}

	fun listIgnoredIdentities(): Set<IdentityKey> = ignoredIdentitiesCache.toSet()
	fun listIgnoredContent(): Set<EncryptedMetaKey> = ignoredContentCache.toSet()

	fun getNodeAlias(node: TCPNode): String =
		nodePeerMap[node]?.key?.name ?: node.remoteAddress.address?.hostAddress ?: node.remoteAddress.hostString

	fun getNodeIp(node: TCPNode): String =
		node.remoteAddress.address?.hostAddress ?: node.remoteAddress.hostString

	fun logConnected(node: TCPNode, alias: String = getNodeAlias(node)) {
		log("[connected, ${getNodeIp(node)}, $alias]")
	}

	fun logDisconnected(node: TCPNode, alias: String = getNodeAlias(node)) {
		log("[disconnected, ${getNodeIp(node)}, $alias]")
	}

	fun logIncoming(node: TCPNode, contentAlias: String, fileSize: Long) {
		log("[incoming, ${getNodeAlias(node)}, $contentAlias, $fileSize]")
	}

	fun logOutgoing(node: TCPNode, contentAlias: String, fileSize: Long) {
		log("[outgoing, ${getNodeAlias(node)}, $contentAlias, $fileSize]")
	}

	fun logNodeError(node: TCPNode, error: String) {
		log("[${getNodeAlias(node)}, $error]")
	}

	private fun pushMessagesForIdentity(key: IdentityKey) {
		metaCache.values.filter {
			(it.recipient == key || it.author.arr.contentEquals(key.arr)) && !isIgnoredForRelay(it)
		}.forEach { eMeta ->
			pushNewMessage(eMeta.key)
		}
	}

	private val ephemeralIdentities = CopyOnWriteArrayList<Identity>()
	private val connectionMap = Collections.synchronizedMap(mutableMapOf<InetSocketAddress, TCPNode>())
	private val outboundTargets = ConcurrentHashMap<InetSocketAddress, Boolean>()
	private val nodesWithoutAutoSync = Collections.synchronizedSet(mutableSetOf<TCPNode>())

	@Volatile
	private var backgroundMode: Boolean = false

	fun isBackgroundMode(): Boolean = backgroundMode

	fun setBackgroundMode(enabled: Boolean) {
		val effective = enabled && serverPort == null && !relayEnabled && !bridgeEnabled
		if (backgroundMode == effective) return
		backgroundMode = effective
		if (effective) {
			log("P2P entering background connection mode (periodic client sync every ${BACKGROUND_SYNC_INTERVAL_MS / 60000}m)")
			wakeReconnectManager()
		} else {
			log("P2P exiting background connection mode — restoring persistent outbound connections")
			retryPendingConnectionsImmediately()
			wakePingManager()
		}
	}

	data class ReconnectTarget(
		val addr: InetSocketAddress,
		val firstFailedAtMs: Long,
		var nextAttemptMs: Long,
		var attemptCount: Int = 0,
		val isRelay: Boolean = false,
		val autoSync: Boolean = true
	)

	private val pendingReconnects = ConcurrentHashMap<InetSocketAddress, ReconnectTarget>()
	private val explicitlyDisconnected = Collections.synchronizedSet(mutableSetOf<InetSocketAddress>())
	@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
	private val reconnectSignal = java.lang.Object()
	@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
	private val pingSignal = java.lang.Object()

	private fun wakeReconnectManager() {
		synchronized(reconnectSignal) {
			reconnectSignal.notifyAll()
		}
	}

	private fun wakePingManager() {
		synchronized(pingSignal) {
			pingSignal.notifyAll()
		}
	}

	// ── Connection lifecycle callbacks ────────────────────────────────────────
	private val onNodeConnectedListeners = mutableListOf<(node: TCPNode, inbound: Boolean) -> Unit>()
	private val onNodeDisconnectedListeners = mutableListOf<(node: TCPNode) -> Unit>()
	private val onTransferStateChangedListeners = mutableListOf<(isTransferring: Boolean) -> Unit>()
	private val onMulticastStateChangedListeners = mutableListOf<(needed: Boolean) -> Unit>()
	private val onNodeIdentifiedListeners = mutableListOf<(node: TCPNode, peer: Peer) -> Unit>()
	private val onPeerReceivedListeners = mutableListOf<(peer: Peer) -> Unit>()
	private val onMessageReceivedListeners = mutableListOf<(key: EncryptedMetaKey) -> Unit>()

	fun addOnNodeConnectedListener(listener: (node: TCPNode, inbound: Boolean) -> Unit) {
		onNodeConnectedListeners.add(listener)
	}

	fun addOnNodeDisconnectedListener(listener: (node: TCPNode) -> Unit) {
		onNodeDisconnectedListeners.add(listener)
	}

	fun addOnTransferStateChangedListener(listener: (isTransferring: Boolean) -> Unit) {
		onTransferStateChangedListeners.add(listener)
	}

	fun addOnMulticastStateChangedListener(listener: (needed: Boolean) -> Unit) {
		onMulticastStateChangedListeners.add(listener)
	}

	fun addOnNodeIdentifiedListener(listener: (node: TCPNode, peer: Peer) -> Unit) {
		onNodeIdentifiedListeners.add(listener)
	}

	fun addOnPeerReceivedListener(listener: (peer: Peer) -> Unit) {
		onPeerReceivedListeners.add(listener)
	}

	fun addOnMessageReceivedListener(listener: (key: EncryptedMetaKey) -> Unit) {
		onMessageReceivedListeners.add(listener)
	}

	internal fun notifyNodeConnected(node: TCPNode, inbound: Boolean) {
		for (listener in onNodeConnectedListeners) {
			try {
				listener(node, inbound)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	internal fun notifyNodeDisconnected(node: TCPNode) {
		contentHolders.values.forEach { it.remove(node) }
		for (listener in onNodeDisconnectedListeners) {
			try {
				listener(node)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	private fun notifyNodeIdentified(node: TCPNode, peer: Peer) {
		for (listener in onNodeIdentifiedListeners) {
			try {
				listener(node, peer)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	private fun notifyPeerReceived(peer: Peer) {
		for (listener in onPeerReceivedListeners) {
			try {
				listener(peer)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	private fun notifyMessageReceived(key: EncryptedMetaKey) {
		for (listener in onMessageReceivedListeners) {
			try {
				listener(key)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	fun isAnyTransferActive(): Boolean = allNodes.any { !it.isClosed() && it.isTransferring() }

	internal fun checkAndNotifyTransferState() {
		val transferring = isAnyTransferActive()
		for (listener in onTransferStateChangedListeners) {
			try {
				listener(transferring)
			} catch (_: Exception) {
			}
		}
	}

	private fun notifyMulticastStateChanged() {
		val needed = isMulticastNeeded()
		for (listener in onMulticastStateChangedListeners) {
			try {
				listener(needed)
			} catch (_: Exception) {
			}
		}
	}

	private val pendingPeerContent = ConcurrentHashMap<PeerKey, ConcurrentHashMap<EncryptedMetaKey, TCPNode>>()

	@Volatile
	private var whitelistEnabled: Boolean = false
	fun isWhitelistEnabled(): Boolean = whitelistEnabled
	fun setWhitelistEnabled(enabled: Boolean) {
		if (whitelistEnabled == enabled) return
		whitelistEnabled = enabled
		resetPeerQueryTimes()
		if (!relayEnabled) {
			syncAllConnectedNodes()
		}
	}

	private val nodePeerMap = ConcurrentHashMap<TCPNode, Peer>()

	internal fun getNodePeer(node: TCPNode): Peer? = nodePeerMap[node]

	fun getNodeForPeer(peerKey: PeerKey): TCPNode? =
		nodePeerMap.entries.firstOrNull { it.value.key == peerKey && !it.key.isClosed() }?.key

	fun isConnectedToPeer(peerKey: PeerKey): Boolean = getNodeForPeer(peerKey) != null

	private class ContentDownloadAttempt(
		val key: EncryptedMetaKey,
		val remainingNodes: ConcurrentLinkedQueue<TCPNode>,
		val isManual: Boolean,
		@Volatile var currentNode: TCPNode? = null,
		@Volatile var streamStarted: Boolean = false,
		@Volatile var attemptId: Long = 0L
	)

	private val activeDownloadAttempts = ConcurrentHashMap<EncryptedMetaKey, ContentDownloadAttempt>()
	private val downloadAttemptCounter = AtomicLong(0L)

	private fun buildCandidateNodes(key: EncryptedMetaKey): List<TCPNode> {
		val knownHolders = contentHolders[key]?.filter { !it.isClosed() }.orEmpty()
		val openNodes = allNodes.filter { !it.isClosed() }
		val holderDirect = knownHolders.filter { !isNodeRelay(it) && it !is BridgeTCPNode }
		val holderBridged = knownHolders.filter { !isNodeRelay(it) && it is BridgeTCPNode }
		val holderRelays = knownHolders.filter { isNodeRelay(it) }
		val otherDirect = openNodes.filter { !isNodeRelay(it) && it !is BridgeTCPNode && !isNodeBridge(it) }
		val otherBridged = openNodes.filter { !isNodeRelay(it) && it is BridgeTCPNode }
		val otherRelays = openNodes.filter { isNodeRelay(it) }
		return (holderDirect + holderBridged + holderRelays + otherDirect + otherBridged + otherRelays).distinct()
	}

	fun requestContentDownload(key: EncryptedMetaKey): Boolean {
		if (hasContent(key)) return true
		val candidates = buildCandidateNodes(key)
		if (candidates.isEmpty()) return false

		val attempt = ContentDownloadAttempt(
			key = key,
			remainingNodes = ConcurrentLinkedQueue(candidates),
			isManual = true
		)
		activeDownloadAttempts[key] = attempt
		return tryNextNodeForDownload(key)
	}

	private fun tryNextNodeForDownload(key: EncryptedMetaKey): Boolean {
		if (hasContent(key) || isMessageDeleted(key)) {
			activeDownloadAttempts.remove(key)
			return true
		}
		val attempt = activeDownloadAttempts[key] ?: return false
		var nextNode: TCPNode? = null
		var currentAttemptId = 0L
		synchronized(attempt) {
			var candidate = attempt.remainingNodes.poll()
			while (candidate != null && candidate.isClosed()) {
				candidate = attempt.remainingNodes.poll()
			}
			nextNode = candidate
			if (nextNode == null) {
				activeDownloadAttempts.remove(key, attempt)
				pendingContentRequests.remove(key)
				inFlightNodeRequests.remove(key)
			} else {
				currentAttemptId = downloadAttemptCounter.incrementAndGet()
				attempt.currentNode = nextNode
				attempt.streamStarted = false
				attempt.attemptId = currentAttemptId
				pendingContentRequests[key] = System.currentTimeMillis()
				inFlightNodeRequests[key] = nextNode
			}
		}
		val targetNode = nextNode
		if (targetNode == null) {
			debug("Exhausted all candidate nodes for content ${key.name}")
			if (attempt.isManual) {
				onContentDownloadFailed?.invoke(key, "No connected peer has this content")
			}
			return false
		}
		return try {
			debug("Requesting content ${key.name} from ${targetNode.remoteAddress} (${attempt.remainingNodes.size} fallback node(s) remaining)")
			targetNode.send(createContentRequestMessage(listOf(key)).serialize())
			contentSenderExecutor.execute {
				try {
					Thread.sleep(5_000L)
					val current = activeDownloadAttempts[key]
					if (current != null && current.attemptId == currentAttemptId && !current.streamStarted && !hasContent(key)) {
						debug("No response within 5s from ${targetNode.remoteAddress} for ${key.name}, trying next node")
						tryNextNodeForDownload(key)
					}
				} catch (_: InterruptedException) {}
			}
			true
		} catch (e: Exception) {
			logNodeError(targetNode, "Failed to request content ${key.name}: ${e.message}")
			tryNextNodeForDownload(key)
		}
	}

	private val peerQueryTimeMap = ConcurrentHashMap<PeerKey, Long>()
	private val nodeQueryTimeMap = ConcurrentHashMap<InetSocketAddress, Long>()

	fun getPeerQueryTime(node: TCPNode): Long {
		val peerKey = nodePeerMap[node]?.key
		if (peerKey != null) {
			val t = peerQueryTimeMap[peerKey]
			if (t != null) return t
		}
		if (node is BridgeTCPNode) return 0L
		return nodeQueryTimeMap[node.remoteAddress] ?: 0L
	}

	fun setPeerQueryTime(node: TCPNode, time: Long) {
		if (node !is BridgeTCPNode) {
			nodeQueryTimeMap[node.remoteAddress] = time
		}
		val peerKey = nodePeerMap[node]?.key
		if (peerKey != null) {
			peerQueryTimeMap[peerKey] = time
		}
	}

	fun resetPeerQueryTimes() {
		peerQueryTimeMap.clear()
		nodeQueryTimeMap.clear()
	}

	private val pendingContentRequests = ConcurrentHashMap<EncryptedMetaKey, Long>()
	private val inFlightNodeRequests = ConcurrentHashMap<EncryptedMetaKey, TCPNode>()
	private val pendingBatchWatermark = ConcurrentHashMap<TCPNode, Long>()
	private val batchPendingKeys = ConcurrentHashMap<TCPNode, MutableSet<EncryptedMetaKey>>()

	internal fun cleanupNodeState(node: TCPNode) {
		val disconnectedPeerKey = nodePeerMap.remove(node)?.key
		nodeRelayMap.remove(node)
		nodeBridgeMap.remove(node)
		nodeChallengeMap.remove(node)
		nodeQueryMap.remove(node)
		nodesWithoutAutoSync.remove(node)
		if (node !is BridgeTCPNode) {
			nodeQueryTimeMap.remove(node.remoteAddress)
			bridgeManager.onDirectNodeClosed(node, disconnectedPeerKey)
		}

		requestTracker.cancelForNode(node)
		pendingBatchWatermark.remove(node)
		batchPendingKeys.remove(node)
		inFlightNodeRequests.entries.removeIf { entry ->
			if (entry.value == node) {
				pendingContentRequests.remove(entry.key)
				activeStreamVerifiers.remove(entry.key)?.close()
				true
			} else false
		}
		pendingPeerContent.values.forEach { map -> map.entries.removeIf { it.value == node } }
		val affectedAttempts = activeDownloadAttempts.values.filter { it.currentNode == node }
		affectedAttempts.forEach { attempt ->
			tryNextNodeForDownload(attempt.key)
		}
	}

	fun findConnectedNode(host: String, port: Int): TCPNode? {
		val normalizedHost = host.trim()
		if (port < 0) {
			return bridgeManager.findConnectedVirtualNode(normalizedHost, port)
		}
		synchronized(connectionMap) {
			connectionMap.entries.firstOrNull { (addr, node) ->
				!node.isClosed() && addr.port == port &&
					(addr.address?.hostAddress == normalizedHost || addr.hostString == normalizedHost)
			}?.value?.let { return it }
		}
		tcpServer?.nodeList?.firstOrNull { node ->
			!node.isClosed() && node.remoteAddress.port == port &&
				(node.remoteAddress.address?.hostAddress == normalizedHost || node.remoteAddress.hostString == normalizedHost)
		}?.let { return it }
		return null
	}

	fun reconcileMissingContent(node: TCPNode) {
		if (node.isClosed()) return
		val missingContentKeys = mutableListOf<EncryptedMetaKey>()
		val missingPeerKeys = mutableSetOf<PeerKey>()
		val now = System.currentTimeMillis()

		metaCache.values.forEach { eMeta ->
			val key = eMeta.key
			if (isMessageDeleted(key) || isContentIgnored(key)) return@forEach
			if (hasContent(key)) return@forEach

			val contentLength = getKnownContentSize(key)
			if (!relayEnabled && autoDownloadThresholdBytes > 0L && (contentLength > autoDownloadThresholdBytes || contentLength == 0L)) {
				return@forEach
			}

			val authorPeer = getPeerByKey(eMeta.author)
			if (authorPeer == null) {
				missingPeerKeys.add(eMeta.author)
				pendingPeerContent.computeIfAbsent(eMeta.author) { ConcurrentHashMap() }[key] = node
			} else {
				val pendingSince = pendingContentRequests[key]
				if (pendingSince == null || (now - pendingSince) > 15_000L) {
					missingContentKeys.add(key)
				}
			}
		}

		if (missingPeerKeys.isNotEmpty()) {
			try {
				node.send(PeerRequestMessage(missingPeerKeys.toList()).serialize())
				debug("Reconcile with ${node.remoteAddress}: requested ${missingPeerKeys.size} missing peer(s)")
			} catch (e: Exception) {
				logNodeError(node, "Reconcile PeerRequest failed: ${e.message}")
			}
		}

		if (missingContentKeys.isNotEmpty()) {
			missingContentKeys.chunked(50).forEach { batch ->
				try {
					batch.forEach {
						pendingContentRequests[it] = now
						inFlightNodeRequests[it] = node
					}
					node.send(createContentRequestMessage(batch).serialize())
					log("Reconcile with ${node.remoteAddress}: requested ${batch.size} missing content item(s)")
				} catch (e: Exception) {
					logNodeError(node, "Reconcile ContentRequest failed: ${e.message}")
				}
			}
		}
	}

	private fun createContentRequestMessage(keys: List<EncryptedMetaKey>): ContentRequestMessage {
		val offsets = mutableMapOf<EncryptedMetaKey, Long>()
		keys.forEach { key ->
			val partFile = File(tmpDir, "$key.part")
			if (partFile.exists() && partFile.length() > 0L) {
				offsets[key] = partFile.length()
			}
		}
		return ContentRequestMessage(keys, offsets)
	}

	private fun createFileStreamForHeader(node: TCPNode, header: ByteArray): TCPFileStream {
		try {
			if (header.isNotEmpty()) {
				val dis = DataInputStream(ByteArrayInputStream(header))
				val type = StringWritable.read(dis).str
				if (type == EncryptedContentMessage.type) {
					dis.readLong() // contentLength
					val eMeta = EncryptedContentMetaData.read(dis)
					activeDownloadAttempts[eMeta.key]?.let { attempt ->
						attempt.streamStarted = true
					}
					contentHolders.computeIfAbsent(eMeta.key) { ConcurrentHashMap.newKeySet() }.add(node)
					val offset = if (dis.available() >= 8) {
						try { dis.readLong() } catch (_: Exception) { 0L }
					} else 0L

					val partFile = File(tmpDir, "${eMeta.key}.part")
					if (offset > 0L && partFile.exists()) {
						log("Resuming transfer for ${eMeta.key.name} from offset $offset (current part size: ${partFile.length()} bytes)")
						return TCPFileStream(partFile, append = true, deleteOnClose = false)
					} else {
						activeStreamVerifiers.remove(eMeta.key)?.close()
						val identity = getIdentityByKey(eMeta.recipient)
						val verifier = if (identity != null) {
							try {
								val (meta, pass) = eMeta.decrypt(identity)
								StreamingHashVerifier(pass, eMeta.contentKey.arr, meta.length)
							} catch (e: Exception) {
								log("Failed to initialize StreamingHashVerifier for ${eMeta.key.name}: ${e.message}")
								null
							}
						} else null

						if (verifier != null) {
							activeStreamVerifiers[eMeta.key] = verifier
						}

						return TCPFileStream(
							partFile,
							append = false,
							deleteOnClose = false,
							onChunk = verifier?.let { v -> { chunk: ByteArray -> v.update(chunk) } }
						)
					}
				}
			}
		} catch (e: Exception) {
			log("createFileStreamForHeader fallback: ${e.message}")
		}
		val f = try {
			File.createTempFile("r3tmp_", null, tmpDir)
		} catch (_: Exception) {
			File(tmpDir, "r3tmp_${System.currentTimeMillis()}_${SecureRandom().nextLong()}")
		}
		return TCPFileStream(f, append = false, deleteOnClose = true)
	}

	private val nodeRelayMap = ConcurrentHashMap<TCPNode, Boolean>()
	fun isNodeRelay(node: TCPNode): Boolean = nodeRelayMap[node] ?: false

	private val nodeBridgeMap = ConcurrentHashMap<TCPNode, Boolean>()
	fun isNodeBridge(node: TCPNode): Boolean = nodeBridgeMap[node] ?: false

	internal val directNodes: List<TCPNode>
		get() = synchronized(connectionMap) { connectionMap.values.toList() } + (tcpServer?.nodeList ?: emptyList())

	internal fun getDirectNodeForPeer(peerKey: PeerKey): TCPNode? =
		directNodes.firstOrNull { !it.isClosed() && nodePeerMap[it]?.key == peerKey }

	private val nodeChallengeMap = ConcurrentHashMap<TCPNode, ByteArray>()
	private val nodeQueryMap = ConcurrentHashMap<TCPNode, QueryMessage>()

	val serverPort: Int? get() = tcpServer?.takeIf { !it.socketServer.isClosed }?.socketServer?.localPort

	private val allNodes: List<TCPNode>
		get() = directNodes + bridgeManager.virtualNodes()

	// ── Active connection listing ─────────────────────────────────────────────
	data class ConnectionInfo(
		val addr: InetSocketAddress,
		val inbound: Boolean,
		val peerKey: String? = null,
		val isRelay: Boolean = false,
		val port: Int = addr.port,
		val isBridge: Boolean = false,
		val isBridged: Boolean = false
	)

	data class ConnectionNodeInfo(
		val addr: InetSocketAddress,
		val inbound: Boolean,
		val peerKey: String? = null,
		val peerName: String? = null,
		val isRelay: Boolean = false,
		val isTransferring: Boolean = false,
		val isSending: Boolean = false,
		val isReceiving: Boolean = false,
		val port: Int = addr.port,
		val isBridge: Boolean = false,
		val isBridged: Boolean = false
	)

	private fun toConnectionNodeInfo(
		node: TCPNode,
		addr: InetSocketAddress,
		port: Int,
		inbound: Boolean,
		isBridged: Boolean,
		fallbackPeerKey: PeerKey? = null
	): ConnectionNodeInfo {
		val peerKeyObj = nodePeerMap[node]?.key ?: fallbackPeerKey
		return ConnectionNodeInfo(
			addr = addr,
			inbound = inbound,
			peerKey = peerKeyObj?.toString(),
			peerName = peerKeyObj?.name,
			isRelay = if (isBridged) false else (nodeRelayMap[node] ?: false),
			isTransferring = node.isTransferring(),
			isSending = node.isSending(),
			isReceiving = node.isReceiving(),
			port = port,
			isBridge = if (isBridged) false else (nodeBridgeMap[node] ?: false),
			isBridged = isBridged
		)
	}

	fun listDetailedConnections(): List<ConnectionNodeInfo> {
		val result = mutableListOf<ConnectionNodeInfo>()
		synchronized(connectionMap) {
			connectionMap.forEach { (addr, node) ->
				if (!node.isClosed()) {
					result.add(toConnectionNodeInfo(node, addr, addr.port, inbound = false, isBridged = false))
				}
			}
		}
		tcpServer?.nodeList?.forEach { node ->
			if (!node.isClosed()) {
				result.add(toConnectionNodeInfo(node, node.remoteAddress, node.remoteAddress.port, inbound = true, isBridged = false))
			}
		}
		bridgeManager.virtualNodes().forEach { vNode ->
			if (!vNode.isClosed()) {
				result.add(
					toConnectionNodeInfo(
						node = vNode,
						addr = vNode.remoteAddress,
						port = vNode.remotePort,
						inbound = bridgeManager.isInbound(vNode),
						isBridged = true,
						fallbackPeerKey = PeerKey(vNode.remotePeerKeyBytes)
					)
				)
			}
		}
		return result
	}

	fun listConnections(): List<ConnectionInfo> =
		listDetailedConnections().map { info ->
			ConnectionInfo(
				addr = info.addr,
				inbound = info.inbound,
				peerKey = info.peerKey,
				isRelay = info.isRelay,
				port = info.port,
				isBridge = info.isBridge,
				isBridged = info.isBridged
			)
		}

	// ── Async discovery ───────────────────────────────────────────────────────
	fun discoverAsync(scan: Boolean, found: (PeerAddressInfo) -> Unit, done: () -> Unit = {}) {
		thread(isDaemon = true, name = "GraffitiDiscover") {
			val seen = Collections.synchronizedSet(LinkedHashSet<ServerKey>())
			fun notify(addr: PeerAddressInfo) {
				if (seen.add(addr.serverKey)) found(addr)
			}
			activeMulticastDiscoveries.incrementAndGet()
			notifyMulticastStateChanged()
			val mFuture = async {
				try {
					MulticastDiscover().discover { notify(it) }
				} finally {
					if (activeMulticastDiscoveries.decrementAndGet() <= 0) {
						activeMulticastDiscoveries.set(0)
						notifyMulticastStateChanged()
					}
				}
			}
			if (scan) {
				ScanDiscover().discover { notify(it) }
				Thread.sleep(6000)
			}
			mFuture.join()
			try {
				discoverBridgedPeers { notify(it) }
			} catch (e: Exception) {
				debug("BridgeDiscovery error: ${e.message}")
			}
			done()
		}
	}

	init {
		thread(isDaemon = true, name = "GraffitiPing") {
			while (!Thread.interrupted()) {
				try {
					val hasActiveOutbound = !backgroundMode && synchronized(connectionMap) {
						connectionMap.values.any { !it.isClosed() }
					}
					val sleepMs = if (hasActiveOutbound) PING_INTERVAL_MS else 30 * 60 * 1000L
					synchronized(pingSignal) {
						pingSignal.wait(sleepMs)
					}
					pingAndCleanup()
				} catch (_: InterruptedException) {
					break
				} catch (e: Exception) {
					log("GraffitiPing: ${e.message}")
				}
			}
		}

		thread(isDaemon = true, name = "GraffitiReconnect") {
			while (!Thread.interrupted()) {
				try {
					val waitMs = computeNextReconnectWaitMs()
					synchronized(reconnectSignal) {
						if (waitMs == Long.MAX_VALUE) {
							reconnectSignal.wait()
						} else if (waitMs > 0L) {
							reconnectSignal.wait(waitMs)
						}
					}
					processBackgroundIdleDisconnects()
					processPendingReconnects()
				} catch (_: InterruptedException) {
					break
				} catch (e: Exception) {
					log("GraffitiReconnect: ${e.message}")
				}
			}
		}
	}

	private fun computeNextReconnectWaitMs(): Long {
		val now = System.currentTimeMillis()
		var waitMs = Long.MAX_VALUE
		if (backgroundMode && serverPort == null && !relayEnabled && !bridgeEnabled) {
			val hasOpenOutbound = synchronized(connectionMap) {
				connectionMap.values.any { !it.isClosed() }
			}
			if (hasOpenOutbound) {
				waitMs = BACKGROUND_QUIET_MS
			}
		}
		if (pendingReconnects.isNotEmpty()) {
			val earliestDue = pendingReconnects.values.minOfOrNull { it.nextAttemptMs }
			if (earliestDue != null) {
				val dueIn = (earliestDue - now).coerceAtLeast(0L)
				waitMs = minOf(waitMs, dueIn)
			}
		}
		return waitMs
	}

	private fun isOutboundSyncIdle(now: Long): Boolean {
		if (isAnyTransferActive()) return false
		if (inFlightNodeRequests.isNotEmpty()) return false
		if (batchPendingKeys.isNotEmpty()) return false
		if (pendingPeerContent.isNotEmpty()) return false
		if (nodeChallengeMap.isNotEmpty()) return false
		if (activeWatermarkSupplier?.invoke() != null) return false

		val openOutbound = synchronized(connectionMap) {
			connectionMap.values.filter { !it.isClosed() }
		}
		if (openOutbound.isEmpty()) return false
		return openOutbound.all { node ->
			(now - node.connectedAtMs) >= BACKGROUND_IDLE_GRACE_MS &&
				(now - node.lastActivityMs) >= BACKGROUND_QUIET_MS &&
				(now - node.lastWriteMs) >= BACKGROUND_QUIET_MS
		}
	}

	private fun processBackgroundIdleDisconnects() {
		if (!backgroundMode || serverPort != null || relayEnabled || bridgeEnabled) return
		val now = System.currentTimeMillis()
		if (!isOutboundSyncIdle(now)) return

		val toClose = synchronized(connectionMap) {
			connectionMap.entries.filter { !it.value.isClosed() }.map { it.key to it.value }
		}
		for ((addr, node) in toClose) {
			val wasRelay = nodeRelayMap[node] == true
			val autoSync = outboundTargets[addr] ?: true
			pendingReconnects[addr] = ReconnectTarget(
				addr = addr,
				firstFailedAtMs = now,
				nextAttemptMs = now + BACKGROUND_SYNC_INTERVAL_MS,
				attemptCount = 0,
				isRelay = wasRelay,
				autoSync = autoSync
			)
			log("Background mode: closing idle outbound connection to $addr (next sync in ${BACKGROUND_SYNC_INTERVAL_MS / 60000}m)")
			node.close()
		}
	}

	fun retryPendingConnectionsImmediately() {
		val now = System.currentTimeMillis()
		outboundTargets.forEach { (addr, autoSync) ->
			val existing = connectionMap[addr]
			if (existing == null || existing.isClosed()) {
				pendingReconnects.compute(addr) { _, target ->
					(target ?: ReconnectTarget(
						addr = addr,
						firstFailedAtMs = now,
						nextAttemptMs = now,
						attemptCount = 0,
						autoSync = autoSync
					)).apply {
						nextAttemptMs = now
						attemptCount = 0
					}
				}
			}
		}
		pendingReconnects.values.forEach { it.nextAttemptMs = now }
		wakeReconnectManager()
	}

	private fun processPendingReconnects() {
		if (pendingReconnects.isEmpty()) return
		val now = System.currentTimeMillis()

		val toAttempt = mutableListOf<ReconnectTarget>()
		val iterator = pendingReconnects.entries.iterator()
		while (iterator.hasNext()) {
			val entry = iterator.next()
			val target = entry.value
			if (!outboundTargets.containsKey(target.addr) || explicitlyDisconnected.contains(target.addr)) {
				iterator.remove()
				continue
			}
			if (target.nextAttemptMs <= now) {
				toAttempt.add(target)
			}
		}

		for (target in toAttempt) {
			val existing = connectionMap[target.addr]
			if (existing != null && !existing.isClosed()) {
				pendingReconnects.remove(target.addr)
				continue
			}
			target.attemptCount++
			val backoffMs = if (backgroundMode) {
				when (target.attemptCount) {
					1 -> 10_000L
					else -> BACKGROUND_SYNC_INTERVAL_MS
				}
			} else {
				when (target.attemptCount) {
					1 -> 5_000L
					2 -> 10_000L
					3 -> 20_000L
					4 -> 30_000L
					else -> 60_000L
				}
			}
			target.nextAttemptMs = now + backoffMs
			async {
				try {
					debug("Attempting auto-reconnect to ${target.addr} (attempt ${target.attemptCount}, background=$backgroundMode)...")
					getTCPNode(target.addr, target.autoSync)
					pendingReconnects.remove(target.addr)
					log("Auto-reconnect succeeded for ${target.addr}")
				} catch (e: Exception) {
					debug("Auto-reconnect failed for ${target.addr}: ${e.message}")
					wakeReconnectManager()
				}
			}
		}
	}

	private fun pingAndCleanup() {
		val now = System.currentTimeMillis()
		if (!backgroundMode) {
			val pingHeader = StringWritable(PingMessage.type).serialize()
			val outboundNodes = synchronized(connectionMap) { connectionMap.values.toList() }
			outboundNodes.forEach { node ->
				if (!node.isClosed()) {
					if (now - node.lastActivityMs > PING_TIMEOUT_MS) {
						if (node.isTransferring()) {
							debug("Outbound node ${node.remoteAddress} silent on read for ${(now - node.lastActivityMs) / 1000}s but actively transferring — sending ping probe")
							try {
								node.sendPriority(pingHeader)
							} catch (_: Exception) {}
						} else {
							logNodeError(node, "Outbound connection silent for ${(now - node.lastActivityMs) / 1000}s, closing")
							node.close()
						}
					} else {
						try {
							node.sendPriority(pingHeader)
						} catch (_: Exception) {
						}
					}
				}
			}
		}
		val closedOutbound = synchronized(connectionMap) {
			connectionMap.values.filter { it.isClosed() }
		}
		closedOutbound.forEach { removeDeadNode(it) }
		val closedInbound = tcpServer?.nodeList?.filter { it.isClosed() }.orEmpty()
		closedInbound.forEach { removeDeadNode(it) }
		val closedBridged = bridgeManager.virtualNodes().filter { it.isClosed() }
		closedBridged.forEach { removeDeadNode(it) }
		cleanTempFiles(maxAgeMs = 30 * 60 * 1000L)
	}

	fun cleanTempFiles(maxAgeMs: Long = 0L): Int {
		val now = System.currentTimeMillis()
		val files = tmpDir.listFiles() ?: return 0
		var count = 0
		var bytesFreed = 0L
		val partFileThreshold = 24 * 60 * 60 * 1000L // 24 hours retention for .part files
		for (file in files) {
			try {
				val lastMod = file.lastModified()
				val isPart = file.name.endsWith(".part")
				val shouldDelete = if (isPart) {
					now - lastMod > partFileThreshold
				} else {
					maxAgeMs <= 0L || (now - lastMod > maxAgeMs)
				}
				if (shouldDelete) {
					val len = if (file.isDirectory) file.walkTopDown().sumOf { it.length() } else file.length()
					if (file.deleteRecursively()) {
						count++
						bytesFreed += len
					}
				}
			} catch (e: Exception) {
				log("Failed to delete temp file ${file.name}: ${e.message}")
			}
		}
		if (count > 0) {
			log("Cleaned up $count stale temp file(s) ($bytesFreed bytes) from $tmpDir")
		}
		activeStreamVerifiers.entries.removeIf { (k, v) ->
			if (!inFlightNodeRequests.containsKey(k)) {
				v.close()
				true
			} else false
		}
		return count
	}
}
