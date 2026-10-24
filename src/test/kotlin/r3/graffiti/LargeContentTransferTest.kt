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

class LargeContentTransferTest {

	@Test
	fun testLargeFileSendAndOnDemandDownload(@TempDir dirA: File, @TempDir dirB: File) {
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)
		// Bob only auto-downloads content <= 1 KB; larger files only fetch metadata
		p2pB.autoDownloadThresholdBytes = 1024L

		val apiA = GraffitiAPI(p2pA) {}
		val apiB = GraffitiAPI(p2pB) {}

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

		// Alice sends a 50 KB video file as a normal message
		val originalBytes = "Big binary video stream test content: ".repeat(1500).toByteArray()
		val uploadFile = File(dirA, "holiday_movie.mp4").apply { writeBytes(originalBytes) }

		val sendHeader = JSONObject().apply {
			put("path", "/api/message/send/file")
			put("identityKey", alice.key.toString())
			put("peerKey", bob.asPeer().key.toString())
			put("file", "holiday_movie.mp4")
			put("async", "true")
		}

		val sendResp = apiA.handle(sendHeader, FileContent(uploadFile))
		assertNotNull(sendResp)
		val sendJson = JSONObject(sendResp!!.readString())
		assertTrue(sendJson.getBoolean("ok"))

		// Wait for command processing to finish on Alice
		val cmdDeadline = System.currentTimeMillis() + 10000L
		while (System.currentTimeMillis() < cmdDeadline && apiA.commandQueue.isBusy()) {
			Thread.sleep(50)
		}

		// Alice has the content locally as regular content
		val aliceMessages = p2pA.metaDir.listFiles { f -> f.isFile } ?: emptyArray()
		assertTrue(aliceMessages.isNotEmpty(), "Alice should have created message metadata")
		val msgKey = EncryptedMetaKey(aliceMessages[0].name)
		assertTrue(p2pA.hasContent(msgKey), "Alice should have local content")

		// Push to Bob
		p2pA.pushNewMessage(msgKey)

		// Wait for Bob to receive metadata only (since 50 KB > Bob's 1 KB threshold)
		val syncDeadline = System.currentTimeMillis() + 10000L
		var bobReceivedMeta = false
		while (System.currentTimeMillis() < syncDeadline && !bobReceivedMeta) {
			if (File(p2pB.metaDir, msgKey.toString()).exists()) {
				bobReceivedMeta = true
			} else {
				Thread.sleep(50)
			}
		}
		assertTrue(bobReceivedMeta, "Bob should have received metadata for the large file")

		// Bob has the metadata, can decrypt filename and size, but does NOT yet have the content
		assertFalse(p2pB.hasContent(msgKey), "Bob should NOT have downloaded content automatically")
		val bobMetaPair = p2pB.getDecryptedMeta(msgKey)
		assertNotNull(bobMetaPair, "Bob should be able to decrypt metadata")
		assertEquals("holiday_movie.mp4", bobMetaPair!!.first.name)
		assertEquals("mp4", bobMetaPair.first.type)
		assertEquals(originalBytes.size.toLong(), bobMetaPair.first.length)

		// List messages on Bob should include the message with hasContent = false
		val listResp = apiB.handle(JSONObject().apply { put("path", "/api/messages") }, null)
		assertNotNull(listResp)
		val listJson = JSONObject(listResp!!.readString())
		val msgsArr = listJson.getJSONArray("messages")
		assertTrue(msgsArr.length() > 0)
		val bobMsgObj = msgsArr.getJSONObject(0)
		assertFalse(bobMsgObj.getBoolean("hasContent"), "hasContent should be false in message listing")

		// Now Bob requests download
		val dlHeader = JSONObject().apply {
			put("path", "/api/message/download")
			put("key", msgKey.toString())
		}
		val dlResp = apiB.handle(dlHeader, null)
		assertNotNull(dlResp)
		val dlJson = JSONObject(dlResp!!.readString())
		assertTrue(dlJson.getBoolean("ok"), "Download request should succeed")

		// Wait for Bob to receive the content
		val dlDeadline = System.currentTimeMillis() + 10000L
		while (System.currentTimeMillis() < dlDeadline && !p2pB.hasContent(msgKey)) {
			Thread.sleep(50)
		}
		assertTrue(p2pB.hasContent(msgKey), "Bob should now have content after manual download")

		// Verify decrypted content on Bob matches original bytes
		val bobContentStream = p2pB.getContent(msgKey).createInputStream()
		val receivedBytes = bobContentStream.use { it.readBytes() }
		assertArrayEquals(originalBytes, receivedBytes, "Downloaded content must match original bytes")

		// Test deletion: deleting removes both meta and content
		assertTrue(p2pB.deleteMessage(msgKey), "Bob should be able to delete message")
		assertFalse(p2pB.hasContent(msgKey), "Content should be deleted")

		nodeToA.close()
		p2pA.stopTCPServer()
	}

	@Test
	fun testRelaySizeThresholdFiltering(@TempDir dirSender: File, @TempDir dirRelay: File) {
		val p2pSender = GraffitiP2P(dirSender)
		val p2pRelay = GraffitiP2P(dirRelay, relayEnabledAtStartup = true)
		// Relay has a strict size limit of 1000 bytes
		p2pRelay.relayMaxContentSizeBytes = 1000L

		p2pSender.startTCPServer(0)
		val senderPort = p2pSender.serverPort!!

		val alice = p2pSender.createIdentity("alice-sender")
		val someone = p2pSender.createIdentity("someone-dest")

		// Connect Relay -> Sender
		val nodeToSender = p2pRelay.getTCPNode(InetSocketAddress("127.0.0.1", senderPort))
		val connectDeadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < connectDeadline && !p2pRelay.isConnectedToPeer(p2pSender.serverIdentity.asPeer().key)) {
			Thread.sleep(50)
		}

		// Sender creates a 10 KB file (exceeds relay's 1000 byte limit)
		val bigData = ByteArray(10_000) { 1 }
		val bigFile = File(dirSender, "too_big.dat").apply { writeBytes(bigData) }
		val apiSender = GraffitiAPI(p2pSender) {}

		val sendHeader = JSONObject().apply {
			put("path", "/api/message/send/file")
			put("sender", alice.key.toString())
			put("recipient", someone.key.toString())
			put("file", "too_big.dat")
		}
		apiSender.handle(sendHeader, FileContent(bigFile))

		// Wait for command processing
		while (apiSender.commandQueue.isBusy()) {
			Thread.sleep(50)
		}

		val senderMeta = p2pSender.metaDir.listFiles { f -> f.isFile }?.firstOrNull()
		assertNotNull(senderMeta)
		val msgKey = EncryptedMetaKey(senderMeta!!.name)

		p2pSender.pushNewMessage(msgKey)
		Thread.sleep(1000)

		// Relay should have ignored this message completely because it exceeds 1000 bytes
		assertFalse(File(p2pRelay.metaDir, msgKey.toString()).exists(), "Relay must ignore messages exceeding its size threshold")
		assertFalse(p2pRelay.hasContent(msgKey), "Relay must not store content exceeding its size threshold")

		nodeToSender.close()
		p2pSender.stopTCPServer()
	}
}
