package r3.graffiti

import r3.org.json.JSONArray
import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import r3.pke.name
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StateManager(
	val p2p: GraffitiP2P,
	val onStateChanged: (Long) -> Unit
) {
	private val version = AtomicLong(1L)
	private val nodesVersion = AtomicLong(1L)
	private val identitiesVersion = AtomicLong(1L)
	private val peersVersion = AtomicLong(1L)
	private val messagesVersion = AtomicLong(1L)

	private val isEncoding = AtomicBoolean(false)
	var activeCommandsSupplier: (() -> List<Command>)? = null

	fun currentVersion(): Long = version.get()

	fun setEncoding(busy: Boolean) {
		if (isEncoding.getAndSet(busy) != busy) {
			notifyChanged()
		}
	}

	fun isEncoding(): Boolean = isEncoding.get() || (activeCommandsSupplier?.invoke()?.isNotEmpty() == true)

	fun onTransferChanged() {
		notifyChanged()
	}

	fun onNodesChanged() {
		nodesVersion.incrementAndGet()
		notifyChanged()
	}

	fun onIdentitiesChanged() {
		identitiesVersion.incrementAndGet()
		notifyChanged()
	}

	fun onPeersChanged() {
		peersVersion.incrementAndGet()
		notifyChanged()
	}

	fun onMessagesChanged() {
		messagesVersion.incrementAndGet()
		notifyChanged()
	}

	fun onRelayChanged() {
		notifyChanged()
	}

	fun onWhitelistChanged() {
		notifyChanged()
	}

	fun onServerStatusChanged() {
		notifyChanged()
	}

	private fun notifyChanged() {
		val v = version.incrementAndGet()
		try {
			onStateChanged(v)
		} catch (_: Exception) {}
	}

	fun getMessageKeysInTimeOrder(): List<String> {
		val files = p2p.metaDir.listFiles { f -> f.isFile }.orEmpty()
		return files
			.filter {
				try {
					p2p.hasContent(EncryptedMetaKey(it.name))
				} catch (_: Exception) {
					false
				}
			}
			.sortedBy { it.lastModified() }
			.map { it.name }
	}

	fun getStateJson(): JSONObject {
		val v = version.get()
		val root = JSONObject()
		root.put("ok", true)
		root.put("version", v)
		root.put("transferring", p2p.isAnyTransferActive())
		root.put("encoding", isEncoding())
		val activeCmdsArr = JSONArray()
		activeCommandsSupplier?.invoke().orEmpty().forEach { activeCmdsArr.put(it.toJson()) }
		root.put("activeCommands", activeCmdsArr)

		// Nodes
		root.put("nodesVersion", nodesVersion.get())
		val nodesArr = JSONArray()
		p2p.listDetailedConnections().forEach { node ->
			val nObj = JSONObject()
				.put("host", node.addr.address.hostAddress)
				.put("port", node.addr.port)
				.put("inbound", node.inbound)
				.put("relay", node.isRelay)
				.put("isTransferring", node.isTransferring)
				.put("isSending", node.isSending)
				.put("isReceiving", node.isReceiving)
			if (node.peerKey != null) {
				nObj.put("peerKey", node.peerKey)
				nObj.put("peerName", node.peerName ?: "")
			}
			nodesArr.put(nObj)
		}
		root.put("nodes", nodesArr)

		// Identities
		root.put("identitiesVersion", identitiesVersion.get())
		val idenArr = JSONArray()
		p2p.listIdentities().forEach { iden ->
			idenArr.put(
				JSONObject()
					.put("name", iden.key.name)
					.put("key", iden.key.toString())
					.put("peerKey", iden.asPeer().key.toString())
					.put("persistent", p2p.isIdentityPersistent(iden.key))
			)
		}
		root.put("identities", idenArr)

		// Peers
		root.put("peersVersion", peersVersion.get())
		val peerArr = JSONArray()
		p2p.listPeers().forEach { peer ->
			peerArr.put(
				JSONObject()
					.put("name", peer.key.name)
					.put("key", peer.key.toString())
			)
		}
		root.put("peers", peerArr)

		// Messages
		root.put("messagesVersion", messagesVersion.get())
		val msgKeysArr = JSONArray()
		getMessageKeysInTimeOrder().forEach { msgKeysArr.put(it) }
		root.put("messageKeys", msgKeysArr)

		// Server info & flags
		root.put("relay", p2p.isRelayEnabled())
		root.put("whitelist", p2p.isWhitelistEnabled())
		val serverPort = p2p.serverPort
		root.put("server", JSONObject()
			.put("running", serverPort != null)
			.put("port", serverPort ?: 0)
		)

		return root
	}
}
