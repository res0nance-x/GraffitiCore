package r3.graffiti

import r3.content.BinaryContent
import r3.content.Content
import r3.content.ContentMeta
import r3.encryption.EncryptedContinuousOutputStream
import r3.hash.hash256
import r3.http.resolveFile
import r3.io.*
import r3.key.hash256
import r3.math.EncryptedSequence
import r3.net.discover.*
import r3.net.tcp.BridgeTCPNode
import r3.net.tcp.TCPBlock
import r3.net.tcp.TCPBlockHandler
import r3.net.tcp.TCPInputStream
import r3.net.tcp.TCPNode
import r3.net.tcp.TCPPack
import r3.net.tcp.TCPPackClient
import r3.net.tcp.TCPServer
import r3.pack.BinaryPack
import r3.pack.DirPack
import r3.pke.*
import r3.source.FileSink
import r3.source.FileSource
import r3.source.ListWritable
import r3.source.Source
import r3.source.StringWritable
import r3.thread.async
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

// Filtering & Verification:
// - Non-relay: rejects messages not addressed to local identities or known peers.
// - Whitelist mode: rejects messages from unknown authors.
// - Relay mode: verifies author signature; recipient additionally verifies content hash.
// - Deleted & Ignored: drops deleted keys and filters out ignored identities/content.

// Liveness & Connection Control (Client-Focused):
// - Outbound (client-initiated) connections are responsible for keepalive pings and reconnects.
// - Inbound (server-accepted) connections are passive: the server responds to PingMessage with
//   PongMessage, does not send pings or idle-timeout inbound connections, and removes dead
//   inbound connections when EOF is reached or when forwarding/sending fails.
// - In foreground/active mode, outbound connections send PingMessage every PING_INTERVAL_MS (60s)
//   and close/reconnect after PING_TIMEOUT_MS (180s) of silence.
// - In background mode (mobile battery saver when screen is off, unplugged, and not running as
//   a server/relay), idle outbound connections are closed after sync completes and periodically
//   reconnected every BACKGROUND_SYNC_INTERVAL_MS (15m), or immediately on foreground resume.
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
	val metaIndexFile = File(graffitiDir, "meta.idx")
	val metaIndexBakFile = File(graffitiDir, "meta.idx.bak")
	val deletedIndexFile = File(graffitiDir, "deleted.idx")
	val ignoredIndexFile = File(graffitiDir, "ignored.idx")
	private val legacyMetaDir = File(graffitiDir, "meta")
	private val legacyDeletedDir = File(graffitiDir, "deleted")
	private val legacyIgnoredIdentitiesDir = File(graffitiDir, "ignored_identities")
	private val legacyIgnoredContentDir = File(graffitiDir, "ignored_content")
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
		const val MAGIC_META_INDEX = 0x47524D49 // "GRMI"
		const val MAGIC_DELETED_INDEX = 0x47524449 // "GRDI"
		const val MAGIC_IGNORED_INDEX = 0x47524949 // "GRII"
		const val INDEX_VERSION: Short = 1
		const val MAX_META_ENTRY_BYTES = 10 * 1024 * 1024 // 10 MB
		const val META_INDEX_COMPACT_THRESHOLD = 100
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
	private val metaIndexLock = Any()
	private val deletedIndexLock = Any()
	private val ignoredIndexLock = Any()
	private val uncompactedDeletes = AtomicInteger(0)
	private val activeStreamVerifiers = ConcurrentHashMap<EncryptedMetaKey, StreamingHashVerifier>()
	private val contentSenderExecutor = Executors.newCachedThreadPool { r ->
		Thread(r, "Graffiti-ContentSender").apply { isDaemon = true }
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
	// A dedicated, persistent identity used solely for peer-connection
	// Stored as graffitiDir/serverIdentity; auto-created on first run.
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

	// ── Storage Size ───────────────────────────────────────────────
	val totalContentSize = AtomicLong(0L)
	private var defaultQuota = 0L

	@Volatile
	private var storageQuotaBytes: Long = defaultQuota
	fun getQuotaBytes(): Long = storageQuotaBytes
	fun setQuotaBytes(bytes: Long) {
		storageQuotaBytes = bytes
		try {
			val props = Properties()
			props.setProperty("quotaBytes", bytes.toString())
			val quotaFile = File(graffitiDir, "quota.properties")
			quotaFile.outputStream().use { props.store(it, "Storage Quota Setting") }
			log("Storage quota set to $bytes bytes")
			checkAndEnforceQuota()
		} catch (e: Exception) {
			log("Failed to save quota: ${e.message}")
		}
	}

	private fun loadQuota() {
		val quotaFile = File(graffitiDir, "quota.properties")
		if (quotaFile.exists()) {
			try {
				val props = Properties()
				quotaFile.inputStream().use { props.load(it) }
				storageQuotaBytes = props.getProperty("quotaBytes", defaultQuota.toString()).toLongOrNull() ?: defaultQuota
				log("Loaded storage quota: $storageQuotaBytes bytes")
			} catch (e: Exception) {
				log("Failed to load quota: ${e.message}")
			}
		}
	}

	private fun replaceFileAtomically(tempFile: File, destFile: File) {
		try {
			Files.move(
				tempFile.toPath(),
				destFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			)
		} catch (_: Exception) {
			try {
				Files.move(
					tempFile.toPath(),
					destFile.toPath(),
					StandardCopyOption.REPLACE_EXISTING
				)
			} catch (_: Exception) {
				tempFile.copyTo(destFile, overwrite = true, bufferSize = DEFAULT_COPY_BUFFER_SIZE)
				tempFile.delete()
			}
		}
	}

	private fun loadDeletedIndex(): Boolean = synchronized(deletedIndexLock) {
		if (!deletedIndexFile.exists()) return false
		return runCatching {
			deletedIndexFile.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_DELETED_INDEX) throw IOException("Invalid deleted.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported deleted.idx version: $version")
					val keyBuf = ByteArray(32)
					try {
						while (true) {
							val first = dis.read()
							if (first < 0) break
							keyBuf[0] = first.toByte()
							dis.readFully(keyBuf, 1, 31)
							deletedCache.add(EncryptedMetaKey(keyBuf.copyOf()))
						}
					} catch (_: EOFException) {
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load deleted.idx, rebuilding from directory: ${it.message}")
		}.getOrDefault(false)
	}

	fun saveDeletedIndex() {
		synchronized(deletedIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "deleted.idx.tmp")
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_DELETED_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						for (key in deletedCache) {
							dos.write(key.arr)
						}
					}
				}
				replaceFileAtomically(tempFile, deletedIndexFile)
			}.onFailure {
				log("Failed to save deleted.idx: ${it.message}")
			}
		}
	}

	private fun appendDeletedIndex(key: EncryptedMetaKey) {
		synchronized(deletedIndexLock) {
			if (!deletedIndexFile.exists()) {
				saveDeletedIndex()
				return
			}
			runCatching {
				FileOutputStream(deletedIndexFile, true).use { fos ->
					fos.write(key.arr)
				}
			}.onFailure {
				log("Failed to append to deleted.idx: ${it.message}")
			}
		}
	}

	private fun loadIgnoredIndex(): Boolean = synchronized(ignoredIndexLock) {
		if (!ignoredIndexFile.exists()) return false
		return runCatching {
			ignoredIndexFile.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_IGNORED_INDEX) throw IOException("Invalid ignored.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported ignored.idx version: $version")
					val keyBuf = ByteArray(32)
					val idCount = dis.readInt()
					for (i in 0 until idCount) {
						dis.readFully(keyBuf)
						ignoredIdentitiesCache.add(IdentityKey(keyBuf.copyOf()))
					}
					val contentCount = dis.readInt()
					for (i in 0 until contentCount) {
						dis.readFully(keyBuf)
						ignoredContentCache.add(EncryptedMetaKey(keyBuf.copyOf()))
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load ignored.idx: ${it.message}")
		}.getOrDefault(false)
	}

	fun saveIgnoredIndex() {
		synchronized(ignoredIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "ignored.idx.tmp")
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_IGNORED_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						val idList = ignoredIdentitiesCache.toList()
						dos.writeInt(idList.size)
						for (idKey in idList) {
							dos.write(idKey.arr)
						}
						val contentList = ignoredContentCache.toList()
						dos.writeInt(contentList.size)
						for (cKey in contentList) {
							dos.write(cKey.arr)
						}
					}
				}
				replaceFileAtomically(tempFile, ignoredIndexFile)
			}.onFailure {
				log("Failed to save ignored.idx: ${it.message}")
			}
		}
	}

	private fun readMetaIndexStream(file: File): Boolean {
		if (!file.exists()) return false
		var staleCount = 0
		val ok = runCatching {
			file.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_META_INDEX) throw IOException("Invalid meta.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported meta.idx version: $version")
					try {
						while (true) {
							val arrivalTime = try {
								dis.readLong()
							} catch (_: EOFException) {
								break
							}
							val dataLen = dis.readInt()
							if (dataLen !in 1..MAX_META_ENTRY_BYTES) {
								throw IOException("Invalid meta entry length in meta.idx: $dataLen")
							}
							val entryBytes = ByteArray(dataLen)
							dis.readFully(entryBytes)
							runCatching {
								val eMeta = entryBytes.toDataInputStream().use { EncryptedContentMetaData.read(it) }
								if (deletedCache.contains(eMeta.key)) {
									staleCount++
								} else {
									if (metaCache.put(eMeta.key, eMeta) != null) {
										staleCount++
									}
									metaTimeCache[eMeta.key] = arrivalTime
								}
							}
						}
					} catch (_: EOFException) {
						staleCount++
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load meta index from ${file.name}: ${it.message}")
		}.getOrDefault(false)

		if (ok && staleCount >= META_INDEX_COMPACT_THRESHOLD) {
			saveMetaIndex()
		} else if (ok && staleCount > 0) {
			uncompactedDeletes.set(staleCount)
		}
		return ok
	}

	private fun loadMetaIndex(): Boolean = synchronized(metaIndexLock) {
		if (readMetaIndexStream(metaIndexFile)) return true
		if (readMetaIndexStream(metaIndexBakFile)) {
			log("Recovered metadata from meta.idx.bak")
			saveMetaIndex()
			return true
		}
		return false
	}

	fun saveMetaIndex() {
		synchronized(metaIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "meta.idx.tmp")
				val sortedEntries = metaCache.entries
					.filter { !deletedCache.contains(it.key) }
					.sortedBy { metaTimeCache[it.key] ?: 0L }
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_META_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						for ((key, eMeta) in sortedEntries) {
							val ts = metaTimeCache[key] ?: System.currentTimeMillis()
							val bytes = eMeta.serialize()
							dos.writeLong(ts)
							dos.writeInt(bytes.size)
							dos.write(bytes)
						}
					}
				}
				if (metaIndexFile.exists()) {
					runCatching {
						metaIndexFile.copyTo(metaIndexBakFile, overwrite = true)
					}
				}
				replaceFileAtomically(tempFile, metaIndexFile)
				uncompactedDeletes.set(0)
			}.onFailure {
				log("Failed to save meta.idx: ${it.message}")
			}
		}
	}

	private fun appendMetaIndex(eMeta: EncryptedContentMetaData, timestamp: Long, serializedBytes: ByteArray? = null) {
		synchronized(metaIndexLock) {
			if (!metaIndexFile.exists()) {
				saveMetaIndex()
				return
			}
			runCatching {
				val bytes = serializedBytes ?: eMeta.serialize()
				FileOutputStream(metaIndexFile, true).buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeLong(timestamp)
						dos.writeInt(bytes.size)
						dos.write(bytes)
					}
				}
			}.onFailure {
				log("Failed to append to meta.idx: ${it.message}")
			}
		}
	}

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
		// Clean up all old temporary files from previous runs
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
		// Populate identityCache from disk
		identityDir.listFiles { f -> f.isFile }?.forEach { file ->
			runCatching { file.readBytes().toDataInputStream().use { Identity.read(it) } }
				.getOrNull()?.let { identityCache[it.key] = it }
		}
		// Populate peerCache from disk
		peerDir.listFiles { f -> f.isFile }?.forEach { file ->
			runCatching { file.readBytes().toDataInputStream().use { Peer.read(it) } }
				.getOrNull()?.let { peerCache[it.key] = it }
		}
		// Populate deletedCache from index (fallback to legacyDeletedDir migration)
		if (!loadDeletedIndex()) {
			if (legacyDeletedDir.exists()) {
				legacyDeletedDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { deletedCache.add(EncryptedMetaKey(file.name)) }
				}
				saveDeletedIndex()
				legacyDeletedDir.deleteRecursively()
			}
		}
		// Populate ignored caches from index (fallback to legacy directories migration)
		if (!loadIgnoredIndex()) {
			var hasLegacyIgnored = false
			if (legacyIgnoredIdentitiesDir.exists()) {
				legacyIgnoredIdentitiesDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { ignoredIdentitiesCache.add(IdentityKey(file.name)) }
				}
				hasLegacyIgnored = true
				legacyIgnoredIdentitiesDir.deleteRecursively()
			}
			if (legacyIgnoredContentDir.exists()) {
				legacyIgnoredContentDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { ignoredContentCache.add(EncryptedMetaKey(file.name)) }
				}
				hasLegacyIgnored = true
				legacyIgnoredContentDir.deleteRecursively()
			}
			if (hasLegacyIgnored) {
				saveIgnoredIndex()
			}
		}
		// Populate metaCache and metaTimeCache from index (fallback to legacyMetaDir migration)
		if (!loadMetaIndex()) {
			metaCache.clear()
			metaTimeCache.clear()
			if (legacyMetaDir.exists()) {
				legacyMetaDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching {
						val eMeta = file.toDataInputStream().use { EncryptedContentMetaData.read(it) }
						if (!deletedCache.contains(eMeta.key)) {
							metaCache[eMeta.key] = eMeta
							metaTimeCache[eMeta.key] = file.lastModified()
						}
					}
				}
				saveMetaIndex()
				legacyMetaDir.deleteRecursively()
			}
		}
		// Populate smallContentCache (up to MAX_SMALL_CONTENT_ITEMS) and initialize totalContentSize
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
		loadQuota()
		// Initialize monotonic timestamp counter based on existing files or wall-clock
		val maxExistingTime = metaTimeCache.values.maxOrNull() ?: 0L
		val now = System.currentTimeMillis()
		val initialTs = if (maxExistingTime in (now + 1)..(now + 86_400_000L)) {
			maxExistingTime
		} else {
			now
		}
		lastAssignedTimestamp.set(initialTs)
	}

	val contentHandler: (TCPNode, ByteArray, File?) -> Unit =
		contentHandler@{ node: TCPNode, rawHead: ByteArray, file: File? ->
			try {
				val type = StringWritable.read(rawHead.toDataInputStream()).str
				when (type) {
					// Peer is asking for all metadata we hold that matches their recipient filter.
					// We respond with a QueryResponseMessage + list of matching EncryptedContentMetaData.
					QueryMessage.type -> {
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
					// Response to our earlier QueryMessage: a list of EncryptedContentMetaData from the peer.
					// We save any new meta addressed to us, then request the actual content for those keys.
					QueryResponseMessage.type -> {
						val resp = QueryResponseMessage.read(rawHead.toDataInputStream())
						val serverTime = resp.serverTime
						val peerLabel = nodePeerMap[node]?.key?.name ?: node.remoteAddress.toString()
						if (file != null) {
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
								// Relay check: If relay size threshold is exceeded, relay ignores content completely
								if (relayEnabled && relayMaxContentSizeBytes > 0L && msgHeader.contentLength > relayMaxContentSizeBytes) {
									debug("Relay ignoring header ${msgHeader.key.name} from ${node.remoteAddress}: size (${msgHeader.contentLength}) exceeds relay limit ($relayMaxContentSizeBytes)")
									return@forEach
								}
								// Non-relay mode: ignore meta not addressed to us or our direct friends
								// (Peers we have explicitly added)
								if (!relayEnabled &&
									msgHeader.recipient !in myIdentityKeys &&
									PeerKey(msgHeader.recipient) !in myPeerKeys
								) {
									debug("Ignoring off-recipient meta ${msgHeader.key.name} from ${node.remoteAddress}")
									return@forEach
								}
								// Whitelist mode: only accept messages from known peers
								if (whitelistEnabled && !relayEnabled && msgHeader.author !in myPeerKeys) {
									debug("Ignoring meta ${msgHeader.key.name} from ${node.remoteAddress}: author ${msgHeader.author.name} not in whitelist")
									return@forEach
								}
								val metaExists = metaCache.containsKey(msgHeader.key)
								val contentExists =
									smallContentCache.containsKey(msgHeader.key) || File(contentDir, "${msgHeader.key}").exists()
								if (metaExists && contentExists) return@forEach

								// If client already has meta, but content is large and hasn't been manually downloaded yet:
								if (metaExists && !contentExists) {
									if (!relayEnabled && autoDownloadThresholdBytes > 0L && msgHeader.contentLength > autoDownloadThresholdBytes) {
										debug("Large content ${msgHeader.key.name} (${msgHeader.contentLength} bytes) awaiting manual download")
										return@forEach
									}
								}

								// Preserve the relay's arrival order for incoming content
								queryOrderTimestamps.computeIfAbsent(msgHeader.key) { nextMonotonicTimestamp() }
								val now = System.currentTimeMillis()
								val pendingSince = pendingContentRequests[msgHeader.key]
								val isPending = pendingSince != null && (now - pendingSince) < 30_000L
								if (isPending) return@forEach
								val authorPeer = getPeerByKey(msgHeader.author)
								val isLargeForClient = !relayEnabled && autoDownloadThresholdBytes > 0L && msgHeader.contentLength > autoDownloadThresholdBytes
								if (authorPeer == null) {
									// We need this peer before we can verify and accept content
									pendingPeerContent.computeIfAbsent(msgHeader.author) { ConcurrentHashMap() }[msgHeader.key] =
										node
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

							// Watermark commit logic:
							// If no content or metadata was requested, all items up to serverTime are already present.
							if (wantContent.isEmpty() && wantPeers.isEmpty() && wantMeta.isEmpty()) {
								if (serverTime > 0L) {
									setPeerQueryTime(node, serverTime)
								}
							} else if (serverTime > 0L) {
								// Defer watermark commit until in-flight content/metadata for this node arrives
								pendingBatchWatermark[node] = maxOf(pendingBatchWatermark[node] ?: 0L, serverTime)
								val set = batchPendingKeys.computeIfAbsent(node) { ConcurrentHashMap.newKeySet() }
								set.addAll(wantContent)
								set.addAll(wantMeta)
							}
						}
					}
					// Peer wants the meta and content for a list of keys we hold.
					// We serve each key's EncryptedContentMessage (containing metadata and content) in turn.
					// Offloaded to a background executor so the TCP receive loop is never blocked during file transfer.
					ContentRequestMessage.type -> {
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
											if (validOffset > 0L) {
												val slice = smallBytes.copyOfRange(validOffset.toInt(), smallBytes.size)
												node.send(EncryptedContentMessage(eMeta, fileSize, validOffset).serialize(), slice)
											} else {
												node.send(EncryptedContentMessage(eMeta, fileSize, 0L).serialize(), smallBytes)
											}
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
					ContentNotFoundMessage.type -> {
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
					// Peer wants only metadata (no content body) for a list of keys
					MetaRequestMessage.type -> {
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
					// Incoming encrypted metadata (no content body attached)
					EncryptedMetaMessage.type -> {
						val msg = EncryptedMetaMessage.read(rawHead.toDataInputStream())
						val eMeta = msg.eMeta
						if (isMessageDeleted(eMeta.key)) {
							debug("Ignoring meta ${eMeta.key.name} from ${node.remoteAddress}: key is deleted")
						} else {
							val peer = getPeerByKey(eMeta.author)
							if (peer == null) {
								debug("Author peer ${eMeta.author.name} not found for meta ${eMeta.key.name}")
							} else if (!eMeta.verify(peer)) {
								logNodeError(node, "Invalid signature for meta ${eMeta.key.name}")
							} else {
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
									contentHolders.computeIfAbsent(eMeta.key) { ConcurrentHashMap.newKeySet() }.add(node)
									notifyMessageReceived(eMeta.key)
								} else {
									queryOrderTimestamps.remove(eMeta.key)
								}
							}
						}
					}
					// Incoming encrypted content file for a specific key.
					EncryptedContentMessage.type -> {
						val contentMessage = EncryptedContentMessage.read(rawHead.toDataInputStream())
						val eMeta = contentMessage.eMeta
						if (file != null) {
							if (isMessageDeleted(eMeta.key)) {
								file.delete()
								debug("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: key is deleted")
							} else if (eMeta.author.arr.contentEquals(eMeta.recipient.arr)) {
								file.delete()
								debug("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: author and recipient are the same")
							} else {
								val peer = getPeerByKey(eMeta.author)
								if (peer == null) {
									file.delete()
									logNodeError(
										node,
										"Author peer ${eMeta.author.name} not found for content ${eMeta.key.name}"
									)
									return@contentHandler
								}
								val identity = getIdentityByKey(eMeta.recipient)
								val verifier = activeStreamVerifiers.remove(eMeta.key)
								val valid = if (verifier != null) {
									try {
										val hashOk = verifier.finish()
										val sigOk = eMeta.verify(peer)
										if (hashOk && sigOk) {
											log("Streaming inline verification succeeded for ${eMeta.key.name}")
											true
										} else {
											logNodeError(
												node,
												"Streaming inline verification failed for ${eMeta.key.name}: hashOk=$hashOk, sigOk=$sigOk"
											)
											false
										}
									} finally {
										verifier.close()
									}
								} else if (identity != null) {
									// Fallback: verify metadata signature AND content hash from stored file
									eMeta.verify(peer, FileSource(file), identity)
								} else {
									// We only have the author peer (relaying): verify metadata signature
									eMeta.verify(peer)
								}
								if (!valid) {
									val corruptFile = File(tmpDir, "${eMeta.key}.corrupt")
									file.renameTo(corruptFile)
									logNodeError(node, "Invalid content: signature or hash mismatch for ${eMeta.key.name} (file size ${if (corruptFile.exists()) corruptFile.length() else file.length()} bytes)")
									if (activeDownloadAttempts[eMeta.key]?.currentNode == node) {
										tryNextNodeForDownload(eMeta.key)
									}
									return@contentHandler
								}
								var contentStored = false
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
									contentStored = true
								}
								if (contentStored) {
									logIncoming(node, eMeta.key.name, fileSize)
									activeDownloadAttempts.remove(eMeta.key)
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
									notifyMessageReceived(eMeta.key)
									if (relayEnabled) {
										pushNewMessage(eMeta.key, excludeNode = node)
									}
								}
							}
						}
					}
					// Keepalive ping — respond immediately with high-priority pong so the sender resets our activity timer.
					PingMessage.type -> node.sendPriority(StringWritable(PongMessage.type).serialize())
					// Keepalive pong — no action needed; TCPNode already refreshed lastActivityMs on block receipt.
					PongMessage.type -> { /* lastActivityMs already refreshed by TCPNode on block receipt */
					}
					// Peer is challenging us to prove our server identity.
					// We sign their nonce with our server identity key and send back the response.
					ChallengeMessage.type -> {
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
					// Peer's response to our challenge. Verify their signature against the nonce we sent;
					// if valid, record them as authenticated in nodePeerMap and fire onNodeIdentified.
					// If invalid, close the connection immediately.
					ChallengeResponseMessage.type -> {
						val msg = ChallengeResponseMessage.read(rawHead.toDataInputStream())
						val ourNonce = nodeChallengeMap.remove(node)   // atomic: nonce is consumed once
						if (ourNonce != null && msg.isValidFor(ourNonce)) {
							nodePeerMap[node] = msg.peer
							nodeRelayMap[node] = msg.isRelay
							nodeBridgeMap[node] = msg.isBridge
							if (node !is BridgeTCPNode) {
								getOrAssignBridgeVirtualPort(msg.peer.key)
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
										requestBridgePeerList(node)
									} catch (_: Exception) {}
								}
							}
						} else {
							logNodeError(node, "Auth failed")
							node.close()
						}
					}
					// Peer is asking for public keys for a list of author/peer keys.
					PeerRequestMessage.type -> {
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
					// Response to our earlier PeerRequestMessage: a list of Peer instances.
					PeerResponseMessage.type -> {
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
					// Portal directory listing request from a connected peer
					PortalListRequestMessage.type -> {
						val req = PortalListRequestMessage.read(rawHead.toDataInputStream())
						contentSenderExecutor.execute {
							try {
								val metaList = getLocalPortalMetaList()
								node.send(
									PortalListResponseMessage(req.requestId).serialize(),
									ListWritable(metaList).serialize()
								)
								debug("Served PortalListResponse (${metaList.size} items) to ${node.remoteAddress}")
							} catch (e: Exception) {
								logNodeError(node, "Error serving PortalListRequest: ${e.message}")
							}
						}
					}
					// Portal directory listing response from a remote peer
					PortalListResponseMessage.type -> {
						val resp = PortalListResponseMessage.read(rawHead.toDataInputStream())
						val list = if (file != null && file.exists()) {
							try {
								file.toDataInputStream().use { dis ->
									ListWritable.read(dis, ContentMeta::read).list
								}
							} catch (e: Exception) {
								logNodeError(node, "Failed to read PortalListResponse body: ${e.message}")
								emptyList()
							}
						} else {
							emptyList()
						}
						pendingPortalListRequests.remove(resp.requestId)?.second?.complete(list)
					}
					// Stateless Portal 64KB chunk request from a connected peer
					PortalChunkRequestMessage.type -> {
						val req = PortalChunkRequestMessage.read(rawHead.toDataInputStream())
						contentSenderExecutor.execute {
							try {
								val chunk = readLocalPortalChunk(req.path, req.pos, req.maxLength)
								if (chunk != null) {
									if (chunk.isNotEmpty()) {
										node.send(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_OK).serialize(), chunk)
									} else {
										node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_OK).serialize())
									}
								} else {
									node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_NOT_FOUND).serialize())
								}
							} catch (e: Exception) {
								logNodeError(node, "Error serving PortalChunkRequest (${req.path} @ ${req.pos}): ${e.message}")
								try {
									node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_ERROR).serialize())
								} catch (_: Exception) {}
							}
						}
					}
					// Stateless Portal chunk response from a remote peer
					PortalChunkResponseMessage.type -> {
						val resp = PortalChunkResponseMessage.read(rawHead.toDataInputStream())
						val data = if (resp.status == PortalChunkResponseMessage.STATUS_OK && file != null && file.exists()) {
							try {
								file.readBytes()
							} catch (_: Exception) {
								ByteArray(0)
							}
						} else {
							ByteArray(0)
						}
						pendingPortalChunkRequests.remove(resp.requestId)?.second?.complete(data)
					}
					// BridgeDiscovery: client asking this Bridge for its connected peers + virtual ports
					BridgeListRequestMessage.type -> {
						val req = BridgeListRequestMessage.read(rawHead.toDataInputStream())
						val entries = if (bridgeEnabled && node !is BridgeTCPNode) {
							directNodes
								.filter { it != node && !it.isClosed() }
								.mapNotNull { n ->
									val peer = nodePeerMap[n] ?: return@mapNotNull null
									val vPort = getOrAssignBridgeVirtualPort(peer.key)
									BridgePeerEntry(peer.key, vPort)
								}
						} else {
							emptyList()
						}
						try {
							node.sendPriority(BridgeListResponseMessage(req.requestId, entries).serialize())
							debug("Served BridgeListResponse (${entries.size} peers) to ${node.remoteAddress}")
						} catch (e: Exception) {
							logNodeError(node, "Error serving BridgeListRequest: ${e.message}")
						}
					}
					// BridgeDiscovery response from a connected Bridge
					BridgeListResponseMessage.type -> {
						val resp = BridgeListResponseMessage.read(rawHead.toDataInputStream())
						val bridgeHost = node.remoteAddress.address?.hostAddress ?: node.remoteAddress.hostString
						resp.entries.forEach { entry ->
							if (entry.virtualPort < 0 && entry.peerKey.arr.size == 32) {
								bridgeDirectory[Pair(bridgeHost, entry.virtualPort)] = Pair(node, entry.peerKey)
							}
						}
						pendingBridgeListRequests.remove(resp.requestId)?.second?.complete(resp.entries)
					}


					else -> debug("Unknown message type: $type from ${node.remoteAddress}")
				}
			} catch (e: Exception) {
				logNodeError(node, "Error in contentHandler: ${e.message}")
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
	private fun sendChallenge(node: TCPNode) {
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
		newNode.bridgeBlockInterceptor = ::handleBridgeRawBlock
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
			disconnect(InetSocketAddress(java.net.InetAddress.getByName(host), port))
		}
	}

	fun connectBridgedPeer(host: String, virtualPort: Int, autoSync: Boolean = true): BridgeTCPNode {
		require(virtualPort < 0) { "Virtual bridge port must be negative, got $virtualPort" }
		val resolvedIp = try {
			java.net.InetAddress.getByName(host.trim()).hostAddress ?: host.trim()
		} catch (_: Exception) {
			host.trim()
		}
		var mapping = bridgeDirectory[Pair(resolvedIp, virtualPort)] ?: bridgeDirectory[Pair(host.trim(), virtualPort)]
		if (mapping == null || mapping.first.isClosed()) {
			val candidateBridge = directNodes.firstOrNull { n ->
				!n.isClosed() && isNodeBridge(n) &&
					(n.remoteAddress.address?.hostAddress == resolvedIp || n.remoteAddress.hostString == host.trim())
			}
			if (candidateBridge != null) {
				requestBridgePeerList(candidateBridge)
				mapping = bridgeDirectory[Pair(resolvedIp, virtualPort)] ?: bridgeDirectory[Pair(host.trim(), virtualPort)]
			}
		}
		val (bridgeNode, targetPeerKey) = mapping
			?: error("Bridged peer at $host:$virtualPort is not available on any connected bridge")
		if (bridgeNode.isClosed()) {
			error("Bridge connection to $host is closed")
		}
		return getOrCreateBridgeNode(
			carrierNode = bridgeNode,
			remotePeerKey = targetPeerKey,
			virtualPort = virtualPort,
			inbound = false,
			autoSync = autoSync
		)
	}

	fun disconnectBridgedPeer(host: String, virtualPort: Int) {
		val resolvedIp = try {
			java.net.InetAddress.getByName(host.trim()).hostAddress ?: host.trim()
		} catch (_: Exception) {
			host.trim()
		}
		bridgeNodes.values.filter { vNode ->
			!vNode.isClosed() && vNode.remotePort == virtualPort &&
				(vNode.remoteAddress.address?.hostAddress == resolvedIp || vNode.remoteAddress.hostString == host.trim())
		}.forEach { it.close() }
	}

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
		val peerLabel = nodePeerMap[node]?.key?.name ?: node.remoteAddress.toString()
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
			// Notify all active bridged pairs that this bridge is shutting down bridging
			val snapshot = activeBridgePairs.entries.map { it.key to it.value.toSet() }
			activeBridgePairs.clear()
			for ((peerA, partners) in snapshot) {
				val nodeA = getDirectNodeForPeer(peerA) ?: continue
				val vPortA = peerToBridgeVirtualPort[peerA] ?: -1
				for (peerB in partners) {
					try {
						nodeA.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(peerB.arr, vPortA))
					} catch (_: Exception) {}
				}
			}
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
		val removedBridged = bridgeNodes.entries.removeIf { it.value == node }
		cleanupNodeState(node)
		if (removedInbound || removedOutbound || removedBridged) {
			checkAndNotifyTransferState()
			notifyNodeDisconnected(node)
		}
	}

	// Pushes a newly stored message's metadata to all currently connected peers.
	// Each peer will automatically request content if they want it.
	fun pushNewMessage(metaKey: EncryptedMetaKey, excludeNode: TCPNode? = null) {
		val eMeta = metaCache[metaKey] ?: return
		val contentExists = smallContentCache.containsKey(metaKey) || File(contentDir, "$metaKey").exists()
		if (!contentExists) return // Must have the content before we push/relay it!
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

	private fun findPeerFile(key: PeerKey): File? {
		// Fast path: filename is the base64 key string.
		val expected = File(peerDir, "$key")
		if (expected.exists()) return expected
		// Fallback: scan for legacy name-based peer files.
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

	fun isIdentityPersistent(key: IdentityKey): Boolean {
		return identityCache.containsKey(key)
	}

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

	fun listPeers(): List<Peer> {
		return peerCache.values.toList()
	}

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
				node.bridgeBlockInterceptor = ::handleBridgeRawBlock
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
			// Advertise the server identity key so that peers discovering us
			// see the stable server avatar/name, not an IP-hash key.
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
		val virtualNodes = bridgeNodes.values.toList()
		bridgeNodes.clear()
		closeAll(virtualNodes)
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

	fun getPeerByKey(key: PeerKey): Peer? =
		peerCache[key]

	fun getMetaByKey(key: EncryptedMetaKey): EncryptedContentMetaData? =
		metaCache[key]

	fun getMetaTimeByKey(key: EncryptedMetaKey): Long? =
		metaTimeCache[key]

	fun listAllMeta(): Collection<EncryptedContentMetaData> =
		metaCache.values

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
			r3.source.BinarySource(smallBytes)
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

	fun getMessagesForIdentity(identityKey: IdentityKey): List<EncryptedMetaKey> {
		return metaCache.values
			.filter { it.recipient == identityKey }
			.map { it.key }
	}

	fun exportMessages(keys: Collection<EncryptedMetaKey>, destinationFile: File): Int {
		val items = mutableListOf<Content>()
		val peerKeysAdded = mutableSetOf<PeerKey>()
		var count = 0

		for (key in keys) {
			val eMeta = metaCache[key] ?: continue
			val metaTime = metaTimeCache[key] ?: System.currentTimeMillis()
			val metaBytes = eMeta.serialize()

			// 1. Add meta entry
			items.add(object : Content, Source by r3.source.BinarySource(metaBytes) {
				override val path: String = "meta/$key"
				override val ext: String = "meta"
				override val length: Long = metaBytes.size.toLong()
				override val lastModified: Long = metaTime
			})

			// 2. Add content entry if present
			val contentFile = File(contentDir, "$key").consistentFile()
			if (contentFile.exists() && contentFile.length() > 0) {
				items.add(object : Content, Source by FileSource(contentFile) {
					override val path: String = "content/$key"
					override val ext: String = "content"
					override val length: Long = contentFile.length()
					override val lastModified: Long = contentFile.lastModified()
					override fun createInputStream(): InputStream = contentFile.inputStream()
				})
			} else {
				val smallBytes = smallContentCache[key]
				if (smallBytes != null && smallBytes.isNotEmpty()) {
					items.add(BinaryContent(smallBytes, path = "content/$key", ext = "content"))
				}
			}

			// 3. Add author peer public key if available
			if (peerKeysAdded.add(eMeta.author)) {
				val peer = getPeerByKey(eMeta.author)
					?: listIdentities().find { it.asPeer().key == eMeta.author }?.asPeer()
				if (peer != null) {
					items.add(BinaryContent(peer.serialize(), path = "peer/${eMeta.author}", ext = "peer"))
				}
			}
			count++
		}

		if (items.isEmpty()) return 0

		destinationFile.parentFile?.mkdirs()
		val sink = FileSink(destinationFile, append = false)
		BinaryPack.create(items, sink)
		return count
	}

	fun importPack(source: Source): ImportResult {
		val pack = BinaryPack.open(source)
		val entries = pack.toList()
		var importedPeers = 0
		var importedMessages = 0
		var skippedMessages = 0

		// 1. Import any peers bundled in the pack
		for (entry in entries) {
			if (entry.path.startsWith("peer/")) {
				try {
					val peer = entry.createInputStream().use { Peer.read(DataInputStream(it)) }
					if (getPeerByKey(peer.key) == null) {
						savePeer(peer)
						importedPeers++
					}
				} catch (e: Exception) {
					log("Failed to import peer entry ${entry.path}: ${e.message}")
				}
			}
		}

		// 2. Index meta and content entries
		val metaEntries = mutableMapOf<EncryptedMetaKey, Content>()
		val contentEntries = mutableMapOf<EncryptedMetaKey, Content>()

		for (entry in entries) {
			if (entry.path.startsWith("meta/")) {
				val keyStr = entry.path.removePrefix("meta/")
				try {
					val key = EncryptedMetaKey(keyStr)
					metaEntries[key] = entry
				} catch (_: Exception) {}
			} else if (entry.path.startsWith("content/")) {
				val keyStr = entry.path.removePrefix("content/")
				try {
					val key = EncryptedMetaKey(keyStr)
					contentEntries[key] = entry
				} catch (_: Exception) {}
			}
		}

		val newlyImportedKeys = mutableListOf<EncryptedMetaKey>()

		for ((key, metaEntry) in metaEntries) {
			if (isMessageDeleted(key)) {
				skippedMessages++
				continue
			}

			val contentFile = File(contentDir, "$key").consistentFile()
			val contentEntry = contentEntries[key]

			val alreadyHasMeta = metaCache.containsKey(key)
			val alreadyHasContent = hasContent(key)

			if (alreadyHasMeta && (alreadyHasContent || contentEntry == null)) {
				skippedMessages++
				continue
			}

			var metaSaved = false
			if (!alreadyHasMeta) {
				try {
					val metaBytes = metaEntry.createInputStream().use { it.readBytes() }
					val eMeta = DataInputStream(ByteArrayInputStream(metaBytes)).use { EncryptedContentMetaData.read(it) }
					if (eMeta.key != key) {
						log("Key mismatch for portable meta: expected $key but got ${eMeta.key}")
						skippedMessages++
						continue
					}
					val originalTime = metaEntry.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()

					metaCache[key] = eMeta
					metaTimeCache[key] = originalTime
					appendMetaIndex(eMeta, originalTime, metaBytes)
					metaSaved = true
				} catch (e: Exception) {
					log("Failed to write portable meta for $key: ${e.message}")
					skippedMessages++
					continue
				}
			}

			var contentSaved = false
			if (contentEntry != null && !alreadyHasContent) {
				try {
					contentFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { out ->
						contentEntry.createInputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { inp ->
							inp.copyTo(out, DEFAULT_COPY_BUFFER_SIZE)
						}
					}
					val sz = contentFile.length()
					totalContentSize.addAndGet(sz)
					if (sz <= SMALL_CONTENT_THRESHOLD_BYTES && smallContentCache.size < MAX_SMALL_CONTENT_ITEMS) {
						smallContentCache[key] = contentFile.readBytes()
					}
					contentSaved = true
				} catch (e: Exception) {
					log("Failed to write portable content for $key: ${e.message}")
				}
			}

			if (metaSaved || contentSaved) {
				importedMessages++
				newlyImportedKeys.add(key)
			}
		}

		if (newlyImportedKeys.isNotEmpty()) {
			triggerMetaWarmup(newlyImportedKeys)
			for (k in newlyImportedKeys) {
				pushNewMessage(k)
			}
			checkAndEnforceQuota()
		}

		return ImportResult(importedMessages, skippedMessages, importedPeers)
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

	// Session-only (ephemeral) identities — held in RAM, never written to disk.
	// They vanish when the application exits.
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

	/** Fired when a greeting is received and the peer's signature is verified. */
	private val onNodeIdentifiedListeners = mutableListOf<(node: TCPNode, peer: Peer) -> Unit>()

	/** Fired when a peer public key is verified and saved to peerCache. */
	private val onPeerReceivedListeners = mutableListOf<(peer: Peer) -> Unit>()

	/** Fired when a new encrypted meta file is successfully stored from a peer. */
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

	private fun notifyNodeConnected(node: TCPNode, inbound: Boolean) {
		for (listener in onNodeConnectedListeners) {
			try {
				listener(node, inbound)
			} catch (e: Exception) {
				log(e)
			}
		}
	}

	private fun notifyNodeDisconnected(node: TCPNode) {
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
	private fun checkAndNotifyTransferState() {
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

	/** Tracks metadata keys waiting for an author peer to be resolved: author -> (metaKey -> node). */
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

	/** Maps each live TCPNode to the verified Peer from its challenge-response. */
	private val nodePeerMap = ConcurrentHashMap<TCPNode, Peer>()

	fun getNodeForPeer(peerKey: PeerKey): TCPNode? {
		return nodePeerMap.entries.firstOrNull { it.value.key == peerKey && !it.key.isClosed() }?.key
	}

	fun isConnectedToPeer(peerKey: PeerKey): Boolean = getNodeForPeer(peerKey) != null

	private class ContentDownloadAttempt(
		val key: EncryptedMetaKey,
		val remainingNodes: java.util.concurrent.ConcurrentLinkedQueue<TCPNode>,
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
			remainingNodes = java.util.concurrent.ConcurrentLinkedQueue(candidates),
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

	/** In-memory map of peer key to last query server time received. */
	private val peerQueryTimeMap = ConcurrentHashMap<PeerKey, Long>()

	/** Fallback map of remote address to last query server time for unauthenticated nodes. */
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

	/** Tracks in-flight content requests by key and request timestamp to prevent duplicate downloads. */
	private val pendingContentRequests = ConcurrentHashMap<EncryptedMetaKey, Long>()

	/** Maps in-flight content/metadata download keys to the TCPNode currently serving them. */
	private val inFlightNodeRequests = ConcurrentHashMap<EncryptedMetaKey, TCPNode>()

	/** Tracks candidate watermarks (serverTime) received in QueryResponses with in-flight items. */
	private val pendingBatchWatermark = ConcurrentHashMap<TCPNode, Long>()

	/** Tracks the set of keys currently in flight for a given node from a QueryResponse. */
	private val batchPendingKeys = ConcurrentHashMap<TCPNode, MutableSet<EncryptedMetaKey>>()

	private fun cleanupNodeState(node: TCPNode) {
		val disconnectedPeerKey = nodePeerMap.remove(node)?.key
		nodeRelayMap.remove(node)
		nodeBridgeMap.remove(node)
		nodeChallengeMap.remove(node)
		nodeQueryMap.remove(node)
		nodesWithoutAutoSync.remove(node)
		if (node !is BridgeTCPNode) {
			nodeQueryTimeMap.remove(node.remoteAddress)
		}

		if (node !is BridgeTCPNode) {
			// 1. If we are a client and a Bridge carrier connection closed, close all virtual BridgeTCPNodes riding on it
			val deadVirtualNodes = bridgeNodes.entries
				.filter { it.key.first == node }
				.map { it.value }
			deadVirtualNodes.forEach { vNode ->
				vNode.closeFromRemote()
			}
			bridgeDirectory.entries.removeIf { it.value.first == node }

			// 2. If we are a Bridge and a direct client disconnected, notify any peers actively bridged to it
			if (disconnectedPeerKey != null) {
				val partners = activeBridgePairs.remove(disconnectedPeerKey).orEmpty()
				val vPort = peerToBridgeVirtualPort[disconnectedPeerKey] ?: -1
				for (partnerKey in partners) {
					activeBridgePairs[partnerKey]?.remove(disconnectedPeerKey)
					val partnerNode = getDirectNodeForPeer(partnerKey)
					if (partnerNode != null && !partnerNode.isClosed()) {
						try {
							partnerNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(disconnectedPeerKey.arr, vPort))
						} catch (_: Exception) {}
					}
				}
			}
		}

		pendingBridgeListRequests.entries.removeIf { (_, pair) ->
			if (pair.first == node) {
				pair.second.complete(emptyList())
				true
			} else false
		}

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
		pendingPortalListRequests.entries.removeIf { (_, pair) ->
			if (pair.first == node) {
				pair.second.complete(emptyList())
				true
			} else false
		}
		pendingPortalChunkRequests.entries.removeIf { (_, pair) ->
			if (pair.first == node) {
				pair.second.complete(ByteArray(0))
				true
			} else false
		}
	}

	// ── Portal (Stateless TCPPack Server & Client) ────────────────────────────
	private val portalRequestCounter = AtomicLong(1L)
	private val pendingPortalListRequests =
		ConcurrentHashMap<Long, Pair<TCPNode, CompletableFuture<List<ContentMeta>>>>()
	private val pendingPortalChunkRequests =
		ConcurrentHashMap<Long, Pair<TCPNode, CompletableFuture<ByteArray>>>()

	private fun generateDefaultPortalHtml(): ByteArray {
		val nodeName = serverIdentity.key.name
		val html = """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>$nodeName — Portal</title>
				<style>
					:root {
						color-scheme: dark light;
					}
					body {
						margin: 0;
						min-height: 100vh;
						display: flex;
						align-items: center;
						justify-content: center;
						font-family: system-ui, -apple-system, sans-serif;
						background: #121614;
						color: #e2e8e4;
						padding: 1.5rem;
						box-sizing: border-box;
					}
					.card {
						max-width: 520px;
						width: 100%;
						background: rgba(255, 255, 255, 0.04);
						border: 1px solid rgba(255, 255, 255, 0.12);
						border-radius: 12px;
						padding: 2rem;
						text-align: center;
						box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
					}
					.icon {
						font-size: 3rem;
						margin-bottom: 0.75rem;
					}
					h1 {
						margin: 0 0 0.5rem;
						font-size: 1.4rem;
					}
					p {
						margin: 0.5rem 0;
						line-height: 1.5;
						opacity: 0.82;
						font-size: 0.95rem;
					}
					.hint {
						margin-top: 1.25rem;
						padding: 0.75rem 1rem;
						background: rgba(0, 0, 0, 0.25);
						border-radius: 8px;
						font-size: 0.82rem;
						opacity: 0.7;
					}
				</style>
			</head>
			<body>
				<div class="card">
					<div class="icon">🌐</div>
					<h1>$nodeName's Portal</h1>
					<p>This node has not customized their portal yet.</p>
					<div class="hint">
						To customize your portal, add media files or an <code>index.html</code> web app to your node's Portal folder.
					</div>
				</div>
			</body>
			</html>
		""".trimIndent()
		return html.toByteArray(Charsets.UTF_8)
	}

	private fun isPortalFileAllowed(file: File): Boolean {
		var cur: File? = file
		val root = portalDir.canonicalFile
		while (cur != null && cur.canonicalFile != root) {
			if (cur.name.startsWith(".")) return false
			cur = cur.parentFile
		}
		return file.isFile && !file.name.startsWith(".")
	}

	fun getLocalPortalMetaList(): List<ContentMeta> {
		if (!portalDir.exists()) {
			portalDir.mkdirs()
		}
		val files = portalDir.walkTopDown()
			.onEnter { dir -> dir == portalDir || !dir.name.startsWith(".") }
			.filter { it.isFile && !it.name.startsWith(".") }
			.toList()
		if (files.isEmpty()) {
			val defaultBytes = generateDefaultPortalHtml()
			return listOf(ContentMeta("index.html", "html", defaultBytes.size.toLong(), System.currentTimeMillis()))
		}
		val rootPath = portalDir.consistentPath()
		return files.map { f ->
			val relPath = makeRelativePath(portalDir, f).let {
				if (f.consistentPath().startsWith(rootPath)) f.consistentPath().substring(rootPath.length) else it
			}
			val ext = getExtension(f.name)
			ContentMeta(relPath, ext, f.length(), f.lastModified())
		}.sortedBy { it.name }
	}

	fun readLocalPortalChunk(path: String, pos: Long, maxLength: Int): ByteArray? {
		if (pos < 0L || maxLength <= 0) return ByteArray(0)
		val cappedMax = maxLength.coerceIn(1, TCPInputStream.DEFAULT_CHUNK_SIZE)
		if (!portalDir.exists()) {
			portalDir.mkdirs()
		}
		val cleanRel = path.trimStart('/', '\\')
		val targetFile = try {
			resolveFile(portalDir, cleanRel)
		} catch (_: Exception) {
			return null
		}
		if (targetFile.exists() && isPortalFileAllowed(targetFile)) {
			val fileLen = targetFile.length()
			if (pos >= fileLen) return ByteArray(0)
			val toRead = minOf(cappedMax.toLong(), fileLen - pos).toInt()
			val buf = ByteArray(toRead)
			RandomAccessFile(targetFile, "r").use { raf ->
				raf.seek(pos)
				var totalRead = 0
				while (totalRead < toRead) {
					val r = raf.read(buf, totalRead, toRead - totalRead)
					if (r <= 0) break
					totalRead += r
				}
				return if (totalRead == toRead) buf else buf.copyOf(totalRead)
			}
		}
		// Fallback to default portal HTML if portalDir has no user files and index.html is requested
		val hasAnyFiles = portalDir.walkTopDown()
			.onEnter { dir -> dir == portalDir || !dir.name.startsWith(".") }
			.any { it.isFile && !it.name.startsWith(".") }
		if (!hasAnyFiles && (cleanRel.equals("index.html", ignoreCase = true) || cleanRel.isEmpty())) {
			val defaultBytes = generateDefaultPortalHtml()
			if (pos >= defaultBytes.size) return ByteArray(0)
			val end = minOf(defaultBytes.size, (pos + cappedMax).toInt())
			return defaultBytes.copyOfRange(pos.toInt(), end)
		}
		return null
	}

	fun findConnectedNode(host: String, port: Int): TCPNode? {
		val normalizedHost = host.trim()
		if (port < 0) {
			return bridgeNodes.values.firstOrNull { node ->
				!node.isClosed() && node.remotePort == port &&
					(node.remoteAddress.address?.hostAddress == normalizedHost || node.remoteAddress.hostString == normalizedHost)
			}
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

	fun createPortalPack(node: TCPNode? = null): TCPPack {
		if (node == null) {
			// Local self-portal preview using the exact same TCPPack + TCPInputStream pipeline
			val selfClient = object : TCPPackClient {
				override fun listContent(): List<ContentMeta> = getLocalPortalMetaList()
				override fun readChunk(path: String, pos: Long, maxLength: Int): ByteArray =
					readLocalPortalChunk(path, pos, maxLength) ?: ByteArray(0)
			}
			return TCPPack(selfClient)
		}
		val remoteClient = object : TCPPackClient {
			override fun listContent(): List<ContentMeta> {
				if (node.isClosed()) return emptyList()
				val reqId = portalRequestCounter.getAndIncrement()
				val future = CompletableFuture<List<ContentMeta>>()
				pendingPortalListRequests[reqId] = Pair(node, future)
				return try {
					node.sendPriority(PortalListRequestMessage(reqId).serialize())
					future.get(15, TimeUnit.SECONDS)
				} catch (e: Exception) {
					logNodeError(node, "Portal listContent timed out or failed: ${e.message}")
					emptyList()
				} finally {
					pendingPortalListRequests.remove(reqId)
				}
			}

			override fun readChunk(path: String, pos: Long, maxLength: Int): ByteArray {
				if (node.isClosed()) return ByteArray(0)
				val reqId = portalRequestCounter.getAndIncrement()
				val future = CompletableFuture<ByteArray>()
				pendingPortalChunkRequests[reqId] = Pair(node, future)
				return try {
					node.sendPriority(
						PortalChunkRequestMessage(
							requestId = reqId,
							path = path,
							pos = pos,
							maxLength = maxLength.coerceIn(1, TCPInputStream.DEFAULT_CHUNK_SIZE)
						).serialize()
					)
					future.get(15, TimeUnit.SECONDS)
				} catch (e: Exception) {
					logNodeError(node, "Portal readChunk ($path @ $pos) timed out or failed: ${e.message}")
					ByteArray(0)
				} finally {
					pendingPortalChunkRequests.remove(reqId)
				}
			}
		}
		return TCPPack(remoteClient)
	}

	/**
	 * Scans local metadata to find any items that lack corresponding content files
	 * (and are within auto-download threshold / not ignored/deleted) and requests them from [node].
	 */
	fun reconcileMissingContent(node: TCPNode) {
		if (node.isClosed()) return
		val missingContentKeys = mutableListOf<EncryptedMetaKey>()
		val missingPeerKeys = mutableSetOf<PeerKey>()
		val now = System.currentTimeMillis()

		metaCache.values.forEach { eMeta ->
			val key = eMeta.key
			if (isMessageDeleted(key) || isContentIgnored(key)) return@forEach
			if (hasContent(key)) return@forEach

			// If client mode and content size exceeds autoDownloadThresholdBytes, wait for manual download
			val contentLength = getKnownContentSize(key)
			if (!relayEnabled && autoDownloadThresholdBytes > 0L && (contentLength > autoDownloadThresholdBytes || contentLength == 0L)) {
				return@forEach
			}

			// Check if author peer is known
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

	private fun createFileStreamForHeader(node: TCPNode, header: ByteArray): r3.net.tcp.TCPFileStream {
		try {
			if (header.isNotEmpty()) {
				val dis = DataInputStream(ByteArrayInputStream(header))
				val type = StringWritable.read(dis).str
				if (type == EncryptedContentMessage.type) {
					val contentLength = dis.readLong()
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
						return r3.net.tcp.TCPFileStream(partFile, append = true, deleteOnClose = false)
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

						return r3.net.tcp.TCPFileStream(
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
		return r3.net.tcp.TCPFileStream(f, append = false, deleteOnClose = true)
	}
	private val nodeRelayMap = ConcurrentHashMap<TCPNode, Boolean>()
	fun isNodeRelay(node: TCPNode): Boolean = nodeRelayMap[node] ?: false

	private val nodeBridgeMap = ConcurrentHashMap<TCPNode, Boolean>()
	fun isNodeBridge(node: TCPNode): Boolean = nodeBridgeMap[node] ?: false

	// ── Bridge Virtual Nodes & Routing State ──────────────────────────────────
	private val nextBridgeVirtualPort = AtomicInteger(-1)
	private val peerToBridgeVirtualPort = ConcurrentHashMap<PeerKey, Int>()
	private val bridgeRequestCounter = AtomicLong(1L)
	private val pendingBridgeListRequests =
		ConcurrentHashMap<Long, Pair<TCPNode, CompletableFuture<List<BridgePeerEntry>>>>()

	/** Maps (bridgeHostIp, virtualPort) -> (bridgeCarrierNode, targetPeerKey) discovered via BridgeDiscovery. */
	private val bridgeDirectory = ConcurrentHashMap<Pair<String, Int>, Pair<TCPNode, PeerKey>>()

	/** Maps (bridgeCarrierNode, remotePeerKey) -> active virtual BridgeTCPNode. */
	private val bridgeNodes = ConcurrentHashMap<Pair<TCPNode, PeerKey>, BridgeTCPNode>()

	/** Tracks whether each active virtual BridgeTCPNode was initiated inbound (from remote peer) or outbound. */
	private val bridgeNodeInboundMap = ConcurrentHashMap<BridgeTCPNode, Boolean>()

	/** On a Bridge server, tracks which PeerKeys have active bridged sessions with each other for disconnect cleanup. */
	private val activeBridgePairs = ConcurrentHashMap<PeerKey, MutableSet<PeerKey>>()

	private fun getOrAssignBridgeVirtualPort(peerKey: PeerKey): Int {
		return peerToBridgeVirtualPort.computeIfAbsent(peerKey) {
			nextBridgeVirtualPort.getAndDecrement()
		}
	}

	private val directNodes: List<TCPNode>
		get() = synchronized(connectionMap) { connectionMap.values.toList() } + (tcpServer?.nodeList ?: emptyList())

	private fun getDirectNodeForPeer(peerKey: PeerKey): TCPNode? {
		return directNodes.firstOrNull { !it.isClosed() && nodePeerMap[it]?.key == peerKey }
	}

	private fun getOrCreateBridgeNode(
		carrierNode: TCPNode,
		remotePeerKey: PeerKey,
		virtualPort: Int,
		inbound: Boolean,
		autoSync: Boolean = true
	): BridgeTCPNode {
		val key = Pair(carrierNode, remotePeerKey)
		val existing = bridgeNodes[key]
		if (existing != null && !existing.isClosed()) {
			return existing
		}
		val resolvedPort = if (virtualPort < 0) virtualPort else getOrAssignBridgeVirtualPort(remotePeerKey)
		val bridgeHost = carrierNode.remoteAddress.address?.hostAddress ?: carrierNode.remoteAddress.hostString
		bridgeDirectory[Pair(bridgeHost, resolvedPort)] = Pair(carrierNode, remotePeerKey)

		val newNode = BridgeTCPNode(
			carrierNode = carrierNode,
			remotePeerKeyBytes = remotePeerKey.arr.copyOf(),
			blockHandler = blockHandler,
			virtualPort = resolvedPort
		)
		var Created = false
		val actualNode = bridgeNodes.compute(key) { _, cur ->
			if (cur != null && !cur.isClosed()) {
				cur
			} else {
				Created = true
				newNode
			}
		}!!
		if (!Created) return actualNode

		bridgeNodeInboundMap[actualNode] = inbound
		actualNode.onTransferStateChanged = {
			checkAndNotifyTransferState()
		}
		actualNode.onClose = {
			val alias = nodePeerMap[actualNode]?.key?.name ?: remotePeerKey.name
			logDisconnected(actualNode, alias)
			bridgeNodes.remove(key, actualNode)
			bridgeNodeInboundMap.remove(actualNode)
			cleanupNodeState(actualNode)
			checkAndNotifyTransferState()
			notifyNodeDisconnected(actualNode)
		}
		if (!autoSync) {
			nodesWithoutAutoSync.add(actualNode)
		}
		notifyNodeConnected(actualNode, inbound)
		sendChallenge(actualNode)
		return actualNode
	}

	private fun handleBridgeRawBlock(carrierNode: TCPNode, block: TCPBlock) {
		try {
			when (block.id) {
				TCPBlock.BRIDGE_TO_PEER_ID,
				TCPBlock.BRIDGE_TO_PEER_PRIORITY_ID,
				TCPBlock.BRIDGE_TO_PEER_CLOSE_ID -> {
					val targetKeyBytes = BridgeTCPNode.extractPeerKeyBytes(block) ?: return
					if (!bridgeEnabled) {
						if (block.id != TCPBlock.BRIDGE_TO_PEER_CLOSE_ID) {
							try {
								carrierNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(targetKeyBytes))
							} catch (_: Exception) {}
						}
						return
					}
					val senderPeer = nodePeerMap[carrierNode] ?: return
					val targetPeerKey = PeerKey(targetKeyBytes)
					val targetNode = getDirectNodeForPeer(targetPeerKey)
					val senderVirtualPort = getOrAssignBridgeVirtualPort(senderPeer.key)
					if (targetNode == null || targetNode.isClosed()) {
						if (block.id != TCPBlock.BRIDGE_TO_PEER_CLOSE_ID) {
							try {
								carrierNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(targetKeyBytes))
							} catch (_: Exception) {}
						}
						return
					}
					val rewritten = BridgeTCPNode.rewriteForRecipient(block, senderPeer.key.arr, senderVirtualPort) ?: return
					if (block.id == TCPBlock.BRIDGE_TO_PEER_CLOSE_ID) {
						activeBridgePairs[senderPeer.key]?.remove(targetPeerKey)
						activeBridgePairs[targetPeerKey]?.remove(senderPeer.key)
						targetNode.offerBridgeBlock(rewritten, priority = true, timeoutMs = 2_000L)
						return
					}
					activeBridgePairs.computeIfAbsent(senderPeer.key) { ConcurrentHashMap.newKeySet() }.add(targetPeerKey)
					activeBridgePairs.computeIfAbsent(targetPeerKey) { ConcurrentHashMap.newKeySet() }.add(senderPeer.key)

					val isPriority = block.id == TCPBlock.BRIDGE_TO_PEER_PRIORITY_ID
					val timeoutMs = if (isPriority) 2_000L else 5_000L
					val forwarded = targetNode.offerBridgeBlock(rewritten, priority = isPriority, timeoutMs = timeoutMs)
					if (!forwarded) {
						log("Bridge: congestion forwarding from ${senderPeer.key.name} to ${targetPeerKey.name}, closing bridged session")
						activeBridgePairs[senderPeer.key]?.remove(targetPeerKey)
						activeBridgePairs[targetPeerKey]?.remove(senderPeer.key)
						val targetVirtualPort = getOrAssignBridgeVirtualPort(targetPeerKey)
						try {
							carrierNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(targetKeyBytes, targetVirtualPort))
						} catch (_: Exception) {}
						try {
							targetNode.offerBridgeBlock(
								BridgeTCPNode.createCloseFromPeerBlock(senderPeer.key.arr, senderVirtualPort),
								priority = true,
								timeoutMs = 1_000L
							)
						} catch (_: Exception) {}
					}
				}

				TCPBlock.BRIDGE_FROM_PEER_ID,
				TCPBlock.BRIDGE_FROM_PEER_PRIORITY_ID,
				TCPBlock.BRIDGE_FROM_PEER_CLOSE_ID -> {
					val sourceKeyBytes = BridgeTCPNode.extractPeerKeyBytes(block) ?: return
					val sourcePeerKey = PeerKey(sourceKeyBytes)
					val sourceVirtualPort = BridgeTCPNode.extractVirtualPort(block)
					if (sourceVirtualPort < 0) {
						val bridgeHost = carrierNode.remoteAddress.address?.hostAddress ?: carrierNode.remoteAddress.hostString
						bridgeDirectory[Pair(bridgeHost, sourceVirtualPort)] = Pair(carrierNode, sourcePeerKey)
					}
					if (block.id == TCPBlock.BRIDGE_FROM_PEER_CLOSE_ID) {
						val vNode = bridgeNodes.remove(Pair(carrierNode, sourcePeerKey))
						vNode?.closeFromRemote()
						return
					}
					val innerBlock = BridgeTCPNode.unwrapInnerBlock(block) ?: return
					val vNode = getOrCreateBridgeNode(
						carrierNode = carrierNode,
						remotePeerKey = sourcePeerKey,
						virtualPort = sourceVirtualPort,
						inbound = true,
						autoSync = true
					)
					vNode.receiveUnwrappedBlock(innerBlock)
				}
			}
		} catch (e: Exception) {
			logNodeError(carrierNode, "Error handling bridge block: ${e.message}")
		}
	}

	fun requestBridgePeerList(bridgeNode: TCPNode, timeoutMs: Long = 3_000L): List<BridgePeerEntry> {
		if (bridgeNode.isClosed()) return emptyList()
		val reqId = bridgeRequestCounter.getAndIncrement()
		val future = CompletableFuture<List<BridgePeerEntry>>()
		pendingBridgeListRequests[reqId] = Pair(bridgeNode, future)
		return try {
			bridgeNode.sendPriority(BridgeListRequestMessage(reqId).serialize())
			future.get(timeoutMs, TimeUnit.MILLISECONDS)
		} catch (e: Exception) {
			debug("BridgeListRequest to ${bridgeNode.remoteAddress} timed out or failed: ${e.message}")
			emptyList()
		} finally {
			pendingBridgeListRequests.remove(reqId)
		}
	}

	fun discoverBridgedPeers(found: (PeerAddressInfo) -> Unit) {
		val bridges = directNodes.filter { !it.isClosed() && isNodeBridge(it) }
		for (bridgeNode in bridges) {
			val entries = requestBridgePeerList(bridgeNode)
			val bridgeAddr = bridgeNode.remoteAddress.address ?: continue
			for (entry in entries) {
				if (entry.peerKey.arr.contentEquals(serverIdentity.key.arr)) continue
				found(
					PeerAddressInfo(
						serverKey = ServerKey(entry.peerKey.arr),
						addrList = listOf(bridgeAddr),
						port = entry.virtualPort,
						isRelay = false,
						isBridge = false
					)
				)
			}
		}
	}

	/** Maps each live TCPNode to the nonce we sent them, consumed when their response arrives. */
	private val nodeChallengeMap = ConcurrentHashMap<TCPNode, ByteArray>()

	/** Maps each live TCPNode to the most recent QueryMessage they sent us. */
	private val nodeQueryMap = ConcurrentHashMap<TCPNode, QueryMessage>()

	/** The port the TCP server is currently listening on, or null if not running. */
	val serverPort: Int? get() = tcpServer?.takeIf { !it.socketServer.isClosed }?.socketServer?.localPort

	/** All currently tracked nodes: outgoing (connectionMap) + incoming (server nodeList) + virtual (bridgeNodes). */
	private val allNodes: List<TCPNode>
		get() = synchronized(connectionMap) { connectionMap.values.toList() } +
			(tcpServer?.nodeList ?: emptyList()) +
			bridgeNodes.values.toList()

	// ── Active connection listing ─────────────────────────────────────────────
	data class ConnectionInfo(
		val addr: InetSocketAddress,
		val inbound: Boolean,
		/** PeerKey string from the verified greeting, null if greeting not yet received. */
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

	fun listConnections(): List<ConnectionInfo> {
		val result = mutableListOf<ConnectionInfo>()
		synchronized(connectionMap) {
			connectionMap.forEach { (addr, node) ->
				if (!node.isClosed()) result.add(
					ConnectionInfo(
						addr = addr,
						inbound = false,
						peerKey = nodePeerMap[node]?.key?.toString(),
						isRelay = nodeRelayMap[node] ?: false,
						port = addr.port,
						isBridge = nodeBridgeMap[node] ?: false,
						isBridged = false
					)
				)
			}
		}
		tcpServer?.nodeList?.forEach { node ->
			if (!node.isClosed()) result.add(
				ConnectionInfo(
					addr = node.remoteAddress,
					inbound = true,
					peerKey = nodePeerMap[node]?.key?.toString(),
					isRelay = nodeRelayMap[node] ?: false,
					port = node.remoteAddress.port,
					isBridge = nodeBridgeMap[node] ?: false,
					isBridged = false
				)
			)
		}
		bridgeNodes.values.forEach { vNode ->
			if (!vNode.isClosed()) result.add(
				ConnectionInfo(
					addr = vNode.remoteAddress,
					inbound = bridgeNodeInboundMap[vNode] ?: false,
					peerKey = (nodePeerMap[vNode]?.key ?: PeerKey(vNode.remotePeerKeyBytes)).toString(),
					isRelay = false,
					port = vNode.remotePort,
					isBridge = false,
					isBridged = true
				)
			)
		}
		return result
	}

	fun listDetailedConnections(): List<ConnectionNodeInfo> {
		val result = mutableListOf<ConnectionNodeInfo>()
		synchronized(connectionMap) {
			connectionMap.forEach { (addr, node) ->
				if (!node.isClosed()) {
					val peer = nodePeerMap[node]
					result.add(
						ConnectionNodeInfo(
							addr = addr,
							inbound = false,
							peerKey = peer?.key?.toString(),
							peerName = peer?.key?.name,
							isRelay = nodeRelayMap[node] ?: false,
							isTransferring = node.isTransferring(),
							isSending = node.isSending(),
							isReceiving = node.isReceiving(),
							port = addr.port,
							isBridge = nodeBridgeMap[node] ?: false,
							isBridged = false
						)
					)
				}
			}
		}
		tcpServer?.nodeList?.forEach { node ->
			if (!node.isClosed()) {
				val peer = nodePeerMap[node]
				result.add(
					ConnectionNodeInfo(
						addr = node.remoteAddress,
						inbound = true,
						peerKey = peer?.key?.toString(),
						peerName = peer?.key?.name,
						isRelay = nodeRelayMap[node] ?: false,
						isTransferring = node.isTransferring(),
						isSending = node.isSending(),
						isReceiving = node.isReceiving(),
						port = node.remoteAddress.port,
						isBridge = nodeBridgeMap[node] ?: false,
						isBridged = false
					)
				)
			}
		}
		bridgeNodes.values.forEach { vNode ->
			if (!vNode.isClosed()) {
				val peerKey = nodePeerMap[vNode]?.key ?: PeerKey(vNode.remotePeerKeyBytes)
				result.add(
					ConnectionNodeInfo(
						addr = vNode.remoteAddress,
						inbound = bridgeNodeInboundMap[vNode] ?: false,
						peerKey = peerKey.toString(),
						peerName = peerKey.name,
						isRelay = false,
						isTransferring = vNode.isTransferring(),
						isSending = vNode.isSending(),
						isReceiving = vNode.isReceiving(),
						port = vNode.remotePort,
						isBridge = false,
						isBridged = true
					)
				)
			}
		}
		return result
	}

	// ── Async discovery ───────────────────────────────────────────────────────
	// Runs multicast, scan, and bridge discovery on a background thread.
	// Calls found() for each unique peer as it is discovered; calls done() when finished.
	fun discoverAsync(scan: Boolean, found: (PeerAddressInfo) -> Unit, done: () -> Unit = {}) {
		thread(isDaemon = true, name = "GraffitiDiscover") {
			val seen = Collections.synchronizedSet(LinkedHashSet<ServerKey>())
			fun notify(addr: PeerAddressInfo) {
				if (seen.add(addr.serverKey)) found(addr)
			}
			// Multicast blocks for ~1 s internally — run it concurrently with the scan.
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
				// Give both mechanisms up to 6 s (covers large /24 subnet scans).
				Thread.sleep(6000)
			}
			mFuture.join()
			// Query any connected Bridge nodes for their connected peers (after LAN multicast so direct LAN wins)
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
		// Client-focused keepalive: only outbound (client-initiated) connections send pings
		// and enforce PING_TIMEOUT_MS when not in background periodic-sync mode.
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
							node.close()   // fires onClose → onNodeDisconnected + map removal + auto-reconnect
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
		// Passive server cleanup: prune any inbound or outbound nodes that have closed.
		val closedOutbound = synchronized(connectionMap) {
			connectionMap.values.filter { it.isClosed() }
		}
		closedOutbound.forEach { removeDeadNode(it) }
		val closedInbound = tcpServer?.nodeList?.filter { it.isClosed() }.orEmpty()
		closedInbound.forEach { removeDeadNode(it) }
		val closedBridged = bridgeNodes.values.filter { it.isClosed() }
		closedBridged.forEach { removeDeadNode(it) }
		// Clean up orphaned temp files older than 30 minutes
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
