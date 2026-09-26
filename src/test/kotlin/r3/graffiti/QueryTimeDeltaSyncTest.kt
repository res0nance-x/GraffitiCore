package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.hash.hash256
import r3.io.serialize
import r3.io.toDataInputStream
import r3.pke.Identity
import r3.pke.PeerKey
import r3.source.readString
import java.io.File
import java.net.ServerSocket
import java.net.Socket

class QueryTimeDeltaSyncTest {

	@Test
	fun testQueryMessageSerialization() {
		val qm1 = QueryMessage(QueryCondition.ALL, QueryCondition.ALL, 123456789L)
		val bytes = qm1.serialize()
		val deserialized = QueryMessage.read(bytes.toDataInputStream())
		assertEquals(123456789L, deserialized.queryTime)

		// Test backward compatibility when queryTime bytes are omitted
		val oldFormatBytes = run {
			val baos = java.io.ByteArrayOutputStream()
			val dos = java.io.DataOutputStream(baos)
			r3.source.StringWritable(QueryMessage.type).write(dos)
			QueryCondition.ALL.write(dos)
			QueryCondition.ALL.write(dos)
			baos.toByteArray()
		}
		val legacyDeserialized = QueryMessage.read(oldFormatBytes.toDataInputStream())
		assertEquals(0L, legacyDeserialized.queryTime)
	}

	@Test
	fun testQueryResponseMessageSerialization() {
		val qrm = QueryResponseMessage(987654321L)
		val bytes = qrm.serialize()
		val deserialized = QueryResponseMessage.read(bytes.toDataInputStream())
		assertEquals(987654321L, deserialized.serverTime)

		// Legacy response without serverTime
		val legacyBytes = r3.source.StringWritable(QueryResponseMessage.type).serialize()
		val legacyDeserialized = QueryResponseMessage.read(legacyBytes.toDataInputStream())
		assertEquals(0L, legacyDeserialized.serverTime)
	}

	@Test
	fun testDeltaSyncAndQueryTimeReset(@TempDir tempDir: File) {
		val serverDir = File(tempDir, "server").also { it.mkdirs() }
		val clientDir = File(tempDir, "client").also { it.mkdirs() }

		val serverP2P = GraffitiP2P(serverDir, relayEnabledAtStartup = true)
		val clientP2P = GraffitiP2P(clientDir)

		val alice = clientP2P.createIdentity("client-alice")
		val bob = serverP2P.createIdentity("server-bob")

		// Create 2 messages on server addressed to alice
		val msg1Key = serverP2P.pkeEncrypt(TestStringContent("Message 1"), bob, alice.asPeer())
		val msg2Key = serverP2P.pkeEncrypt(TestStringContent("Message 2"), bob, alice.asPeer())
		assertTrue(serverP2P.hasContent(msg1Key))
		assertTrue(serverP2P.hasContent(msg2Key))

		val freePort = ServerSocket(0).use { it.localPort }
		serverP2P.startTCPServer(freePort)

		// Client connects to server
		val clientNode = clientP2P.getTCPNode(java.net.InetSocketAddress("127.0.0.1", freePort))

		// Wait for challenge authentication and initial sync
		val deadline1 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline1 && (!clientP2P.hasContent(msg1Key) || !clientP2P.hasContent(msg2Key))) {
			Thread.sleep(50)
		}
		assertTrue(clientP2P.hasContent(msg1Key), "Client should have received message 1")
		assertTrue(clientP2P.hasContent(msg2Key), "Client should have received message 2")

		// Verify client recorded server's query time
		val initialQueryTime = clientP2P.getPeerQueryTime(clientNode)
		assertTrue(initialQueryTime > 0L, "Client should have non-zero query time after initial sync")

		// Simulate subsequent foreground sync with no new messages on server
		clientP2P.syncAllConnectedNodes()
		Thread.sleep(200)

		val secondQueryTime = clientP2P.getPeerQueryTime(clientNode)
		assertTrue(secondQueryTime >= initialQueryTime, "Query time should be maintained or advanced")

		// Now create Message 3 on the server
		val msg3Key = serverP2P.pkeEncrypt(TestStringContent("Message 3"), bob, alice.asPeer())
		assertTrue(serverP2P.hasContent(msg3Key))

		// Client syncs again (delta query with since = secondQueryTime)
		clientP2P.syncAllConnectedNodes()

		val deadline2 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline2 && !clientP2P.hasContent(msg3Key)) {
			Thread.sleep(50)
		}
		assertTrue(clientP2P.hasContent(msg3Key), "Client should receive new message 3 via delta query")

		// Test explicit resetPeerQueryTimes
		clientP2P.resetPeerQueryTimes()
		assertEquals(0L, clientP2P.getPeerQueryTime(clientNode), "resetPeerQueryTimes should reset query time to 0")

		// Re-establish a query time
		clientP2P.syncAllConnectedNodes()
		Thread.sleep(200)
		val queryTimeBeforeNewIdentity = clientP2P.getPeerQueryTime(clientNode)
		assertTrue(queryTimeBeforeNewIdentity > 0L)

		// Create a 4th message on server for a new identity BEFORE client creates it
		val alice2IdentitySeed = "client-alice-2"
		val byteSeed = alice2IdentitySeed.toByteArray().hash256()
		val prospectiveAlice2 = Identity(byteSeed)
		val msg4Key = serverP2P.pkeEncrypt(TestStringContent("Message 4 for Alice 2"), bob, prospectiveAlice2.asPeer())
		assertTrue(serverP2P.hasContent(msg4Key))

		// Client creates the new identity. This should reset query time to 0 and sync, retrieving msg4
		val alice2 = clientP2P.createIdentity(alice2IdentitySeed)
		assertEquals(prospectiveAlice2.key, alice2.key)

		val deadline3 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline3 && !clientP2P.hasContent(msg4Key)) {
			Thread.sleep(50)
		}
		assertTrue(clientP2P.hasContent(msg4Key), "Client should retrieve historical message for new identity after reset")

		// Clean up
		serverP2P.stopTCPServer()
		clientNode.close()
	}
}
