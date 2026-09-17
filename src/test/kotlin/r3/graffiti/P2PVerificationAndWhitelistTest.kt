package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.BinaryContent
import r3.hash.hash256
import r3.io.serialize
import r3.io.toDataInputStream
import r3.org.json.JSONObject
import r3.pke.*
import r3.source.BinarySource
import r3.source.readString
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

class P2PVerificationAndWhitelistTest {

	@Test
	fun testIdentityToPeerAndExport(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		// 1. Create a new identity
		val iden = p2p.createIdentity("test-seed-alice")
		assertNotNull(iden)

		// 2. Verify it is NOT automatically in peerCache
		assertNull(p2p.getPeerByKey(PeerKey(iden.key.arr)))
		assertTrue(p2p.listPeers().none { it.key == PeerKey(iden.key.arr) })

		// 3. Convert identity to peer via API
		val toPeerHeader = JSONObject()
			.put("path", "/api/identity/to-peer")
			.put("key", iden.key.toString())
		val toPeerRes = api.handle(toPeerHeader, null)
		assertNotNull(toPeerRes)
		val toPeerJson = JSONObject(toPeerRes!!.readString())
		assertTrue(toPeerJson.getBoolean("ok"))

		// 4. Verify it is now recognized in peerCache
		val resolvedPeer = p2p.getPeerByKey(PeerKey(iden.key.arr))
		assertNotNull(resolvedPeer)
		assertEquals(iden.asPeer().key, resolvedPeer!!.key)
		assertTrue(p2p.listPeers().any { it.key == PeerKey(iden.key.arr) })

		// 5. Test export of the identity as a peer
		val exportHeader = JSONObject()
			.put("path", "/api/peer/export")
			.put("key", iden.key.toString())
		val exportRes = api.handle(exportHeader, null)
		assertNotNull(exportRes)
		val importedFromExport = Peer.read(DataInputStream(exportRes!!.createInputStream()))
		assertEquals(iden.asPeer().key, importedFromExport.key)
	}

	@Test
	fun testWhitelistToggleLifecycle(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		// Default is false
		assertFalse(p2p.isWhitelistEnabled())

		// Toggle on via API
		val enableHeader = JSONObject()
			.put("path", "/api/whitelist")
			.put("enabled", true)
		val enableRes = api.handle(enableHeader, null)
		assertNotNull(enableRes)
		val enableJson = JSONObject(enableRes!!.readString())
		assertTrue(enableJson.getBoolean("ok"))
		assertTrue(enableJson.getBoolean("enabled"))
		assertTrue(p2p.isWhitelistEnabled())

		// Verify persistence in settings.json
		val settingsFile = File(tempDir, "settings.json")
		assertTrue(settingsFile.exists())
		val settings = JSONObject(settingsFile.readText())
		assertEquals("true", settings.getString("graffiti:whitelist-enabled"))

		// Toggle off via API
		val disableHeader = JSONObject()
			.put("path", "/api/whitelist")
			.put("enabled", false)
		val disableRes = api.handle(disableHeader, null)
		assertNotNull(disableRes)
		val disableJson = JSONObject(disableRes!!.readString())
		assertTrue(disableJson.getBoolean("ok"))
		assertFalse(disableJson.getBoolean("enabled"))
		assertFalse(p2p.isWhitelistEnabled())
	}

	@Test
	fun testPeerMessagesSerialization() {
		val seed = "test-seed-messages".toByteArray().hash256()
		val iden = Identity(seed)
		val peer = iden.asPeer()

		// Test PeerRequestMessage
		val req = PeerRequestMessage(listOf(peer.key))
		val reqBytes = req.serialize()
		val reqRead = PeerRequestMessage.read(reqBytes.toDataInputStream())
		assertEquals(1, reqRead.keys.size)
		assertEquals(peer.key, reqRead.keys[0])

		// Test PeerResponseMessage
		val resp = PeerResponseMessage(listOf(peer))
		val respBytes = resp.serialize()
		val respRead = PeerResponseMessage.read(respBytes.toDataInputStream())
		assertEquals(1, respRead.peers.size)
		assertEquals(peer.key, respRead.peers[0].key)
	}

	@Test
	fun testStrictVerificationRejectsUnknownAuthor(@TempDir dirA: File, @TempDir dirB: File) {
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		val alice = p2pA.createIdentity("alice-author")
		val bob = p2pB.createIdentity("bob-recipient")

		// Create encrypted content from Alice to Bob via pkeEncrypt
		val encKey = p2pA.pkeEncrypt(TestStringContent("Hello Bob"), alice, bob.asPeer())
		val metaFile = File(p2pA.metaDir, "$encKey")
		val eMeta = metaFile.toDataInputStream().use { EncryptedContentMetaData.read(it) }
		val sourceContentFile = File(p2pA.contentDir, "$encKey")

		// Copy content file to a temp file for feeding Bob's contentHandler
		val tempContentFile = File(p2pB.tmpDir, "temp_$encKey")
		sourceContentFile.copyTo(tempContentFile, overwrite = true)

		// Bob receives EncryptedContentMessage from Alice, but Bob does NOT have Alice in peerCache
		assertNull(p2pB.getPeerByKey(PeerKey(alice.key.arr)))

		val encMsg = EncryptedContentMessage(eMeta)
		val rawHead = encMsg.serialize()

		ServerSocket(0).use { serverSocket ->
			val port = serverSocket.localPort
			Socket("127.0.0.1", port).use { clientSocket ->
				serverSocket.accept().use { acceptedSocket ->
					val nodeB = r3.net.tcp.TCPNode(acceptedSocket, p2pB.tmpDir, p2pB.contentHandler)

					// Calling contentHandler: should reject because author is unknown
					p2pB.contentHandler(nodeB, rawHead, tempContentFile)

					// Content must NOT be stored
					assertFalse(File(p2pB.contentDir, "$encKey").exists())
					assertFalse(File(p2pB.metaDir, "$encKey").exists())
					assertFalse(p2pB.hasContent(encKey))
					// Temp file should have been deleted
					assertFalse(tempContentFile.exists())

					// Now add Alice to Bob's peers and try again
					p2pB.savePeer(alice.asPeer())
					assertNotNull(p2pB.getPeerByKey(PeerKey(alice.key.arr)))

					val tempContentFile2 = File(p2pB.tmpDir, "temp2_$encKey")
					sourceContentFile.copyTo(tempContentFile2, overwrite = true)

					p2pB.contentHandler(nodeB, rawHead, tempContentFile2)

					// Now content should be verified and stored!
					assertTrue(File(p2pB.contentDir, "$encKey").exists())
					assertTrue(File(p2pB.metaDir, "$encKey").exists())
					assertTrue(p2pB.hasContent(encKey))

					nodeB.close()
				}
			}
		}
	}

	@Test
	fun testP2PPeerResolutionAndWhitelistFlow(@TempDir dirA: File, @TempDir dirB: File) {
		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		p2pA.startTCPServer(0)
		val portA = p2pA.serverPort!!

		val alice = p2pA.createIdentity("alice-author-flow")
		val bob = p2pB.createIdentity("bob-recipient-flow")

		// 1. Alice writes a message to Bob
		val encKey = p2pA.pkeEncrypt(TestStringContent("Hello Bob through P2P"), alice, bob.asPeer())
		assertTrue(p2pA.hasContent(encKey))

		// Ensure Bob does not know Alice yet
		assertNull(p2pB.getPeerByKey(PeerKey(alice.key.arr)))

		// 2. Connect Bob's node to Alice's server
		val nodeToA = p2pB.getTCPNode(java.net.InetSocketAddress("127.0.0.1", portA))

		// Wait for challenge authentication and automated sync
		val deadline = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline && !p2pB.hasContent(encKey)) {
			Thread.sleep(100)
		}

		// Bob should have requested Alice's peer, verified it, and received/verified the message
		assertTrue(p2pB.hasContent(encKey), "Bob should have retrieved and verified content for encKey")
		assertNotNull(p2pB.getPeerByKey(PeerKey(alice.key.arr)), "Bob should have saved Alice to peers")

		val decrypted = p2pB.getContent(encKey)
		assertEquals("Hello Bob through P2P", decrypted.readString())

		// 3. Now test Whitelist: Enable whitelist on Bob
		p2pB.setWhitelistEnabled(true)
		assertTrue(p2pB.isWhitelistEnabled())

		// Alice creates another identity Charlie that Bob has NOT whitelisted
		val charlie = p2pA.createIdentity("charlie-unwhitelisted")
		assertNull(p2pB.getPeerByKey(PeerKey(charlie.key.arr)))

		val unwhitelistedKey = p2pA.pkeEncrypt(TestStringContent("Spam message"), charlie, bob.asPeer())
		assertTrue(p2pA.hasContent(unwhitelistedKey))

		// Trigger sync from Bob to Alice
		p2pB.syncAllConnectedNodes()
		Thread.sleep(500)

		// Bob must NOT have accepted or stored Charlie's message
		assertFalse(p2pB.hasContent(unwhitelistedKey), "Bob must not accept content from unwhitelisted Charlie")
		assertNull(p2pB.getPeerByKey(PeerKey(charlie.key.arr)), "Bob must not auto-add unwhitelisted Charlie")

		// Clean up
		p2pA.stopTCPServer()
		nodeToA.close()
	}
}
