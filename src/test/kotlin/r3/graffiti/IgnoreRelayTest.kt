package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.source.readString
import java.io.File
import java.net.ServerSocket

class IgnoreRelayTest {

	@Test
	fun testIgnoreIdentityAndMessageLifecycle(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		val alice = p2p.createIdentity("test-alice")
		val bob = p2p.createIdentity("test-bob")

		val msgKey = p2p.pkeEncrypt(TestStringContent("Hello Bob"), alice, bob.asPeer())

		// 1. Initial state: not ignored
		assertFalse(p2p.isIdentityIgnored(alice.key))
		assertFalse(p2p.isIdentityIgnored(bob.key))
		assertFalse(p2p.isContentIgnored(msgKey))

		// Check API listing includes ignored: false
		val listIdHeader = JSONObject().put("path", "/api/identities").put("method", "GET")
		val listIdRes = JSONObject(api.handle(listIdHeader, null)!!.readString())
		val idArr = listIdRes.getJSONArray("identities")
		for (i in 0 until idArr.length()) {
			assertFalse(idArr.getJSONObject(i).getBoolean("ignored"))
		}

		// 2. Ignore alice identity via API
		val ignoreAliceHeader = JSONObject()
			.put("path", "/api/identity/ignore")
			.put("key", alice.key.toString())
		val ignoreAliceRes = JSONObject(api.handle(ignoreAliceHeader, null)!!.readString())
		assertTrue(ignoreAliceRes.getBoolean("ok"))
		assertTrue(ignoreAliceRes.getBoolean("ignored"))
		assertTrue(p2p.isIdentityIgnored(alice.key))
		assertTrue(File(p2p.ignoredIdentitiesDir, alice.key.toString()).exists())

		// 3. Toggle alice identity back to watched
		val unignoreAliceRes = JSONObject(api.handle(ignoreAliceHeader, null)!!.readString())
		assertTrue(unignoreAliceRes.getBoolean("ok"))
		assertFalse(unignoreAliceRes.getBoolean("ignored"))
		assertFalse(p2p.isIdentityIgnored(alice.key))
		assertFalse(File(p2p.ignoredIdentitiesDir, alice.key.toString()).exists())

		// 4. Ignore content key via API
		val ignoreMsgHeader = JSONObject()
			.put("path", "/api/message/ignore")
			.put("key", msgKey.toString())
		val ignoreMsgRes = JSONObject(api.handle(ignoreMsgHeader, null)!!.readString())
		assertTrue(ignoreMsgRes.getBoolean("ok"))
		assertTrue(ignoreMsgRes.getBoolean("ignored"))
		assertTrue(p2p.isContentIgnored(msgKey))
		assertTrue(File(p2p.ignoredContentDir, msgKey.toString()).exists())

		// Check /api/messages shows ignored: true
		val listMsgHeader = JSONObject().put("path", "/api/messages").put("method", "GET")
		val listMsgRes = JSONObject(api.handle(listMsgHeader, null)!!.readString())
		val msgArr = listMsgRes.getJSONArray("messages")
		assertEquals(1, msgArr.length())
		assertTrue(msgArr.getJSONObject(0).getBoolean("ignored"))

		// 5. Test persistence across GraffitiP2P restart
		val reloadedP2P = GraffitiP2P(tempDir)
		assertTrue(reloadedP2P.isContentIgnored(msgKey))
		assertFalse(reloadedP2P.isIdentityIgnored(alice.key))

		reloadedP2P.setIdentityIgnored(bob.key, true)
		val reloadedP2P2 = GraffitiP2P(tempDir)
		assertTrue(reloadedP2P2.isIdentityIgnored(bob.key))
	}

	@Test
	fun testRelayIgnoresIdentitiesAndKeysOnSync(@TempDir tempDir: File) {
		val serverDir = File(tempDir, "server").also { it.mkdirs() }
		val relayDir = File(tempDir, "relay").also { it.mkdirs() }
		val clientDir = File(tempDir, "client").also { it.mkdirs() }

		val serverP2P = GraffitiP2P(serverDir)
		val relayP2P = GraffitiP2P(relayDir, relayEnabledAtStartup = true)
		val clientP2P = GraffitiP2P(clientDir)

		val alice = serverP2P.createIdentity("server-alice")
		val privateFriend = serverP2P.createIdentity("server-private")
		val relayPeer = relayP2P.serverIdentity.asPeer()
		val clientAlice = clientP2P.createIdentity("client-alice")

		// Create message 1 for privateFriend (to be ignored)
		val msg1Key = serverP2P.pkeEncrypt(TestStringContent("Private content"), alice, privateFriend.asPeer())
		// Create message 2 for clientAlice (not ignored)
		val msg2Key = serverP2P.pkeEncrypt(TestStringContent("Shared content"), alice, clientAlice.asPeer())
		// Create message 3 for clientAlice (will ignore specific content key)
		val msg3Key = serverP2P.pkeEncrypt(TestStringContent("Large content"), alice, clientAlice.asPeer())

		// Mark privateFriend identity and msg3 content as ignored
		serverP2P.setIdentityIgnored(privateFriend.key, true)
		serverP2P.setContentIgnored(msg3Key, true)

		assertTrue(serverP2P.isIdentityIgnored(privateFriend.key))
		assertTrue(serverP2P.isContentIgnored(msg3Key))

		val port = ServerSocket(0).use { it.localPort }
		serverP2P.startTCPServer(port)

		// 1. Relay connects and syncs (asks for ALL)
		val relayNode = relayP2P.getTCPNode(java.net.InetSocketAddress("127.0.0.1", port))
		val deadline1 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline1 && !relayP2P.hasContent(msg2Key)) {
			Thread.sleep(50)
		}
		assertTrue(relayP2P.hasContent(msg2Key), "Relay should have received shared unignored message 2")
		assertFalse(relayP2P.hasContent(msg1Key), "Relay must NOT have received message 1 with ignored identity")
		assertFalse(relayP2P.hasContent(msg3Key), "Relay must NOT have received message 3 with ignored content key")

		// 2. Regular client (asking for clientAlice) connects directly
		val clientNode = clientP2P.getTCPNode(java.net.InetSocketAddress("127.0.0.1", port))
		val deadline2 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline2 && (!clientP2P.hasContent(msg2Key) || !clientP2P.hasContent(msg3Key))) {
			Thread.sleep(50)
		}
		assertTrue(clientP2P.hasContent(msg2Key), "Direct peer should receive message 2 addressed to them")
		assertTrue(clientP2P.hasContent(msg3Key), "Direct peer should receive message 3 addressed to them even if ignored for relays")

		// 3. Unignore msg3 and watch it upload to relay
		serverP2P.setContentIgnored(msg3Key, false)
		val deadline3 = System.currentTimeMillis() + 5000L
		while (System.currentTimeMillis() < deadline3 && !relayP2P.hasContent(msg3Key)) {
			Thread.sleep(50)
		}
		assertTrue(relayP2P.hasContent(msg3Key), "Relay should receive message 3 once unignored")

		// Clean up
		serverP2P.stopTCPServer()
		relayNode.close()
		clientNode.close()
	}
}
