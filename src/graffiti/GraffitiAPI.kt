package graffiti

import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import r3.content.BinaryContent
import r3.content.Content
import r3.content.ContentMeta
import r3.content.JsonContent
import r3.content.TextContent
import r3.http.ContentHandler
import r3.io.log
import r3.io.serialize
import r3.key.Key256
import r3.org.json.JSONArray
import r3.org.json.JSONObject
import r3.pke.EncryptContent
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.pke.Peer
import r3.pke.PeerKey
import r3.pke.name
import r3.source.FileSource
import r3.source.readString
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

class GraffitiAPI(val p2p: GraffitiP2P, val sendToAll: (JSONObject) -> Unit) : ContentHandler {
	init {
		// Wire up p2p event callbacks — p2p is always ready at construction.
		p2p.onNodeConnected = { node, inbound ->
			sendToAll(
				JSONObject()
					.put("event", "node_connected")
					.put("host", node.remoteAddress.address.hostAddress)
					.put("port", node.remoteAddress.port)
					.put("inbound", inbound)
			)
		}
		p2p.onNodeDisconnected = { node ->
			sendToAll(
				JSONObject()
					.put("event", "node_disconnected")
					.put("host", node.remoteAddress.address.hostAddress)
					.put("port", node.remoteAddress.port)
			)
		}
		p2p.onNodeIdentified = { node, peer ->
			val peerFile = File(p2p.peerDir, "${peer.key}")
			if (!peerFile.exists()) {
				try {
					peerFile.writeBytes(peer.serialize())
					sendToAll(
						JSONObject().put("event", "peers_update")
					)
				} catch (e: Exception) {
					log("Failed to auto-import peer ${peer.key.name}: ${e.message}")
				}
			}
			sendToAll(
				JSONObject()
					.put("event", "node_identified")
					.put("host", node.remoteAddress.address.hostAddress)
					.put("port", node.remoteAddress.port)
					.put("peerKey", peer.key.toString())
					.put("peerName", peer.key.name)
					.put("relay", p2p.isNodeRelay(node))
			)
			p2p.syncAllConnectedNodes()
		}
		p2p.onMessageReceived = { encKey ->
			val metaFile = File(p2p.metaDir, "$encKey")
			val contentFile = File(p2p.contentDir, "$encKey")
			if (metaFile.exists() && contentFile.exists()) {
				try {
					val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
					val iden = p2p.getIdentityByKey(eMeta.recipient) ?: error("No identity found for ${eMeta.recipient}")
					val (meta, _) = eMeta.decrypt(iden)
					sendToAll(
						JSONObject().put("event", "messages_update").put("action", "add")
							.put("msg", buildMsgJson(eMeta, meta))
					)
				} catch (_: Exception) {
					// Still notify so the UI can fall back to a full refresh.
					sendToAll(
						JSONObject().put("event", "messages_update").put("action", "add")
					)
				}
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────
	private fun ok(build: JSONObject.() -> Unit = {}): Content {
		return JsonContent(JSONObject().put("ok", true).apply(build).toString())
	}

	private fun err(msg: String, status: Status = Status.BAD_REQUEST): Content {
		return JsonContent(JSONObject().put("ok", false).put("error", msg).toString())
	}

	private fun dispatch(header: JSONObject, content: Content?): Content? {
		val path = header.get("path")
		return when (path) {
			"/api/avatar" -> serveAvatar(header)
			"/api/identities" -> listIdentities()
			"/api/identity/create" -> createIdentity(header, content)
			"/api/identity/remove" -> removeIdentity(header)
			"/api/peers" -> listPeers(header)
			"/api/peer/export" -> exportPeer(header)
			"/api/peer/import" -> content?.let { importPeer(header, it) }
			"/api/peer/remove" -> removePeer(header)
			"/api/messages" -> listMessages()
			"/api/messages/refresh" -> refreshMessages()
			"/api/message/export" -> exportMessage(header)
			"/api/message/remove" -> removeMessage(header)
			"/api/message/send/text" -> content?.let { sendTextMessage(header, it) }
			"/api/message/send/file" -> content?.let { sendFileMessage(header, it) }
			"/api/content" -> getContent(header)
			"/api/storage" -> getStorageInfo()
			"/api/storage/purge" -> purgeStorage(header)
			"/api/storage/quota" -> if (header.has("quota")) setQuota(header) else getQuota()
			"/api/server/start" -> startServer(header)
			"/api/server/stop" -> stopServer()
			"/api/client/connect" -> connect(header)
			"/api/client/disconnect" -> disconnect(header)
			"/api/node/info" -> info(header)
			"/api/node/relay" -> relay(header)
			"/api/server/status" -> status(header)
			"/api/connections" -> connections()
			"/api/discover" -> if (header.optBoolean("scan")) discover(true) else discover(false)
			"/api/store" -> handleStore(header, content)
			else -> null
		}
	}

	// ── Identity ──────────────────────────────────────────────────────────────
	private fun listIdentities(): Content {
		val arr = JSONArray()
		p2p.listIdentities()
			.sortedWith(compareBy({ it.key.name.lowercase() }, { it.key.toString() }))
			.forEach { iden ->
				val keyStr = iden.key.toString()
				arr.put(
					JSONObject()
						.put("name", iden.key.name)
						.put("key", keyStr)
						.put("peerKey", iden.asPeer().key.toString())
				)
			}
		return ok { put("identities", arr) }
	}

	private fun createIdentity(header: JSONObject, content: Content?): Content {
		val seed = header.getString("seed")
		val iden = p2p.createIdentity(seed)
		sendToAll(JSONObject().put("event", "identities_update"))
		sendToAll(JSONObject().put("event", "messages_reload"))
		return ok { put("name", iden.key.name).put("key", iden.key) }
	}

	private fun removeIdentity(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val removed = p2p.removeIdentity(IdentityKey(keyStr))
		sendToAll(JSONObject().put("event", "identities_update"))
		sendToAll(JSONObject().put("event", "messages_reload"))
		return if (removed) ok() else err("Identity not found")
	}

	// ── Peer ──────────────────────────────────────────────────────────────────
	private fun listPeers(header: JSONObject): Content {
		val arr = JSONArray()
		p2p.listPeers()
			.sortedWith(compareBy({ it.key.name.lowercase() }, { it.key }))
			.forEach { peer ->
				val keyStr = peer.key
				arr.put(
					JSONObject()
						.put("name", peer.key.name)
						.put("key", keyStr)
				)
			}
		return ok { put("peers", arr) }
	}

	private fun importPeer(header: JSONObject, content: Content): Content {
		val peer = try {
			Peer.read(DataInputStream(content.createInputStream()))
		} catch (e: Exception) {
			return err("Invalid peer file: ${e.message}")
		}
		File(p2p.peerDir, "${peer.key}").writeBytes(peer.serialize())
		sendToAll(JSONObject().put("event", "peers_update"))
		return ok { put("name", peer.key.name).put("key", peer.key) }
	}

	/** POST param: {@code key} (PeerKey as string) */
	private fun removePeer(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val removed = p2p.removePeer(PeerKey(keyStr))
		sendToAll(JSONObject().put("event", "peers_update"))
		return if (removed) ok() else err("Peer not found")
	}

	/** GET param: {@code key} — returns the peer file as a download */
	private fun exportPeer(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val peer = p2p.getPeerByKey(PeerKey(keyStr)) ?: error("No peer found for $keyStr")
		return BinaryContent(
			peer.serialize(),
			path = "${peer.key}",
			ext = "application/octet-stream"
		)
	}
// ── Message ───────────────────────────────────────────────────────────────
	/** Builds the display JSON for a single message. Shared by listMessages and WS push. */
	private fun buildMsgJson(eMeta: EncryptedContentMetaData, meta: ContentMeta): JSONObject {
		return JSONObject()
			.put("key", eMeta.key.toString())
			.put("author", eMeta.author.name)
			.put("authorKey", eMeta.author.toString())
			.put("recipient", eMeta.recipient.name)
			.put("recipientKey", eMeta.recipient.toString())
			.put("name", meta.name)
			.put("size", meta.length)
			.put("type", meta.type)
			.put("created", meta.created)
	}

	private fun listMessages(): Content {
		val identities = p2p.listIdentities().associateBy { it.key }

		data class Entry(val created: Long, val json: JSONObject)

		val entries = mutableListOf<Entry>()
		p2p.metaDir.listFiles { f -> f.isFile }.orEmpty().forEach { metaFile ->
			try {
				val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
				val contentFile = File(p2p.contentDir, eMeta.key.toString())
				if (!contentFile.exists()) return@forEach
				val iden = identities[eMeta.recipient] ?: return@forEach
				val (meta, _) = eMeta.decrypt(iden)
				val fileTime = metaFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
				entries.add(Entry(fileTime, buildMsgJson(eMeta, meta)))
			} catch (_: Exception) { /* skip unreadable / undecryptable meta files */
			}
		}
		val arr = JSONArray()
		entries.sortedWith(compareBy({ it.created }, { it.json.optLong("created", 0L) }))
			.forEach { arr.put(it.json) }
		return ok { put("messages", arr) }
	}

	private fun refreshMessages(): Content {
		p2p.syncAllConnectedNodes()
		return ok()
	}

	private fun getContent(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val key = EncryptedMetaKey(keyStr)
		return p2p.getContent(key)
	}

	private fun getStorageInfo(): Content {
		val identities = p2p.listIdentities()
		val sizeMap = mutableMapOf<IdentityKey, Long>()
		identities.forEach { iden ->
			sizeMap[iden.key] = 0L
		}

		p2p.metaDir.listFiles { f -> f.isFile }.orEmpty().forEach { metaFile ->
			try {
				val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
				val contentFile = File(p2p.contentDir, eMeta.key.toString())
				val contentSize = if (contentFile.exists()) contentFile.length() else 0L
				sizeMap[eMeta.recipient] = sizeMap.getOrDefault(eMeta.recipient, 0L) + contentSize
			} catch (_: Exception) {
			}
		}
		val totalOverall = p2p.totalContentSize.get()
		val arr = JSONArray()
		sizeMap.entries
			.sortedWith(compareBy({ it.key.name.lowercase() }, { it.key.toString() }))
			.forEach { (key, size) ->
				val keyStr = key.toString()
				arr.put(
					JSONObject()
						.put("name", key.name)
						.put("key", keyStr)
						.put("size", size)
				)
			}

		return ok {
			put("overall", totalOverall)
			put("storage", arr)
		}
	}

	private fun getQuota(): Content {
		return ok {
			put("quota", p2p.getQuotaBytes())
		}
	}

	private fun setQuota(header: JSONObject): Content {
		val quotaStr = header.getString("quota")
		val quota = quotaStr.toLongOrNull() ?: return err("Invalid 'quota' parameter")
		p2p.setQuotaBytes(quota)
		return ok()
	}

	private fun startServer(header: JSONObject): Content {
		val port = header.getInt("port")
		p2p.startTCPServer(port)
		val actualPort = p2p.serverPort ?: port
		return ok { put("port", actualPort) }
	}

	private fun stopServer(): Content {
		p2p.stopTCPServer()
		return ok()
	}

	private fun connect(header: JSONObject): Content {
		val nodes = header.optJSONArray("node") ?: JSONArray().put(header.getString("node"))
		val port = header.getInt("port")
		var lastError: Exception? = null
		val length = nodes.length()
		for (i in 0 until length) {
			val n = nodes.getString(i)
			val node = n.trim()
			if (node.isEmpty()) continue
			try {
				val addr = InetAddress.getByName(node)
				if (addr.isLinkLocalAddress) continue
				p2p.getTCPNode(InetSocketAddress(addr, port))
				p2p.syncAllConnectedNodes()
				return ok()
			} catch (e: Exception) {
				lastError = e
				log("Connect failed for $node: ${e.message}")
			}
		}
		return err("Failed to connect: ${lastError?.message}")
	}

	private fun disconnect(header: JSONObject): Content {
		val node = header.getString("node")
		val port = header.getInt("port")
		p2p.disconnect(InetSocketAddress(InetAddress.getByName(node), port))
		return ok()
	}

	private fun purgeStorage(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val type = header.getString("type")
		val targetKey = IdentityKey(keyStr)

		data class MsgEntry(val key: EncryptedMetaKey, val lastModified: Long)

		val matchedMessages = mutableListOf<MsgEntry>()

		p2p.metaDir.listFiles { f -> f.isFile }.orEmpty().forEach { metaFile ->
			try {
				val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
				if (eMeta.recipient == targetKey) {
					matchedMessages.add(MsgEntry(eMeta.key, metaFile.lastModified()))
				}
			} catch (_: Exception) {
			}
		}

		matchedMessages.sortBy { it.lastModified }
		val toDelete = if (type == "half") {
			matchedMessages.take(matchedMessages.size / 2)
		} else {
			matchedMessages
		}
		var count = 0
		toDelete.forEach { msg ->
			if (p2p.deleteMessage(msg.key)) {
				count++
			}
		}

		sendToAll(JSONObject().put("event", "messages_reload"))

		return ok {
			put("purged", count)
		}
	}

	private fun removeMessage(header: JSONObject): Content {
		val key = header.getString("key")
		p2p.deleteMessage(EncryptedMetaKey(key))
		sendToAll(
			JSONObject().put("event", "messages_update").put("action", "remove").put("key", key)
		)
		return ok()
	}

	private fun exportMessage(header: JSONObject): Content {
		val keyStr = header.getString("key")
		val key = EncryptedMetaKey(keyStr)
		val metaFile = File(p2p.metaDir, "$key")
		if (!metaFile.exists()) return err("Message not found")
		val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
		val iden = p2p.getIdentityByKey(eMeta.recipient) ?: error("No Identity found for ${eMeta.recipient}")
		val (meta, pass) = eMeta.decrypt(iden)
		val contentFile = File(p2p.contentDir, "$key")
		return EncryptContent(pass, FileSource(contentFile), meta)
	}

	private fun sendTextMessage(header: JSONObject, content: Content): Content {
		val identityKey = header.getString("identityKey")
		val peerKey = header.getString("peerKey")
		val text = content.readString()
		val iden = p2p.getIdentityByKey(IdentityKey(identityKey)) ?: error("No Identity found for $identityKey")
		val peer = p2p.getPeerByKey(PeerKey(peerKey)) ?: error("No peer found for $peerKey")
		val encKey = p2p.pkeEncrypt(TextContent(text), iden, peer)
		p2p.pushNewMessage(encKey)
		val metaFile = File(p2p.metaDir, "$encKey")
		try {
			val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
			val (meta, _) = eMeta.decrypt(iden)
			sendToAll(
				JSONObject().put("event", "messages_update").put("action", "add")
					.put("msg", buildMsgJson(eMeta, meta))
			)
		} catch (_: Exception) {
			sendToAll(JSONObject().put("event", "messages_update").put("action", "add"))
		}
		return ok { put("key", encKey.toString()) }
	}

	private fun sendFileMessage(header: JSONObject, content: Content): Content {
		val identityKey = header.getString("identityKey") ?: return err("Missing 'identityKey'")
		val peerKey = header.getString("peerKey") ?: return err("Missing 'peerKey'")
		val fileParam = header.optString("file").takeIf { it.isNotEmpty() } ?: "file"
		val originalName = try {
			java.net.URLDecoder.decode(fileParam, "UTF-8")
		} catch (_: Exception) {
			fileParam
		}
		val iden = p2p.getIdentityByKey(IdentityKey(identityKey)) ?: error("No identity found for $identityKey")
		val peer = p2p.getPeerByKey(PeerKey(peerKey)) ?: error("No peer found for $peerKey")
		val wrappedContent = MutableMetaDataContent(content).apply {
			path = originalName
			ext = originalName.substringAfterLast('.', "").lowercase()
		}
		val encKey = p2p.pkeEncrypt(wrappedContent, iden, peer)
		p2p.pushNewMessage(encKey)
		val metaFile = File(p2p.metaDir, "$encKey")
		try {
			val eMeta = DataInputStream(metaFile.inputStream()).use { EncryptedContentMetaData.read(it) }
			val (meta, _) = eMeta.decrypt(iden)
			sendToAll(
				JSONObject().put("event", "messages_update").put("action", "add")
					.put("msg", buildMsgJson(eMeta, meta))
			)
		} catch (_: Exception) {
			sendToAll(JSONObject().put("event", "messages_update").put("action", "add"))
		}
		return ok { put("key", encKey.toString()) }
	}

	private fun serveAvatar(header: JSONObject): Content {
		val keyStr = header.getString("key") ?: return err("Missing 'key' parameter")
		val key = Key256(keyStr)
		val svg = getAvatar(key)
		return BinaryContent(svg.toByteArray(Charsets.UTF_8), ext = "svg", path = key.name)
	}

	/** Returns the current list of active TCP connections (both inbound and outbound). */
	private fun connections(): Content {
		val arr = JSONArray()
		p2p.listConnections().forEach { info ->
			val obj = JSONObject()
				.put("host", info.addr.address.hostAddress)
				.put("port", info.addr.port)
				.put("inbound", info.inbound)
				.put("relay", info.isRelay)
			if (info.peerKey != null) {
				obj.put("peerKey", info.peerKey)
				obj.put("peerName", PeerKey(info.peerKey).name)
			}
			arr.put(obj)
		}
		return ok { put("connections", arr) }
	}

	/**
	 * Starts async peer discovery and returns immediately.
	 * Each discovered peer is pushed to all WebSocket clients as:
	 *   { "event": "discover_result", "ips": [...], "port": N, "key": "..." }
	 * When discovery finishes, a final event is sent:
	 *   { "event": "discover_done" }
	 */
	private fun discover(scan: Boolean): Content {
		p2p.discoverAsync(
			scan,
			found = { info ->
				sendToAll(
					JSONObject()
						.put("event", "discover_result")
						.put("ips", JSONArray(info.addrList.map { it.hostAddress }))
						.put("port", info.port)
						.put("key", info.serverKey.toString())
						.put("name", info.serverKey.name)
						.put("relay", info.isRelay)
				)
			},
			done = {
				sendToAll(
					JSONObject().put("event", "discover_done")
				)
			}
		)
		return ok()
	}

	private fun getLocalIPs(): List<String> {
		val ips = mutableListOf<String>()
		try {
			val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
			while (interfaces.hasMoreElements()) {
				val iface = interfaces.nextElement()
				if (iface.isLoopback || !iface.isUp) continue
				val addresses = iface.inetAddresses ?: continue
				while (addresses.hasMoreElements()) {
					val addr = addresses.nextElement()
					if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
					val ip = addr.hostAddress ?: continue
					if (ip.contains("%") || ip.contains(":")) continue
					ips.add(ip)
				}
			}
		} catch (e: Exception) {
			log("Failed to get local IPs: ${e.message}")
		}
		return if (ips.isEmpty()) listOf("127.0.0.1") else ips
	}

	private fun info(header: JSONObject): Content {
		return ok {
			put("peerKey", p2p.serverIdentity.key.toString())
			put("peerName", p2p.serverIdentity.key.name)
			put("defaultP2PPort", p2p.defaultP2PPort)
			put("ips", JSONArray(getLocalIPs()))
		}
	}

	private fun relay(header: JSONObject): Content {
		val hasEnabled = header.has("enabled")
		if (hasEnabled) {
			val enabled = header.getBoolean("enabled")
			p2p.setRelayEnabled(enabled)
			sendToAll(JSONObject().put("event", "node_relay_update").put("relay", p2p.isRelayEnabled()))
		}
		return ok { put("relay", p2p.isRelayEnabled()) }
	}

	private fun status(header: JSONObject): Content {
		val port = p2p.serverPort
		return ok { put("running", port != null); if (port != null) put("port", port) }
	}

	private val settingsFile = File(p2p.graffitiDir, "settings.json")
	private fun saveSetting(key: String, value: String) {
		synchronized(settingsFile) {
			val obj = if (settingsFile.exists()) {
				try {
					JSONObject(settingsFile.readText())
				} catch (e: Exception) {
					JSONObject()
				}
			} else {
				JSONObject()
			}
			obj.put(key, value)
			settingsFile.writeText(obj.toString(2))
		}
	}

	private fun loadSetting(key: String): String {
		return synchronized(settingsFile) {
			if (!settingsFile.exists()) ""
			else {
				try {
					val obj = JSONObject(settingsFile.readText())
					obj.optString(key, "")
				} catch (e: Exception) {
					""
				}
			}
		}
	}

	private fun handleStore(header: JSONObject, content: Content?): Content {
		val method = header.optString("method", "GET")
		val key = header.optString("key", "")
		if (key.isEmpty()) {
			return err("Missing 'key' parameter")
		}
		return if (method == "PUT") {
			val value = content?.readString() ?: ""
			saveSetting(key, value)
			ok()
		} else {
			val value = loadSetting(key)
			ok { put("value", value) }
		}
	}

	override fun handle(header: JSONObject, content: Content?): Content? {
		return try {
			dispatch(header, content)
		} catch (e: Exception) {
			log("API Error in dispatch for ${header.optString("path")}: ${e.message}")
			e.printStackTrace(System.out)
			err(e.message ?: "Unknown error")
		}
	}

	override fun onResponse(header: JSONObject, response: Response) {
		response.addHeader("Cache-Control", "no-store")
	}
}
