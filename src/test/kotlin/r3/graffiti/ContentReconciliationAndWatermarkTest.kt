package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.source.readString
import java.io.File
import java.net.ServerSocket

class ContentReconciliationAndWatermarkTest {

	@Test
	fun testReconcileMissingContentOnConnect(@TempDir tempDir: File) {
		val serverDir = File(tempDir, "server").also { it.mkdirs() }
		val clientDir = File(tempDir, "client").also { it.mkdirs() }

		val serverP2P = GraffitiP2P(serverDir, relayEnabledAtStartup = true)
		val clientP2P = GraffitiP2P(clientDir)

		val alice = clientP2P.createIdentity("alice")
		val bob = serverP2P.createIdentity("bob")

		// Create a message on server addressed to alice
		val msgKey = serverP2P.pkeEncrypt(TestStringContent("Hello resilient P2P!"), bob, alice.asPeer())
		assertTrue(serverP2P.hasContent(msgKey))

		// Simulate client having only the metadata file (interrupted transfer or sync drop)
		val serverMetaFile = File(serverP2P.metaDir, "$msgKey")
		val clientMetaFile = File(clientP2P.metaDir, "$msgKey")
		serverMetaFile.copyTo(clientMetaFile, overwrite = true)

		// Restart client P2P to simulate starting with orphan metadata
		val restartedClient = GraffitiP2P(clientDir)
		restartedClient.createIdentity("alice")
		assertTrue(restartedClient.metaDir.resolve("$msgKey").exists(), "Client should have metadata")
		assertFalse(restartedClient.hasContent(msgKey), "Client should NOT yet have content")

		// Start server
		val freePort = ServerSocket(0).use { it.localPort }
		serverP2P.startTCPServer(freePort)

		// Client connects
		restartedClient.getTCPNode(java.net.InetSocketAddress("127.0.0.1", freePort))

		// Wait for reconcileMissingContent to automatically request and receive the missing content
		val deadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline && !restartedClient.hasContent(msgKey)) {
			Thread.sleep(50)
		}

		assertTrue(restartedClient.hasContent(msgKey), "Client should have automatically reconciled and received missing content!")
		val content = restartedClient.getContent(msgKey)
		assertEquals("Hello resilient P2P!", content.readString())
	}

	@Test
	fun testWatermarkDeferredUntilContentReceived(@TempDir tempDir: File) {
		val serverDir = File(tempDir, "server").also { it.mkdirs() }
		val clientDir = File(tempDir, "client").also { it.mkdirs() }

		val serverP2P = GraffitiP2P(serverDir, relayEnabledAtStartup = true)
		val clientP2P = GraffitiP2P(clientDir)

		val alice = clientP2P.createIdentity("alice")
		val bob = serverP2P.createIdentity("bob")

		// Create message on server
		val msgKey = serverP2P.pkeEncrypt(TestStringContent("Message 1"), bob, alice.asPeer())

		val freePort = ServerSocket(0).use { it.localPort }
		serverP2P.startTCPServer(freePort)

		// Client connects
		val clientNode = clientP2P.getTCPNode(java.net.InetSocketAddress("127.0.0.1", freePort))

		// Wait until message arrives and content is stored
		val deadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline && !clientP2P.hasContent(msgKey)) {
			Thread.sleep(50)
		}
		assertTrue(clientP2P.hasContent(msgKey))

		// Once all content is delivered, watermark must be committed
		val queryTime = clientP2P.getPeerQueryTime(clientNode)
		assertTrue(queryTime > 0L, "Query time should be committed once in-flight batch completes")

		serverP2P.stopTCPServer()
		clientNode.close()
	}

	@Test
	fun testResumablePartialFileTransfer(@TempDir tempDir: File) {
		val serverDir = File(tempDir, "server").also { it.mkdirs() }
		val clientDir = File(tempDir, "client").also { it.mkdirs() }

		val serverP2P = GraffitiP2P(serverDir, relayEnabledAtStartup = true)
		val clientP2P = GraffitiP2P(clientDir)

		val alice = clientP2P.createIdentity("alice")
		val bob = serverP2P.createIdentity("bob")

		// 50 KB text payload
		val largeText = "Resumable chunk transfer resiliency test payload: ".repeat(1200)
		val msgKey = serverP2P.pkeEncrypt(TestStringContent(largeText), bob, alice.asPeer())
		assertTrue(serverP2P.hasContent(msgKey))

		val serverContentFile = File(serverP2P.contentDir, "$msgKey")
		val totalLength = serverContentFile.length()
		assertTrue(totalLength > 30_000L, "Payload should be large (> 30KB)")

		// Copy metadata to client, simulating metadata received before transfer drop
		File(serverP2P.metaDir, "$msgKey").copyTo(File(clientP2P.metaDir, "$msgKey"), overwrite = true)

		// Simulate partial .part file on client (first 15,000 bytes already downloaded)
		val partBytesLength = 15_000
		val partFile = File(clientP2P.tmpDir, "$msgKey.part")
		serverContentFile.inputStream().use { inp ->
			val buf = ByteArray(partBytesLength)
			var totalRead = 0
			while (totalRead < partBytesLength) {
				val r = inp.read(buf, totalRead, partBytesLength - totalRead)
				if (r <= 0) break
				totalRead += r
			}
			partFile.writeBytes(buf.copyOf(totalRead))
		}
		assertEquals(15_000L, partFile.length(), "Simulated partial file should have exactly 15,000 bytes")

		// Start server and connect client
		val freePort = ServerSocket(0).use { it.localPort }
		serverP2P.startTCPServer(freePort)

		// Restart client P2P to simulate resumption on reconnect
		val restartedClient = GraffitiP2P(clientDir)
		restartedClient.createIdentity("alice")
		assertFalse(restartedClient.hasContent(msgKey), "Client should not have completed content yet")
		assertTrue(File(restartedClient.tmpDir, "$msgKey.part").exists(), ".part file should persist across restart")

		val clientNode = restartedClient.getTCPNode(java.net.InetSocketAddress("127.0.0.1", freePort))

		// Wait for transfer to resume from offset 15,000 and finish
		val deadline = System.currentTimeMillis() + 6000L
		while (System.currentTimeMillis() < deadline && !restartedClient.hasContent(msgKey)) {
			Thread.sleep(50)
		}

		assertTrue(restartedClient.hasContent(msgKey), "Client should have resumed and completed the partial transfer!")
		val clientFile = File(restartedClient.contentDir, "$msgKey")
		val receivedContent = restartedClient.getContent(msgKey)
		assertEquals(largeText, receivedContent.readString(), "Received payload should match original text after resumption!")
		assertFalse(File(restartedClient.tmpDir, "$msgKey.part").exists(), ".part file should have been moved to contentDir")
		assertEquals(totalLength, clientFile.length(), "Finished file size should match full server file")

		serverP2P.stopTCPServer()
		clientNode.close()
	}
}
