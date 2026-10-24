package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.FileContent
import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import r3.source.readString
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class P2PLinkTransferTest {

	@Test
	fun testP2PLinkCreationAndCleanup(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}
		val alice = p2p.createIdentity("alice-seed")
		val bob = p2p.createIdentity("bob-seed")
		p2p.savePeer(bob.asPeer())

		// Create a sample file to send as p2plink
		val sampleData = "Sample data for P2P link test: ".repeat(100).toByteArray()
		val sampleFile = File(tempDir, "sample_video.mp4").apply { writeBytes(sampleData) }

		val header = JSONObject().apply {
			put("path", "/api/message/send/p2plink")
			put("sender", alice.key.toString())
			put("recipient", bob.key.toString())
			put("file", "sample_video.mp4")
		}

		val resp = api.handle(header, FileContent(sampleFile))
		assertNotNull(resp)
		val jsonResp = JSONObject(resp!!.readString())
		assertTrue(jsonResp.getBoolean("ok"))
		val transferId = jsonResp.getString("transferId")

		// Wait for command processing to finish
		val deadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline && api.commandQueue.isBusy()) {
			Thread.sleep(50)
		}

		// Backing file should exist in p2pLinkDir
		val backingFile = File(p2p.p2pLinkDir, transferId)
		assertTrue(backingFile.exists(), "Backing file should exist in p2pLinkDir")
		assertTrue(backingFile.length() > 0, "Backing file should not be empty")

		// The staged upload temp file should be deleted
		val tmpFiles = p2p.tmpDir.listFiles { f -> f.name.startsWith("upload_p2plink_") } ?: emptyArray()
		assertTrue(tmpFiles.isEmpty(), "Staged upload temp file should be cleaned up")

		// Find the generated message in p2p
		val messages = p2p.metaDir.listFiles { f -> f.isFile } ?: emptyArray()
		assertTrue(messages.isNotEmpty(), "P2P message should have been created")

		val msgKey = EncryptedMetaKey(messages[0].name)
		val decrypted = p2p.getDecryptedMeta(msgKey)
		assertNotNull(decrypted)
		assertEquals("p2plink", decrypted!!.first.type)

		// Now test deleting the message: backing file in p2pLinkDir should be deleted!
		val deleted = p2p.deleteMessage(msgKey)
		assertTrue(deleted, "Message should be deleted")
		assertFalse(backingFile.exists(), "Backing file in p2pLinkDir should be deleted when message is deleted")
	}

	@Test
	fun testP2PLinkTransferBetweenPeers(@TempDir dirA: File, @TempDir dirB: File) {
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)
		val apiA = GraffitiAPI(p2pA) {}

		p2pA.startTCPServer(0)
		val portA = p2pA.serverPort!!

		val alice = p2pA.createIdentity("alice-sender")
		val bob = p2pB.createIdentity("bob-receiver")

		p2pA.savePeer(bob.asPeer())
		p2pA.savePeer(p2pB.serverIdentity.asPeer())
		p2pB.savePeer(alice.asPeer())
		p2pB.savePeer(p2pA.serverIdentity.asPeer())

		// Connect Bob -> Alice
		val nodeToA = p2pB.getTCPNode(InetSocketAddress("127.0.0.1", portA))
		val connectDeadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < connectDeadline && !p2pB.isConnectedToPeer(p2pA.serverIdentity.asPeer().key)) {
			Thread.sleep(50)
		}
		assertTrue(p2pB.isConnectedToPeer(p2pA.serverIdentity.asPeer().key), "Bob should be connected to Alice")

		// Alice creates a P2P Link message
		val originalBytes = "Big binary video stream test content: ".repeat(2000).toByteArray()
		val uploadFile = File(dirA, "holiday_movie.mkv").apply { writeBytes(originalBytes) }

		val sendHeader = JSONObject().apply {
			put("path", "/api/message/send/p2plink")
			put("sender", alice.key.toString())
			put("recipient", bob.key.toString())
			put("file", "holiday_movie.mkv")
		}

		val sendResp = apiA.handle(sendHeader, FileContent(uploadFile))
		assertNotNull(sendResp)
		val sendJson = JSONObject(sendResp!!.readString())
		assertTrue(sendJson.getBoolean("ok"))
		val transferId = sendJson.getString("transferId")

		// Wait for command processing to finish on Alice
		val cmdDeadline = System.currentTimeMillis() + 10000L
		while (System.currentTimeMillis() < cmdDeadline && apiA.commandQueue.isBusy()) {
			Thread.sleep(50)
		}

		// Wait for the descriptor message to arrive at Bob
		val syncDeadline = System.currentTimeMillis() + 15000L
		var bobMsgKey: EncryptedMetaKey? = null
		while (System.currentTimeMillis() < syncDeadline && bobMsgKey == null) {
			val metaFiles = p2pB.metaDir.listFiles { f -> f.isFile } ?: emptyArray()
			if (metaFiles.isNotEmpty()) {
				val k = EncryptedMetaKey(metaFiles[0].name)
				if (p2pB.hasContent(k)) {
					bobMsgKey = k
				}
			}
			if (bobMsgKey == null) Thread.sleep(50)
		}

		assertNotNull(bobMsgKey, "Bob should receive the p2plink descriptor message")
		val metaPair = p2pB.getDecryptedMeta(bobMsgKey!!)
		assertNotNull(metaPair)
		assertEquals("p2plink", metaPair!!.first.type)

		// Destination directory for download on Bob
		val bobDownloads = File(dirB, "Downloads").apply { mkdirs() }
		p2pB.defaultDownloadsDir = bobDownloads

		// Trigger download via API on Bob
		val downloadLatch = CountDownLatch(1)
		var completedPath: String? = null
		var downloadError: String? = null

		// Custom sendToAll listener to capture the event
		val customApiB = GraffitiAPI(p2pB) { eventObj ->
			val ev = eventObj.optString("event")
			if (ev == "p2plink_complete") {
				completedPath = eventObj.optString("path")
				downloadLatch.countDown()
			} else if (ev == "p2plink_error") {
				downloadError = eventObj.optString("error")
				downloadLatch.countDown()
			}
		}

		val dlHeader = JSONObject().apply {
			put("path", "/api/p2plink/download")
			put("key", bobMsgKey.toString())
			put("destinationDir", bobDownloads.absolutePath)
		}

		val dlResp = customApiB.handle(dlHeader, null)
		assertNotNull(dlResp)
		val dlJson = JSONObject(dlResp!!.readString())
		assertTrue(dlJson.getBoolean("ok"), "Download request should return ok")

		// Wait for on-demand P2P stream to complete
		val finished = downloadLatch.await(10, TimeUnit.SECONDS)
		assertTrue(finished, "Download should complete within timeout")
		assertNull(downloadError, "Download should not have errors: $downloadError")
		assertNotNull(completedPath, "Completed path should be reported")

		val downloadedFile = File(completedPath!!)
		assertTrue(downloadedFile.exists(), "Downloaded file should exist on disk")
		assertArrayEquals(originalBytes, downloadedFile.readBytes(), "Downloaded bytes must exactly match original")

		nodeToA.close()
		p2pA.stopTCPServer()
	}
}
