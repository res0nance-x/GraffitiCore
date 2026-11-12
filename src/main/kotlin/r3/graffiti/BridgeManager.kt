package r3.graffiti

import r3.io. closeAll
import r3.io.debug
import r3.io.log
import r3.io.serialize
import r3.io.toDataInputStream
import r3.net.discover.PeerAddressInfo
import r3.net.tcp.BridgeTCPNode
import r3.net.tcp.TCPBlock
import r3.net.tcp.TCPNode
import r3.pke.PeerKey
import r3.pke.ServerKey
import r3.pke.name
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages Bridge peer discovery, negative virtual port assignment, block routing,
 * and virtual [BridgeTCPNode] tunnel lifecycles for [GraffitiP2P].
 */
class BridgeManager(
	private val p2p: GraffitiP2P,
	private val requestTracker: NodeRequestTracker
) {
	private val nextBridgeVirtualPort = AtomicInteger(-1)
	private val peerToBridgeVirtualPort = ConcurrentHashMap<PeerKey, Int>()

	/** Maps (bridgeHostIp, virtualPort) -> (bridgeCarrierNode, targetPeerKey) discovered via BridgeDiscovery. */
	private val bridgeDirectory = ConcurrentHashMap<Pair<String, Int>, Pair<TCPNode, PeerKey>>()

	/** Maps (bridgeCarrierNode, remotePeerKey) -> active virtual BridgeTCPNode. */
	private val bridgeNodes = ConcurrentHashMap<Pair<TCPNode, PeerKey>, BridgeTCPNode>()

	/** Tracks whether each active virtual BridgeTCPNode was initiated inbound (from remote peer) or outbound. */
	private val bridgeNodeInboundMap = ConcurrentHashMap<BridgeTCPNode, Boolean>()

	/** On a Bridge server, tracks which PeerKeys have active bridged sessions with each other for disconnect cleanup. */
	private val activeBridgePairs = ConcurrentHashMap<PeerKey, MutableSet<PeerKey>>()

	fun getOrAssignVirtualPort(peerKey: PeerKey): Int {
		return peerToBridgeVirtualPort.computeIfAbsent(peerKey) {
			nextBridgeVirtualPort.getAndDecrement()
		}
	}

	fun virtualNodes(): List<BridgeTCPNode> = bridgeNodes.values.toList()

	fun isInbound(vNode: BridgeTCPNode): Boolean = bridgeNodeInboundMap[vNode] ?: false

	fun removeNode(node: TCPNode): Boolean = bridgeNodes.entries.removeIf { it.value == node }

	fun findConnectedVirtualNode(normalizedHost: String, port: Int): BridgeTCPNode? {
		return bridgeNodes.values.firstOrNull { node ->
			!node.isClosed() && node.remotePort == port &&
				(node.remoteAddress.address?.hostAddress == normalizedHost || node.remoteAddress.hostString == normalizedHost)
		}
	}

	fun connectBridgedPeer(host: String, virtualPort: Int, autoSync: Boolean = true): BridgeTCPNode {
		require(virtualPort < 0) { "Virtual bridge port must be negative, got $virtualPort" }
		val resolvedIp = try {
			InetAddress.getByName(host.trim()).hostAddress ?: host.trim()
		} catch (_: Exception) {
			host.trim()
		}
		var mapping = bridgeDirectory[Pair(resolvedIp, virtualPort)] ?: bridgeDirectory[Pair(host.trim(), virtualPort)]
		if (mapping == null || mapping.first.isClosed()) {
			val candidateBridge = p2p.directNodes.firstOrNull { n ->
				!n.isClosed() && p2p.isNodeBridge(n) &&
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
			InetAddress.getByName(host.trim()).hostAddress ?: host.trim()
		} catch (_: Exception) {
			host.trim()
		}
		bridgeNodes.values.filter { vNode ->
			!vNode.isClosed() && vNode.remotePort == virtualPort &&
				(vNode.remoteAddress.address?.hostAddress == resolvedIp || vNode.remoteAddress.hostString == host.trim())
		}.forEach { it.close() }
	}

	fun getOrCreateBridgeNode(
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
		val resolvedPort = if (virtualPort < 0) virtualPort else getOrAssignVirtualPort(remotePeerKey)
		val bridgeHost = carrierNode.remoteAddress.address?.hostAddress ?: carrierNode.remoteAddress.hostString
		bridgeDirectory[Pair(bridgeHost, resolvedPort)] = Pair(carrierNode, remotePeerKey)

		val newNode = BridgeTCPNode(
			carrierNode = carrierNode,
			remotePeerKeyBytes = remotePeerKey.arr.copyOf(),
			blockHandler = p2p.blockHandler,
			virtualPort = resolvedPort
		)
		var created = false
		val actualNode = bridgeNodes.compute(key) { _, cur ->
			if (cur != null && !cur.isClosed()) {
				cur
			} else {
				created = true
				newNode
			}
		}!!
		if (!created) return actualNode

		bridgeNodeInboundMap[actualNode] = inbound
		actualNode.onTransferStateChanged = {
			p2p.checkAndNotifyTransferState()
		}
		actualNode.onClose = {
			val alias = p2p.getNodePeer(actualNode)?.key?.name ?: remotePeerKey.name
			p2p.logDisconnected(actualNode, alias)
			bridgeNodes.remove(key, actualNode)
			bridgeNodeInboundMap.remove(actualNode)
			p2p.onVirtualNodeClosed(actualNode)
		}
		p2p.onVirtualNodeCreated(actualNode, inbound, autoSync)
		return actualNode
	}

	fun handleBridgeRawBlock(carrierNode: TCPNode, block: TCPBlock) {
		try {
			when (block.id) {
				TCPBlock.BRIDGE_TO_PEER_ID,
				TCPBlock.BRIDGE_TO_PEER_PRIORITY_ID,
				TCPBlock.BRIDGE_TO_PEER_CLOSE_ID -> {
					val targetKeyBytes = BridgeTCPNode.extractPeerKeyBytes(block) ?: return
					if (!p2p.isBridgeEnabled()) {
						if (block.id != TCPBlock.BRIDGE_TO_PEER_CLOSE_ID) {
							try {
								carrierNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(targetKeyBytes))
							} catch (_: Exception) {}
						}
						return
					}
					val senderPeer = p2p.getNodePeer(carrierNode) ?: return
					val targetPeerKey = PeerKey(targetKeyBytes)
					val targetNode = p2p.getDirectNodeForPeer(targetPeerKey)
					val senderVirtualPort = getOrAssignVirtualPort(senderPeer.key)
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
						val targetVirtualPort = getOrAssignVirtualPort(targetPeerKey)
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
			p2p.logNodeError(carrierNode, "Error handling bridge block: ${e.message}")
		}
	}

	fun handleListRequest(node: TCPNode, rawHead: ByteArray) {
		val req = BridgeListRequestMessage.read(rawHead.toDataInputStream())
		val entries = if (p2p.isBridgeEnabled() && node !is BridgeTCPNode) {
			p2p.directNodes
				.filter { it != node && !it.isClosed() }
				.mapNotNull { n ->
					val peer = p2p.getNodePeer(n) ?: return@mapNotNull null
					val vPort = getOrAssignVirtualPort(peer.key)
					BridgePeerEntry(peer.key, vPort)
				}
		} else {
			emptyList()
		}
		try {
			node.sendPriority(BridgeListResponseMessage(req.requestId, entries).serialize())
			debug("Served BridgeListResponse (${entries.size} peers) to ${node.remoteAddress}")
		} catch (e: Exception) {
			p2p.logNodeError(node, "Error serving BridgeListRequest: ${e.message}")
		}
	}

	fun handleListResponse(node: TCPNode, rawHead: ByteArray) {
		val resp = BridgeListResponseMessage.read(rawHead.toDataInputStream())
		val bridgeHost = node.remoteAddress.address?.hostAddress ?: node.remoteAddress.hostString
		resp.entries.forEach { entry ->
			if (entry.virtualPort < 0 && entry.peerKey.arr.size == 32) {
				bridgeDirectory[Pair(bridgeHost, entry.virtualPort)] = Pair(node, entry.peerKey)
			}
		}
		requestTracker.complete(resp.requestId, resp.entries)
	}

	fun requestBridgePeerList(bridgeNode: TCPNode, timeoutMs: Long = 3_000L): List<BridgePeerEntry> {
		return requestTracker.request(
			node = bridgeNode,
			timeoutMs = timeoutMs,
			defaultValue = emptyList(),
			onError = { e -> debug("BridgeListRequest to ${bridgeNode.remoteAddress} timed out or failed: ${e.message}") }
		) { reqId ->
			bridgeNode.sendPriority(BridgeListRequestMessage(reqId).serialize())
		}
	}

	fun discoverBridgedPeers(found: (PeerAddressInfo) -> Unit) {
		val bridges = p2p.directNodes.filter { !it.isClosed() && p2p.isNodeBridge(it) }
		for (bridgeNode in bridges) {
			val entries = requestBridgePeerList(bridgeNode)
			val bridgeAddr = bridgeNode.remoteAddress.address ?: continue
			for (entry in entries) {
				if (entry.peerKey.arr.contentEquals(p2p.serverIdentity.key.arr)) continue
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

	fun onBridgeDisabled() {
		val snapshot = activeBridgePairs.entries.map { it.key to it.value.toSet() }
		activeBridgePairs.clear()
		for ((peerA, partners) in snapshot) {
			val nodeA = p2p.getDirectNodeForPeer(peerA) ?: continue
			val vPortA = peerToBridgeVirtualPort[peerA] ?: -1
			for (peerB in partners) {
				try {
					nodeA.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(peerB.arr, vPortA))
				} catch (_: Exception) {}
			}
		}
	}

	fun onDirectNodeClosed(node: TCPNode, disconnectedPeerKey: PeerKey?) {
		val deadVirtualNodes = bridgeNodes.entries
			.filter { it.key.first == node }
			.map { it.value }
		deadVirtualNodes.forEach { vNode ->
			vNode.closeFromRemote()
		}
		bridgeDirectory.entries.removeIf { it.value.first == node }

		if (disconnectedPeerKey != null) {
			val partners = activeBridgePairs.remove(disconnectedPeerKey).orEmpty()
			val vPort = peerToBridgeVirtualPort[disconnectedPeerKey] ?: -1
			for (partnerKey in partners) {
				activeBridgePairs[partnerKey]?.remove(disconnectedPeerKey)
				val partnerNode = p2p.getDirectNodeForPeer(partnerKey)
				if (partnerNode != null && !partnerNode.isClosed()) {
					try {
						partnerNode.sendPriority(BridgeTCPNode.createCloseFromPeerBlock(disconnectedPeerKey.arr, vPort))
					} catch (_: Exception) {}
				}
			}
		}
	}

	fun closeAllVirtualNodes() {
		val virtualNodes = bridgeNodes.values.toList()
		bridgeNodes.clear()
		closeAll(virtualNodes)
	}
}
