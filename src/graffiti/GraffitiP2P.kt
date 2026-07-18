package graffiti

import r3.content.Content
import r3.content.ContentMeta
import r3.hash.hash256
import r3.http.WebServer
import r3.io.*
import r3.key.hash256
import r3.net.discover.*
import r3.net.tcp.TCPNode
import r3.net.tcp.TCPServer
import r3.pack.BinaryPack
import r3.pack.PackRouter
import r3.pke.*
import r3.source.FileSink
import r3.source.FileSource
import r3.source.ListWritable
import r3.source.StringWritable
import r3.thread.async
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
Peer to peer synchronization:

// Initial sync (A initiates)
A → B:  QueryMessage { recipientKeys: Set<IdentityKey> }
B → A:  QueryResponseMessage (header) + ListWritable<EncryptedContentMetaData> (file body)
A → B:  ContentRequestMessage { keys: List<EncryptedMetaKey> }  // only keys A wants content for
B → A:  EncryptedMetaMessage + meta header and EncryptedContentMessage + content file (per key)

// Push (when either side receives or creates a new message)
A → B:  SyncResponseMessage (header) + ListWritable<EncryptedContentMetaData> (file body, typically 1 item)
B:      issues ContentRequestMessage if it wants the content


// Blocking: all messages addressed to our identity keys are accepted regardless of author.
// Blocking is handled via a separate block-list checked at display/notification time.

// Liveness (application-level — no SO_KEEPALIVE)
// GraffitiP2P sends PingMessage to every connected node every PING_INTERVAL_MS.
// Any received block (data or pong) resets TCPNode.lastActivityMs.
// Nodes silent for PING_TIMEOUT_MS are closed and removed from tracking structures.
// onNodeConnected / onNodeDisconnected callbacks are fired for UI / WebSocket notifications.
 */

class GraffitiP2P(val graffitiDir: File, relayEnabledAtStartup: Boolean = false) {
	val peerDir = File(graffitiDir, "peer").also { it.mkdirs() }
	val metaDir = File(graffitiDir, "meta").also { it.mkdirs() }
	val contentDir = File(graffitiDir, "content").also { it.mkdirs() }
	val deletedDir = File(graffitiDir, "deleted").also { it.mkdirs() }
	val tmpDir = File(graffitiDir, "tmp").also { it.mkdirs() }
	var defaultP2PPort: Int = 0

	// ── Server identity ───────────────────────────────────────────────────────
	// A dedicated, persistent identity used solely for peer-connection
	// Stored as graffitiDir/serverIdentity; auto-created on first run.
	val serverIdentity: Identity

	@Volatile
	private var relayEnabled = relayEnabledAtStartup

	// ── Storage Size ───────────────────────────────────────────────
	val totalContentSize = AtomicLong(0L)
	private var defaultQuota = 100_000_000L

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

	@Synchronized
	fun purgeOldestHalfOverall(): Int {
		data class MsgEntry(val key: EncryptedMetaKey, val length: Long, val lastModified: Long)

		val allMessages = mutableListOf<MsgEntry>()
		metaDir.listFiles { f -> f.isFile }.orEmpty().forEach { metaFile ->
			try {
				val name = metaFile.name
				val key = EncryptedMetaKey(name)
				allMessages.add(MsgEntry(key, metaFile.length(), metaFile.lastModified()))
			} catch (_: Exception) {
			}
		}
		allMessages.sortBy { it.lastModified }
		val target = storageQuotaBytes / 2
		val iter = allMessages.iterator()
		var count = 0
		while (iter.hasNext() && totalContentSize.get() > target) {
			if (deleteMessage(iter.next().key)) {
				count++
			}
		}
		return count
	}

	@Synchronized
	fun checkAndEnforceQuota() {
		val quota = storageQuotaBytes
		val currentSize = totalContentSize.get()
		if (quota in 0..<currentSize) {
			log("Quota exceeded: $currentSize > $quota. Purging oldest half...")
			val purgedCount = purgeOldestHalfOverall()
			log("Purged $purgedCount messages. New total size: ${totalContentSize.get()}")
		}
	}

	init {
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
		// Initialize totalContentSize cache
		totalContentSize.set(contentDir.listFiles().orEmpty().sumOf { it.length() })
		loadQuota()
	}

	val contentHandler = { node: TCPNode, rawHead: ByteArray, file: File? ->
		try {
			val type = StringWritable.read(rawHead.toDataInputStream()).str
			when (type) {
				// Peer is asking for all metadata we hold that matches their recipient filter.
				// We respond with a QueryResponseMessage + list of matching EncryptedContentMetaData.
				QueryMessage.type -> {
					val msg = QueryMessage.read(rawHead.toDataInputStream())
					nodeQueryMap[node] = msg
					val metaList = mutableListOf<EncryptedContentMetaData>()
					metaDir.listFiles { f -> f.isFile }
						.orEmpty()
						.sortedBy { it.lastModified() }
						.forEach { metaFile ->
							try {
								val eMeta =
									metaFile.toDataInputStream().use { dis -> EncryptedContentMetaData.read(dis) }
								val contentFile = File(contentDir, "${eMeta.key}")
								if (msg.matches(eMeta) && contentFile.exists()) metaList.add(eMeta)
							} catch (e: Exception) {
								log("Error reading meta file ${metaFile.absolutePath}: ${e.message}")
							}
						}
					if (metaList.isNotEmpty()) {
						node.send(
							StringWritable(QueryResponseMessage.type).serialize(),
							ListWritable(metaList.map { it.toHeader() }).serialize()
						)
					}
					log("Query from ${node.remoteAddress}: returning ${metaList.size} metadata items")
				}
				// Response to our earlier QueryMessage: a list of EncryptedContentMetaData from the peer.
				// We save any new meta addressed to us, then request the actual content for those keys.
				QueryResponseMessage.type -> {
					if (file != null) {
						val headerList = file.toDataInputStream().use { dis ->
							ListWritable.read(dis, EncryptedContentHeader::read).list
						}
						val myIdentityKeys = listIdentities().map { it.key }.toSet()
						val myPeerKeys = listPeers().map { it.key }.toSet()
						val wantContent = mutableListOf<EncryptedMetaKey>()
						log("Received QueryResponse from ${node.remoteAddress}: ${headerList.size} header item(s)")
						headerList.forEach { msgHeader ->
							val deletedMetaFile = File(deletedDir, "${msgHeader.key}")
							if (deletedMetaFile.exists()) return@forEach
							if (msgHeader.author.arr.contentEquals(msgHeader.recipient.arr)) {
								log("Ignoring header ${msgHeader.key} from ${node.remoteAddress}: author and recipient are the same")
								return@forEach
							}
							// Non-relay mode: ignore meta not addressed to us or our direct friends
							// (Peers we have explicitly added)
							if (!relayEnabled &&
								msgHeader.recipient !in myIdentityKeys &&
								PeerKey(msgHeader.recipient) !in myPeerKeys
							) {
								log("Ignoring off-recipient meta ${msgHeader.key} from ${node.remoteAddress}")
								return@forEach
							}
							val metaFile = File(metaDir, "${msgHeader.key}")
							// Ask for it only if we don't already have it
							if (!metaFile.exists()) wantContent.add(msgHeader.key)
						}
						if (wantContent.isNotEmpty()) {
							node.send(ContentRequestMessage(wantContent).serialize())
						} else {
							log("No content requests needed from ${node.remoteAddress} after QueryResponse")
						}
					}
				}
				// Peer wants the meta file and content file for a list of keys we hold.
				// We serve each key's EncryptedMetaMessage and EncryptedContentMessage in turn.
				ContentRequestMessage.type -> {
					val req = ContentRequestMessage.read(rawHead.toDataInputStream())
					log("Received ContentRequest from ${node.remoteAddress}: ${req.keys.joinToString()}")
					req.keys.forEach { key ->
						if (File(deletedDir, "$key").exists()) return@forEach
						val metaFile = File(metaDir, "$key")
						val contentFile = File(contentDir, "$key")
						if (metaFile.exists() && contentFile.exists()) {
							val eMeta = metaFile.readBytes().toDataInputStream().use { EncryptedContentMetaData.read(it) }
							node.send(EncryptedContentMessage(eMeta).serialize(), contentFile)
						}
					}
					log("ContentRequest from ${node.remoteAddress}: served ${req.keys.size} keys")
				}
				// Incoming encrypted content file for a specific key.
				EncryptedContentMessage.type -> {
					val contentMessage = EncryptedContentMessage.read(rawHead.toDataInputStream())
					val eMeta = contentMessage.eMeta
					if (file != null) {
						val deletedMetaFile = File(deletedDir, eMeta.key.toString())
						if (deletedMetaFile.exists()) {
							file.delete()
							log("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: key is deleted")
						} else if (eMeta.author.arr.contentEquals(eMeta.recipient.arr)) {
							file.delete()
							log("Ignoring content ${eMeta.key.name} from ${node.remoteAddress}: author and recipient are the same")
						} else {
							val peer = listPeers().firstOrNull { it.key == eMeta.author }
								?: ephemeralIdentities.firstOrNull { it.asPeer().key == eMeta.author }?.asPeer()
							val identity = listIdentities().firstOrNull { it.key == eMeta.recipient }
							val valid = if (peer != null) {
								if (identity != null) {
									// We have both: verify metadata signature AND content hash
									eMeta.verify(peer, FileSource(file), identity)
								} else {
									// We only have the author peer (relaying): verify metadata signature
									eMeta.verify(peer)
								}
							} else {
								true
							}
							if (!valid) error("Invalid Content Message")
							val destFile = File(contentDir, eMeta.key.toString())
							val metaFile = File(metaDir, eMeta.key.toString())
							var contentStored = destFile.exists()
							if (!contentStored) {
								if (file.renameTo(destFile)) {
									contentStored = true
									totalContentSize.addAndGet(destFile.length())
									checkAndEnforceQuota()
								} else {
									log("Failed to move content file to content directory")
								}
							}
							if (contentStored && !metaFile.exists()) {
								metaFile.writeBytes(eMeta.serialize())
								if (metaFile.exists()) {
									onMessageReceived?.invoke(eMeta.key)
									if (relayEnabled) {
										pushNewMessage(eMeta.key, excludeNode = node)
									}
								}
							}
						}
					}
					log("Received content ${eMeta.key} from ${node.remoteAddress}")
				}
				// Keepalive ping — respond immediately with a pong so the sender resets our activity timer.
				PingMessage.type -> node.send(StringWritable(PongMessage.type).serialize())
				// Keepalive pong — no action needed; TCPNode already refreshed lastActivityMs on block receipt.
				PongMessage.type -> { /* lastActivityMs already refreshed by TCPNode on block receipt */
				}
				// Peer is challenging us to prove our server identity.
				// We sign their nonce with our server identity key and send back the response.
				ChallengeMessage.type -> {
					val msg = ChallengeMessage.read(rawHead.toDataInputStream())
					try {
						node.send(
							ChallengeResponseMessage(
								serverIdentity.asPeer(),
								serverIdentity.sign(msg.nonce),
								relayEnabled
							).serialize()
						)
					} catch (e: Exception) {
						log("Challenge response to ${node.remoteAddress} failed: ${e.message}")
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
						onNodeIdentified?.invoke(node, msg.peer)
						log("Auth OK: ${node.remoteAddress} → ${msg.peer.key.name}")
						if (node !in nodesWithoutAutoSync) {
							syncNode(node)
						}
					} else {
						log("Auth FAILED from ${node.remoteAddress} (nonce=${ourNonce != null}) — closing")
						node.close()
					}
				}

				else -> log("Unknown message type: $type from ${node.remoteAddress}")
			}
		} catch (e: Exception) {
			log("Error in contentHandler: ${e.message}")
			e.printStackTrace(System.out)
		}
	}

	/** Send a challenge nonce to [node] and remember it so we can verify their response. */
	private fun sendChallenge(node: TCPNode) {
		try {
			val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
			nodeChallengeMap[node] = nonce
			node.send(ChallengeMessage(nonce).serialize())
		} catch (e: Exception) {
			log("sendChallenge to ${node.remoteAddress} failed: ${e.message}")
		}
	}

	fun getTCPNode(addr: InetSocketAddress, autoSync: Boolean = true): TCPNode {
		val node = connectionMap[addr]
		if (node != null && !node.isClosed()) {
			return node
		}
		connectionMap.remove(addr)
		val socket = Socket()
		socket.connect(addr)
		val newNode = TCPNode(socket, tmpDir, contentHandler)
		newNode.onClose = {
			connectionMap.remove(addr)
			nodePeerMap.remove(newNode)
			nodeRelayMap.remove(newNode)
			nodeChallengeMap.remove(newNode)
			nodeQueryMap.remove(newNode)
			nodesWithoutAutoSync.remove(newNode)
			onNodeDisconnected?.invoke(newNode)
		}
		connectionMap[addr] = newNode
		if (!autoSync) {
			nodesWithoutAutoSync.add(newNode)
		}
		onNodeConnected?.invoke(newNode, false)
		sendChallenge(newNode)
		syncNode(newNode)
		return newNode
	}

	fun disconnect(addr: InetSocketAddress) {
		connectionMap[addr]?.close()
		tcpServer?.nodeList?.firstOrNull { !it.isClosed() && it.remoteAddress == addr }?.close()
	}

	private fun syncNode(node: TCPNode) {
		if (node.isClosed()) return
		val myIdentityKeys = listIdentities().map { it.key }.toSet()
		if (!relayEnabled && myIdentityKeys.isEmpty()) return
		val recipientFilter = if (relayEnabled) QueryCondition.ALL
		else QueryCondition(myIdentityKeys, QueryCondition.ConditionType.Include)
		log("Syncing with ${node.remoteAddress} (${if (relayEnabled) "relay/all recipients" else "recipient filter ${myIdentityKeys.joinToString()}"})")
		node.send(
			QueryMessage(
				QueryCondition.ALL,
				recipientFilter
			).serialize()
		)
	}

	fun isRelayEnabled(): Boolean = relayEnabled
	fun setRelayEnabled(enabled: Boolean) {
		if (relayEnabled == enabled) return
		relayEnabled = enabled
		restartDiscoveryServers()
		if (enabled) {
			queryAllFromConnectedNodes()
		}
	}

	private fun restartDiscoveryServers() {
		val port = serverPort ?: return
		mcd?.close()
		sd?.close()
		val discoverInfo =
			PeerAddressInfo(ServerKey(serverIdentity.key.arr), tcpServer!!.peerAddressInfo.addrList, port, relayEnabled)
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
		}
	}

	private fun queryAllFromNode(node: TCPNode) {
		if (node.isClosed()) return
		val queryAll = QueryMessage(QueryCondition.ALL, QueryCondition.ALL).serialize()
		runCatching { node.send(queryAll) }
	}

	// Pushes a newly stored message's metadata to all currently connected peers.
	// Each peer will automatically request content if they want it.
	fun pushNewMessage(metaKey: EncryptedMetaKey, excludeNode: TCPNode? = null) {
		val metaFile = File(metaDir, "$metaKey")
		if (!metaFile.exists()) return
		val contentFile = File(contentDir, "$metaKey")
		if (!contentFile.exists()) return // Must have the content before we push/relay it!
		val eMeta = try {
			metaFile.toDataInputStream().use { EncryptedContentMetaData.read(it) }
		} catch (e: Exception) {
			log("pushNewMessage: failed to read meta for $metaKey: ${e.message}")
			return
		}
		val header = StringWritable(QueryResponseMessage.type).serialize()
		val body = ListWritable(listOf(eMeta.toHeader())).serialize()
		allNodes.filter { !it.isClosed() && it != excludeNode }.forEach { node ->
			val query = nodeQueryMap[node]
			if (query != null && query.matches(eMeta)) {
				node.send(header, body)
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
		return ephemeralIdentities
	}

	fun createIdentity(seed: String): Identity {
		val byteSeed = seed.toByteArray().hash256()
		val iden = Identity(byteSeed)
		ephemeralIdentities.add(iden)
		syncAllConnectedNodes()
		return iden
	}

	fun listPeers(): List<Peer> {
		// Only disk-persisted peers — these are explicitly imported contacts.
		val peerFiles =
			peerDir.listFiles { file -> file.isFile } ?: emptyArray()
		return peerFiles.map { file -> file.readBytes().toDataInputStream().use { Peer.read(it) } }
	}

	fun removePeer(key: PeerKey): Boolean {
		val file = findPeerFile(key)
		return if (file != null) {
			file.delete()
		} else {
			log("Peer file for key ${key.name} not found, cannot remove")
			false
		}
	}

	/** Remove an ephemeral identity from this session. */
	fun removeIdentity(key: IdentityKey): Boolean {
		val removed = ephemeralIdentities.removeIf { it.key == key }
		return removed
	}

	fun pkeEncrypt(content: Content, authorIdentity: Identity, recipientPeer: Peer): EncryptedMetaKey {
		val contentKey = ContentKey(content.hash256())
		val meta = ContentMeta(content)
		val pass = Password256.createPassword()
		val eMeta = meta.encrypt(authorIdentity, recipientPeer, contentKey, pass)
		val contentFile = File(contentDir, eMeta.key.toString()).consistentFile()
		content.encrypt(pass, FileSink(contentFile, false))
		totalContentSize.addAndGet(contentFile.length())
		val metaDest = File(metaDir, eMeta.key.toString()).consistentFile()
		metaDest.writeBytes(eMeta.serialize())
		checkAndEnforceQuota()
		return eMeta.key
	}

	private var tcpServer: TCPServer? = null
	private var mcd: MulticastDiscoverServer? = null
	private var sd: ScanDiscoverServer? = null
	fun startTCPServer(port: Int) {
		val nodeList = CopyOnWriteArrayList<TCPNode>()
		TCPServer(
			nodeList, tmpDir, contentHandler, InetSocketAddress(port),
			serverKey = ServerKey(serverIdentity.key.arr),
			onAccept = { node ->
				node.onClose = {
					nodeList.remove(node)
					nodePeerMap.remove(node)
					nodeRelayMap.remove(node)
					nodeChallengeMap.remove(node)
					nodeQueryMap.remove(node)
					nodesWithoutAutoSync.remove(node)
					onNodeDisconnected?.invoke(node)
				}
				onNodeConnected?.invoke(node, true)
				sendChallenge(node)
				syncNode(node)
			}
		).also {
			tcpServer = it
			it.start(false)
			log("TCP server started on ${it.peerAddressInfo}")
			// Advertise the server identity key so that peers discovering us
			// see the stable server avatar/name, not an IP-hash key.
			val discoverInfo =
				PeerAddressInfo(
					ServerKey(serverIdentity.key.arr),
					it.peerAddressInfo.addrList,
					it.peerAddressInfo.port,
					relayEnabled
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
		log("Server stopped")
	}

	fun getIdentityByKey(key: IdentityKey): Identity? =
		listIdentities().firstOrNull { it.key == key }

	fun getPeerByKey(key: PeerKey): Peer? =
		listPeers().firstOrNull { it.key == key }
			?: ephemeralIdentities.firstOrNull { it.asPeer().key == key }?.asPeer()

	fun getContent(key: EncryptedMetaKey): Content {
		val metaFile = File(metaDir, "$key").consistentFile()
		if (!metaFile.exists()) {
			error("Meta file not found for key: $key")
		}
		val eMeta = metaFile.toDataInputStream().use { dis -> EncryptedContentMetaData.read(dis) }
		val recipientIdentity = getIdentityByKey(eMeta.recipient) ?: error("No Identity found for ${eMeta.recipient}")
		val (meta, pass) = try {
			eMeta.decrypt(recipientIdentity)
		} catch (e: Exception) {
			error("Failed to decrypt metadata. Wrong identity or corrupted file. ${e.message}")
		}
		val contentFile = File(contentDir, "$key").consistentFile()
		if (!contentFile.exists()) {
			error("Content file not found for key: $key")
		}
		return EncryptContent(pass, FileSource(contentFile), meta)
	}

	@Synchronized
	fun deleteMessage(key: EncryptedMetaKey): Boolean {
		val metaFile = File(metaDir, "$key")
		val deletedMetaFile = File(deletedDir, "$key")
		val hadMeta = metaFile.exists() || deletedMetaFile.exists()
		if (metaFile.exists()) {
			if (!metaFile.renameTo(deletedMetaFile)) {
				runCatching {
					deletedMetaFile.writeBytes(metaFile.readBytes())
					metaFile.delete()
				}.onFailure {
					log("Failed to move meta $key to deleted directory: ${it.message}")
					return false
				}
			}
		}
		val contentFile = File(contentDir, "$key")
		val size = if (contentFile.exists()) contentFile.length() else 0L
		if (size > 0L) {
			if (contentFile.delete()) {
				totalContentSize.addAndGet(-size)
			} else {
				log("Failed to delete content file for $key")
			}
		}
		return hadMeta
	}

	// Session-only (ephemeral) identities — held in RAM, never written to disk.
	// They vanish when the application exits.
	private val ephemeralIdentities = CopyOnWriteArrayList<Identity>()
	private val connectionMap = Collections.synchronizedMap(mutableMapOf<InetSocketAddress, TCPNode>())
	private val nodesWithoutAutoSync = Collections.synchronizedSet(mutableSetOf<TCPNode>())

	// ── Connection lifecycle callbacks ────────────────────────────────────────
	var onNodeConnected: ((node: TCPNode, inbound: Boolean) -> Unit)? = null
	var onNodeDisconnected: ((node: TCPNode) -> Unit)? = null

	/** Fired when a greeting is received and the peer's signature is verified. */
	var onNodeIdentified: ((node: TCPNode, peer: Peer) -> Unit)? = null

	/** Fired when a new encrypted meta file is successfully stored from a peer. */
	var onMessageReceived: ((key: EncryptedMetaKey) -> Unit)? = null

	/** Maps each live TCPNode to the verified Peer from its challenge-response. */
	private val nodePeerMap = ConcurrentHashMap<TCPNode, Peer>()

	/** Maps each live TCPNode to whether they are a relay. */
	private val nodeRelayMap = ConcurrentHashMap<TCPNode, Boolean>()
	fun isNodeRelay(node: TCPNode): Boolean = nodeRelayMap[node] ?: false

	/** Maps each live TCPNode to the nonce we sent them, consumed when their response arrives. */
	private val nodeChallengeMap = ConcurrentHashMap<TCPNode, ByteArray>()

	/** Maps each live TCPNode to the most recent QueryMessage they sent us. */
	private val nodeQueryMap = ConcurrentHashMap<TCPNode, QueryMessage>()

	/** The port the TCP server is currently listening on, or null if not running. */
	val serverPort: Int? get() = tcpServer?.takeIf { !it.socketServer.isClosed }?.socketServer?.localPort

	/** All currently tracked nodes: outgoing (connectionMap) + incoming (server nodeList). */
	private val allNodes: List<TCPNode>
		get() = synchronized(connectionMap) { connectionMap.values.toList() } + (tcpServer?.nodeList ?: emptyList())

	// ── Active connection listing ─────────────────────────────────────────────
	data class ConnectionInfo(
		val addr: InetSocketAddress,
		val inbound: Boolean,
		/** PeerKey string from the verified greeting, null if greeting not yet received. */
		val peerKey: String? = null,
		val isRelay: Boolean = false
	)

	fun listConnections(): List<ConnectionInfo> {
		val result = mutableListOf<ConnectionInfo>()
		synchronized(connectionMap) {
			connectionMap.forEach { (addr, node) ->
				if (!node.isClosed()) result.add(
					ConnectionInfo(
						addr,
						false,
						nodePeerMap[node]?.key?.toString(),
						nodeRelayMap[node] ?: false
					)
				)
			}
		}
		tcpServer?.nodeList?.forEach { node ->
			if (!node.isClosed()) result.add(
				ConnectionInfo(
					node.remoteAddress,
					true,
					nodePeerMap[node]?.key?.toString(),
					nodeRelayMap[node] ?: false
				)
			)
		}
		return result
	}

	// ── Async discovery ───────────────────────────────────────────────────────
	// Runs multicast and scan discovery concurrently on a background thread.
	// Calls found() for each unique peer as it is discovered; calls done() when finished.
	fun discoverAsync(scan:Boolean, found: (PeerAddressInfo) -> Unit, done: () -> Unit = {}) {
		thread(isDaemon = true, name = "GraffitiDiscover") {
			val seen = Collections.synchronizedSet(LinkedHashSet<ServerKey>())
			fun notify(addr: PeerAddressInfo) {
				if (seen.add(addr.serverKey)) found(addr)
			}
			// Multicast blocks for ~1 s internally — run it concurrently with the scan.
			val mFuture = async { MulticastDiscover().discover { notify(it) } }
			if(scan) {
				ScanDiscover().discover { notify(it) }
				// Give both mechanisms up to 6 s (covers large /24 subnet scans).
				Thread.sleep(6000)
			}
			mFuture.join()
			done()
		}
	}

	// ── Application-level ping scheduler (no SO_KEEPALIVE) ───────────────────
	companion object {
		const val PING_INTERVAL_MS = 30_000L  // send a ping every 30 s
		const val PING_TIMEOUT_MS = 3 * PING_INTERVAL_MS  // close after 90 s of silence (3 missed pings)
	}

	init {
		thread(isDaemon = true, name = "GraffitiPing") {
			while (!Thread.interrupted()) {
				try {
					Thread.sleep(PING_INTERVAL_MS)
					pingAndCleanup()
				} catch (_: InterruptedException) {
					break
				} catch (e: Exception) {
					log("GraffitiPing: ${e.message}")
				}
			}
		}
	}

	private fun pingAndCleanup() {
		val now = System.currentTimeMillis()
		val pingHeader = StringWritable(PingMessage.type).serialize()
		allNodes.forEach { node ->
			if (!node.isClosed()) {
				if (now - node.lastActivityMs > PING_TIMEOUT_MS) {
					log("Closing stale node ${node.remoteAddress} (silent for ${(now - node.lastActivityMs) / 1000}s)")
					node.close()   // fires onClose → onNodeDisconnected + map removal
				} else {
					try {
						node.send(pingHeader)
					} catch (_: Exception) {
					}
				}
			}
		}
		// Belt-and-suspenders: prune entries that closed without firing their onClose.
		synchronized(connectionMap) { connectionMap.entries.removeIf { it.value.isClosed() } }
		tcpServer?.nodeList?.removeIf { it.isClosed() }
	}

	fun createPack(sourceDir: String, outDir: String) {
		createPack(File(sourceDir), File(outDir))
	}

	fun createPack(sourceDir: File, outDir: File) {
		if (!sourceDir.exists() || !sourceDir.isDirectory) {
			error("Source directory does not exist or is not a directory: $sourceDir")
		}
		if (!outDir.exists()) {
			outDir.mkdirs()
		} else if (!outDir.isDirectory) {
			error("Output path exists but is not a directory: $outDir")
		}
		val packFile = File(outDir, "${sourceDir.name}.pack")
		BinaryPack.create(sourceDir.iterable(), FileSink(packFile, false))
		log("Pack created at ${packFile.absolutePath}")
	}

	fun listPack(packFile: String): List<Content> {
		return listPack(File(packFile))
	}

	fun listPack(packFile: File): List<Content> {
		if (!packFile.exists() || !packFile.isFile) {
			error("Pack file does not exist or is not a file: $packFile")
		}
		val contentList = ArrayList<Content>()
		val pack = BinaryPack(FileSource(packFile))
		pack.forEach {
			contentList.add(it)
		}
		return contentList
	}

	fun viewPack(packFile: String) {
		viewPack(File(packFile))
	}

	fun viewPack(packFile: File) {
		if (!packFile.exists() || !packFile.isFile) {
			error("Pack file does not exist or is not a file: $packFile")
		}
		val pack = BinaryPack(FileSource(packFile))
		val packRouter = PackRouter(pack)
		val ws = WebServer(
			"localhost",
			0,
			tmpDir
		)
		ws.start(0, false)
		// TODO - replace with way that is Android and Desktop compatible
//		Desktop.getDesktop().browse(URI.create("http://localhost:${ws.listeningPort}/"))
		log("Pack server started on port ${ws.listeningPort}")
	}
}
