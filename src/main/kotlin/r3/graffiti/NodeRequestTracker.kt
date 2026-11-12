package r3.graffiti

import r3.net.tcp.TCPNode
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared request-response correlation tracker for synchronous RPC calls over [TCPNode]
 * (used by both Portal and Bridge discovery requests).
 *
 * Automatically unblocks waiting callers with their fallback value if the target node
 * disconnects or if the timeout elapses.
 */
class NodeRequestTracker {
	private val requestCounter = AtomicLong(1L)
	private val pendingRequests = ConcurrentHashMap<Long, Pair<TCPNode, CompletableFuture<Any?>>>()

	fun <T> request(
		node: TCPNode,
		timeoutMs: Long,
		defaultValue: T,
		onError: ((Exception) -> Unit)? = null,
		send: (requestId: Long) -> Unit
	): T {
		if (node.isClosed()) return defaultValue
		val reqId = requestCounter.getAndIncrement()
		val future = CompletableFuture<Any?>()
		pendingRequests[reqId] = Pair(node, future)
		return try {
			send(reqId)
			@Suppress("UNCHECKED_CAST")
			future.get(timeoutMs, TimeUnit.MILLISECONDS) as T
		} catch (e: Exception) {
			if (e !is CancellationException && e.cause !is CancellationException) {
				onError?.invoke(e)
			}
			defaultValue
		} finally {
			pendingRequests.remove(reqId)
		}
	}

	fun complete(requestId: Long, value: Any?) {
		pendingRequests.remove(requestId)?.second?.complete(value)
	}

	fun cancelForNode(node: TCPNode) {
		pendingRequests.entries.removeIf { (_, pair) ->
			if (pair.first == node) {
				pair.second.completeExceptionally(CancellationException("Node disconnected"))
				true
			} else false
		}
	}
}
