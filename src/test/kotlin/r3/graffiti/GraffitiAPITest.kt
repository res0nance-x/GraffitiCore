package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.Content
import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import r3.source.BinarySource
import r3.source.readString
import java.io.File

class TestStringContent(val text: String) : Content, BinarySource(text.toByteArray()) {
	override val path: String = "test.txt"
	override val ext: String = "txt"
	override val lastModified: Long = System.currentTimeMillis()
}

class GraffitiAPITest {

	@Test
	fun testSettingsStoreLifecycle(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		// 1. Initial GET on non-existent key returns empty value
		val getInitialHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "GET")
			.put("key", "graffiti:theme")
		val getInitialRes = api.handle(getInitialHeader, null)
		assertNotNull(getInitialRes)
		val getInitialJson = JSONObject(getInitialRes!!.readString())
		assertTrue(getInitialJson.getBoolean("ok"))
		assertEquals("", getInitialJson.getString("value"))

		// 2. PUT a setting
		val putHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "PUT")
			.put("key", "graffiti:theme")
		val putRes = api.handle(putHeader, TestStringContent("dark-sky"))
		assertNotNull(putRes)
		val putJson = JSONObject(putRes!!.readString())
		assertTrue(putJson.getBoolean("ok"))

		// 3. GET that setting back
		val getHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "GET")
			.put("key", "graffiti:theme")
		val getRes = api.handle(getHeader, null)
		assertNotNull(getRes)
		val getJson = JSONObject(getRes!!.readString())
		assertTrue(getJson.getBoolean("ok"))
		assertEquals("dark-sky", getJson.getString("value"))

		// 4. PUT another setting
		val putFontSizeHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "PUT")
			.put("key", "graffiti:message-font-size")
		api.handle(putFontSizeHeader, TestStringContent("120%"))

		// 5. GET all settings (no key provided)
		val getAllHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "GET")
		val getAllRes = api.handle(getAllHeader, null)
		assertNotNull(getAllRes)
		val getAllJson = JSONObject(getAllRes!!.readString())
		assertTrue(getAllJson.getBoolean("ok"))
		val settingsObj = getAllJson.getJSONObject("settings")
		assertEquals("dark-sky", settingsObj.getString("graffiti:theme"))
		assertEquals("120%", settingsObj.getString("graffiti:message-font-size"))

		// 6. Verify persistence in settings.json file on disk
		val settingsFile = File(tempDir, "settings.json")
		assertTrue(settingsFile.exists())
		val fileContentJson = JSONObject(settingsFile.readText())
		assertEquals("dark-sky", fileContentJson.getString("graffiti:theme"))
		assertEquals("120%", fileContentJson.getString("graffiti:message-font-size"))

		// 7. DELETE a setting
		val deleteHeader = JSONObject()
			.put("path", "/api/store")
			.put("method", "DELETE")
			.put("key", "graffiti:theme")
		val deleteRes = api.handle(deleteHeader, null)
		assertNotNull(deleteRes)
		val deleteJson = JSONObject(deleteRes!!.readString())
		assertTrue(deleteJson.getBoolean("ok"))

		// 8. Confirm deleted key is removed from settings.json and returns empty
		val getAfterDeleteRes = api.handle(getHeader, null)
		val getAfterDeleteJson = JSONObject(getAfterDeleteRes!!.readString())
		assertEquals("", getAfterDeleteJson.getString("value"))

		val fileContentAfterDelete = JSONObject(settingsFile.readText())
		assertFalse(fileContentAfterDelete.has("graffiti:theme"))
		assertTrue(fileContentAfterDelete.has("graffiti:message-font-size"))
	}

	@Test
	fun testForwardMessageLifecycle(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		// 1. Create identities Alice, Bob, Charlie
		val alice = p2p.createIdentity("alice-seed")
		val bob = p2p.createIdentity("bob-seed")
		val charlie = p2p.createIdentity("charlie-seed")

		// 2. Alice sends a message to Bob
		val origContent = TestStringContent("Confidential info from Alice")
		val origEncKey = p2p.pkeEncrypt(origContent, alice, bob.asPeer())
		assertTrue(p2p.hasContent(origEncKey))

		val bobRead = p2p.getContent(origEncKey)
		assertEquals("Confidential info from Alice", bobRead.readString())
		assertEquals("test.txt", bobRead.path)
		assertEquals("txt", bobRead.ext)

		// 3. Bob forwards the message to Charlie via /api/message/forward
		val forwardHeader = JSONObject()
			.put("path", "/api/message/forward")
			.put("method", "GET")
			.put("key", origEncKey.toString())
			.put("identityKey", bob.key.toString())
			.put("peerKey", charlie.asPeer().key.toString())

		val forwardRes = api.handle(forwardHeader, null)
		assertNotNull(forwardRes)
		val forwardJson = JSONObject(forwardRes!!.readString())
		assertTrue(forwardJson.getBoolean("ok"))
		val forwardedKeyStr = forwardJson.getString("key")
		assertNotEquals(origEncKey.toString(), forwardedKeyStr)

		// 4. Charlie can decrypt and read the forwarded content
		val forwardedEncKey = EncryptedMetaKey(forwardedKeyStr)
		assertTrue(p2p.hasContent(forwardedEncKey))
		val charlieRead = p2p.getContent(forwardedEncKey)
		assertEquals("Confidential info from Alice", charlieRead.readString())
		assertEquals("test.txt", charlieRead.path)
		assertEquals("txt", charlieRead.ext)

		// 5. Deleting original message does not break forwarded message
		assertTrue(p2p.deleteMessage(origEncKey))
		assertTrue(p2p.isMessageDeleted(origEncKey))
		assertFalse(p2p.isMessageDeleted(forwardedEncKey))

		val charlieReadAfterDelete = p2p.getContent(forwardedEncKey)
		assertEquals("Confidential info from Alice", charlieReadAfterDelete.readString())

		// 6. Forwarding with missing key or non-existent content returns error
		val invalidHeader = JSONObject()
			.put("path", "/api/message/forward")
			.put("method", "GET")
			.put("key", "non-existent-key")
			.put("identityKey", bob.key.toString())
			.put("peerKey", charlie.asPeer().key.toString())
		val invalidRes = api.handle(invalidHeader, null)
		assertNotNull(invalidRes)
		val invalidJson = JSONObject(invalidRes!!.readString())
		assertFalse(invalidJson.getBoolean("ok"))
	}
}
