package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.io.log
import r3.pke.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class StructuredLoggingTest {

	@Test
	fun testStructuredLogsDuringP2P(@TempDir dirA: File, @TempDir dirB: File) {
		val logs = CopyOnWriteArrayList<String>()
		val origLog = log
		log = { msg ->
			logs.add(msg)
			origLog(msg)
		}
		try {
			val p2pA = GraffitiP2P(dirA)
			val p2pB = GraffitiP2P(dirB)

			p2pA.startTCPServer(0)
			val portA = p2pA.serverPort!!

			val alice = p2pA.createIdentity("alice-logger")
			val bob = p2pB.createIdentity("bob-logger")

			// Pre-populate peers so auth succeeds and content verification succeeds
			p2pA.savePeer(bob.asPeer())
			p2pB.savePeer(alice.asPeer())

			val encKey = p2pA.pkeEncrypt(TestStringContent("Structured log test payload"), alice, bob.asPeer())

			// Connect Bob -> Alice
			val nodeToA = p2pB.getTCPNode(java.net.InetSocketAddress("127.0.0.1", portA))

			val deadline = System.currentTimeMillis() + 5000L
			while (System.currentTimeMillis() < deadline && !p2pB.hasContent(encKey)) {
				Thread.sleep(100)
			}

			assertTrue(p2pB.hasContent(encKey), "Bob should receive content from Alice")

			// Close node to test disconnected
			nodeToA.close()
			p2pA.stopTCPServer()

			// Let disconnect handlers complete
			Thread.sleep(200)

			// 1. Verify [connected, IP, node alias]
			val connectedLogs = logs.filter { it.startsWith("[connected, ") }
			assertTrue(connectedLogs.isNotEmpty(), "Should have logged [connected, IP, node alias]")
			assertTrue(connectedLogs.any { it.matches(Regex("""\[connected, [0-9a-fA-F.:]+, [^]]+]""")) },
				"Connected logs did not match format: $connectedLogs")

			// 2. Verify [incoming, node alias, content alias, file size]
			val incomingLogs = logs.filter { it.startsWith("[incoming, ") }
			assertTrue(incomingLogs.isNotEmpty(), "Should have logged [incoming, node alias, content alias, file size]")
			assertTrue(incomingLogs.any { it.matches(Regex("""\[incoming, [^,]+, [^,]+, \d+]""")) },
				"Incoming logs did not match format: $incomingLogs")

			// 3. Verify [outgoing, node alias, content alias, file size]
			val outgoingLogs = logs.filter { it.startsWith("[outgoing, ") }
			assertTrue(outgoingLogs.isNotEmpty(), "Should have logged [outgoing, node alias, content alias, file size]")
			assertTrue(outgoingLogs.any { it.matches(Regex("""\[outgoing, [^,]+, [^,]+, \d+]""")) },
				"Outgoing logs did not match format: $outgoingLogs")

			// 4. Verify [disconnected, IP, node alias]
			val disconnectedLogs = logs.filter { it.startsWith("[disconnected, ") }
			assertTrue(disconnectedLogs.isNotEmpty(), "Should have logged [disconnected, IP, node alias]")
			assertTrue(disconnectedLogs.any { it.matches(Regex("""\[disconnected, [0-9a-fA-F.:]+, [^]]+]""")) },
				"Disconnected logs did not match format: $disconnectedLogs")

			// 5. Verify [node alias, error]
			p2pA.logNodeError(nodeToA, "test error message")
			val errorLogs = logs.filter { it.endsWith(", test error message]") }
			assertTrue(errorLogs.isNotEmpty(), "Should have logged [node alias, error]")
			assertTrue(errorLogs.any { it.matches(Regex("""\[[^,]+, test error message]""")) },
				"Error logs did not match format: $errorLogs")

		} finally {
			log = origLog
		}
	}
}
