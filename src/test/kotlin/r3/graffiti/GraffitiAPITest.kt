package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.Content
import r3.org.json.JSONObject
import r3.pack.BinaryPack
import r3.pke.EncryptedMetaKey
import r3.pke.name
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

	@Test
	fun testPackCreationPipeline(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		val alice = p2p.createIdentity("alice-pack-seed")
		val bob = p2p.createIdentity("bob-pack-seed")

		// 1. Begin pack creation
		val beginHeader = JSONObject()
			.put("path", "/api/pack/create/begin")
			.put("name", "gallery.pack")
			.put("identityKey", alice.key.toString())
			.put("peerKey", bob.asPeer().key.toString())
		val beginRes = api.handle(beginHeader, null)
		assertNotNull(beginRes)
		val beginJson = JSONObject(beginRes!!.readString())
		assertTrue(beginJson.getBoolean("ok"))
		val sessionId = beginJson.getString("sessionId")
		assertTrue(sessionId.isNotEmpty())

		// 2. Upload files to pack
		val file1Content = TestStringContent("Contents of image 1")
		val upload1Header = JSONObject()
			.put("path", "/api/pack/create/file")
			.put("sessionId", sessionId)
			.put("filePath", "image1.txt")
		val upload1Res = api.handle(upload1Header, file1Content)
		assertNotNull(upload1Res)
		assertTrue(JSONObject(upload1Res!!.readString()).getBoolean("ok"))

		val file2Content = TestStringContent("Contents of nested image 2")
		val upload2Header = JSONObject()
			.put("path", "/api/pack/create/file")
			.put("sessionId", sessionId)
			.put("filePath", "nested/folder/image2.txt")
		val upload2Res = api.handle(upload2Header, file2Content)
		assertNotNull(upload2Res)
		assertTrue(JSONObject(upload2Res!!.readString()).getBoolean("ok"))

		// 3. Finish pack creation
		val finishHeader = JSONObject()
			.put("path", "/api/pack/create/finish")
			.put("sessionId", sessionId)
		val finishRes = api.handle(finishHeader, null)
		assertNotNull(finishRes)
		val finishJson = JSONObject(finishRes!!.readString())
		assertTrue(finishJson.getBoolean("ok"))
		val encKeyStr = finishJson.getString("key")
		val encKey = EncryptedMetaKey(encKeyStr)

		// 4. Verify recipient Bob received and can decrypt the pack
		assertTrue(p2p.hasContent(encKey))
		val packContent = p2p.getContent(encKey)
		assertEquals("gallery.pack", packContent.path)
		assertEquals("pack", packContent.ext)

		// 5. Open the pack with BinaryPack and verify entries
		val binaryPack = BinaryPack(packContent)
		assertTrue(binaryPack.keys.contains("image1.txt"))
		assertTrue(binaryPack.keys.contains("nested/folder/image2.txt"))
		assertEquals("Contents of image 1", binaryPack["image1.txt"]!!.readString())
		assertEquals("Contents of nested image 2", binaryPack["nested/folder/image2.txt"]!!.readString())

		// 6. Verify staging directory and temp pack file in tmpDir are cleaned up
		val stagingDir = File(p2p.tmpDir, "pack_stage_$sessionId")
		assertFalse(stagingDir.exists())
		val tempPackFile = File(p2p.tmpDir, "pack_$sessionId.pack")
		assertFalse(tempPackFile.exists())
	}

	@Test
	fun testStateManagerAndApiStateLifecycle(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val events = mutableListOf<JSONObject>()
		val api = GraffitiAPI(p2p) { msg -> events.add(msg) }

		// 1. Initial /api/state call
		val stateHeader = JSONObject().put("path", "/api/state")
		val res1 = api.handle(stateHeader, null)
		assertNotNull(res1)
		val json1 = JSONObject(res1!!.readString())
		assertTrue(json1.getBoolean("ok"))
		val initialVersion = json1.getLong("version")
		assertTrue(initialVersion >= 1L)
		assertFalse(json1.getBoolean("transferring"))
		assertFalse(json1.getBoolean("encoding"))
		assertTrue(json1.has("nodes"))
		assertTrue(json1.has("identities"))
		assertTrue(json1.has("peers"))
		assertTrue(json1.has("messageKeys"))

		// 2. Identity creation triggers state change notification
		val createIdHeader = JSONObject().put("path", "/api/identity/create")
		val createIdRes = api.handle(createIdHeader, TestStringContent(JSONObject().put("seed", "test seed phrase for state manager").toString()))
		assertNotNull(createIdRes)
		val createIdJson = JSONObject(createIdRes!!.readString())
		assertTrue(createIdJson.getBoolean("ok"))

		assertTrue(events.isNotEmpty())
		val lastEvent = events.last()
		assertEquals("state_changed", lastEvent.getString("event"))
		val newVersion = lastEvent.getLong("version")
		assertTrue(newVersion > initialVersion)

		// 3. /api/state reflects the updated version and identity list
		val res2 = api.handle(stateHeader, null)
		assertNotNull(res2)
		val json2 = JSONObject(res2!!.readString())
		assertEquals(newVersion, json2.getLong("version"))
		assertEquals(1, json2.getJSONArray("identities").length())

		// 4. Encoding flag updates version and state snapshot
		api.stateManager.setEncoding(true)
		val res3 = api.handle(stateHeader, null)
		assertNotNull(res3)
		val json3 = JSONObject(res3!!.readString())
		assertTrue(json3.getBoolean("encoding"))
		assertTrue(json3.getLong("version") > newVersion)

		api.stateManager.setEncoding(false)
		val res4 = api.handle(stateHeader, null)
		assertNotNull(res4)
		val json4 = JSONObject(res4!!.readString())
		assertFalse(json4.getBoolean("encoding"))
	}

	@Test
	fun testStartupTempFilesCleanup(@TempDir baseDir: File) {
		val tmpDir = File(baseDir, "tmp")
		tmpDir.mkdirs()

		// 1. Create leftover files from previous run: 0-size file, large file, and subfolder
		val zeroByteFile = File(tmpDir, "r3tmp_0byte.tmp").also { it.createNewFile() }
		val largeFile = File(tmpDir, "r3tmp_large.tmp").also { it.writeBytes(ByteArray(1024 * 1024)) }
		val staleDir = File(tmpDir, "pack_stage_12345").also {
			it.mkdirs()
			File(it, "stale_payload.bin").writeBytes(ByteArray(512))
		}

		assertTrue(zeroByteFile.exists())
		assertTrue(largeFile.exists())
		assertTrue(staleDir.exists())

		// 2. Starting GraffitiP2P should automatically purge all pre-existing temp files on startup
		val p2p = GraffitiP2P(baseDir)
		assertFalse(zeroByteFile.exists())
		assertFalse(largeFile.exists())
		assertFalse(staleDir.exists())
		assertTrue(tmpDir.exists())
		assertEquals(0, tmpDir.listFiles()?.size ?: 0)

		// 3. Test cleanTempFiles with age threshold for periodic cleanup
		val recentFile = File(tmpDir, "recent.tmp").also { it.writeBytes("recent".toByteArray()) }
		val oldFile = File(tmpDir, "old.tmp").also {
			it.writeBytes("old".toByteArray())
			it.setLastModified(System.currentTimeMillis() - 3600_000L) // 1 hour old
		}

		val cleaned = p2p.cleanTempFiles(maxAgeMs = 30 * 60 * 1000L)
		assertEquals(1, cleaned)
		assertTrue(recentFile.exists())
		assertFalse(oldFile.exists())
	}

	@Test
	fun testOpenUrlApi(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		var openedUrl: String? = null
		val api = GraffitiAPI(p2p) {}.apply {
			onOpenUrl = { url ->
				openedUrl = url
				true
			}
		}

		// 1. Missing URL fails
		val missingHeader = JSONObject().put("path", "/api/open-url")
		val missingRes = api.handle(missingHeader, null)
		assertNotNull(missingRes)
		val missingJson = JSONObject(missingRes!!.readString())
		assertFalse(missingJson.getBoolean("ok"))
		assertTrue(missingJson.getString("error").contains("Missing 'url'"))

		// 2. Dangerous protocols (file:, javascript:, data:) are rejected
		for (badUrl in listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/html,test")) {
			val badHeader = JSONObject().put("path", "/api/open-url").put("param", JSONObject().put("url", badUrl))
			val badRes = api.handle(badHeader, null)
			assertNotNull(badRes)
			val badJson = JSONObject(badRes!!.readString())
			assertFalse(badJson.getBoolean("ok"))
			assertTrue(badJson.getString("error").contains("Only http and https links are permitted"))
		}

		// 3. Valid https URL via param
		val validHttpsHeader = JSONObject().put("path", "/api/open-url").put("param", JSONObject().put("url", "https://github.com/res0nance-x/Graffiti"))
		val validRes = api.handle(validHttpsHeader, null)
		assertNotNull(validRes)
		val validJson = JSONObject(validRes!!.readString())
		assertTrue(validJson.getBoolean("ok"))
		assertEquals("https://github.com/res0nance-x/Graffiti", openedUrl)

		// 4. Valid http URL via JSON body content
		val validBodyHeader = JSONObject().put("path", "/api/open-url")
		val bodyContent = TestStringContent(JSONObject().put("url", "http://example.org/topic").toString())
		val validBodyRes = api.handle(validBodyHeader, bodyContent)
		assertNotNull(validBodyRes)
		val validBodyJson = JSONObject(validBodyRes!!.readString())
		assertTrue(validBodyJson.getBoolean("ok"))
		assertEquals("http://example.org/topic", openedUrl)
	}

	@Test
	fun testUnencryptedListMessagesAndMetaEndpoint(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val api = GraffitiAPI(p2p) {}

		val alice = p2p.createIdentity("alice-seed")
		val bob = p2p.createIdentity("bob-seed")

		// 1. Alice sends text message to Bob
		val sendHeader = JSONObject()
			.put("path", "/api/message/send/text")
			.put("method", "POST")
			.put("identityKey", alice.key.toString())
			.put("peerKey", bob.asPeer().key.toString())
		val sendRes = api.handle(sendHeader, TestStringContent("Hello Bob without decrypting"))
		assertNotNull(sendRes)
		val sendJson = JSONObject(sendRes!!.readString())
		assertTrue(sendJson.getBoolean("ok"))
		val msgKeyStr = sendJson.getString("key")
		val msgKey = EncryptedMetaKey(msgKeyStr)

		// 2. /api/messages returns envelope only (no type, name, size in envelope)
		val listHeader = JSONObject().put("path", "/api/messages").put("method", "GET")
		val listRes = api.handle(listHeader, null)
		assertNotNull(listRes)
		val listJson = JSONObject(listRes!!.readString())
		assertTrue(listJson.getBoolean("ok"))
		val messagesArr = listJson.getJSONArray("messages")
		assertEquals(1, messagesArr.length())
		val msgObj = messagesArr.getJSONObject(0)
		assertEquals(msgKeyStr, msgObj.getString("key"))
		assertEquals(alice.key.name, msgObj.getString("author"))
		assertEquals(bob.key.name, msgObj.getString("recipient"))
		assertTrue(msgObj.has("fileTime"))
		assertFalse(msgObj.has("type"))
		assertFalse(msgObj.has("name"))
		assertFalse(msgObj.has("size"))

		// 3. PUT /api/message/meta with single key in JSON body returns decrypted metadata
		val metaPutHeader = JSONObject()
			.put("path", "/api/message/meta")
			.put("method", "PUT")
		val metaPutBody = TestStringContent(JSONObject().put("key", msgKeyStr).toString())
		val metaRes = api.handle(metaPutHeader, metaPutBody)
		assertNotNull(metaRes)
		val metaJson = JSONObject(metaRes!!.readString())
		assertTrue(metaJson.getBoolean("ok"))
		val metaObj = metaJson.getJSONObject("meta")
		assertEquals(msgKeyStr, metaObj.getString("key"))
		assertEquals("txt", metaObj.getString("type"))
		assertTrue(metaObj.has("size"))
		assertTrue(metaObj.has("created"))
		assertFalse(metaObj.getBoolean("urgent"))

		// 4. PUT /api/messages/meta with batch keys in JSON body returns metas array
		val batchPutHeader = JSONObject()
			.put("path", "/api/messages/meta")
			.put("method", "PUT")
		val batchPutBody = TestStringContent(JSONObject().put("keys", r3.org.json.JSONArray().put(msgKeyStr)).toString())
		val batchRes = api.handle(batchPutHeader, batchPutBody)
		assertNotNull(batchRes)
		val batchJson = JSONObject(batchRes!!.readString())
		assertTrue(batchJson.getBoolean("ok"))
		val metasArr = batchJson.getJSONArray("metas")
		assertEquals(1, metasArr.length())
		assertEquals(msgKeyStr, metasArr.getJSONObject(0).getString("key"))

		// 5. GET /api/messages/meta with query param keys=... returns metas array
		val batchGetHeader = JSONObject()
			.put("path", "/api/messages/meta")
			.put("method", "GET")
			.put("keys", msgKeyStr)
		val batchGetRes = api.handle(batchGetHeader, null)
		assertNotNull(batchGetRes)
		val batchGetJson = JSONObject(batchGetRes!!.readString())
		assertTrue(batchGetJson.getBoolean("ok"))
		assertEquals(1, batchGetJson.getJSONArray("metas").length())

		// 6. GET /api/message/meta with query param key=... returns single meta
		val singleGetHeader = JSONObject()
			.put("path", "/api/message/meta")
			.put("method", "GET")
			.put("key", msgKeyStr)
		val singleGetRes = api.handle(singleGetHeader, null)
		assertNotNull(singleGetRes)
		val singleGetJson = JSONObject(singleGetRes!!.readString())
		assertTrue(singleGetJson.getBoolean("ok"))
		assertEquals(msgKeyStr, singleGetJson.getJSONObject("meta").getString("key"))

		// 7. Check caching: p2p.getDecryptedMeta returns cached instance
		val cachedPair1 = p2p.getDecryptedMeta(msgKey)
		assertNotNull(cachedPair1)
		val cachedPair2 = p2p.getDecryptedMeta(msgKey)
		assertSame(cachedPair1, cachedPair2)

		// 8. Delete message evicts from cache
		assertTrue(p2p.deleteMessage(msgKey))
		assertNull(p2p.getDecryptedMeta(msgKey))
	}

	@Test
	fun testAsyncCommandEndpoints(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val events = mutableListOf<JSONObject>()
		val api = GraffitiAPI(p2p) { evt -> events.add(evt) }

		val alice = p2p.createIdentity("async-alice")
		val bob = p2p.createIdentity("async-bob")

		// 1. Send file asynchronously
		val sendFileHeader = JSONObject()
			.put("path", "/api/message/send/file")
			.put("method", "PUT")
			.put("identityKey", alice.key.toString())
			.put("peerKey", bob.asPeer().key.toString())
			.put("file", "notes.txt")
			.put("async", true)
		val sendRes = api.handle(sendFileHeader, TestStringContent("Secret notes for Bob"))
		assertNotNull(sendRes)
		val sendJson = JSONObject(sendRes!!.readString())
		assertTrue(sendJson.getBoolean("ok"))
		assertTrue(sendJson.has("commandId"))
		val cmdId = sendJson.getString("commandId")

		// 2. Poll status until completed
		val deadline = System.currentTimeMillis() + 5000
		var status = ""
		while (System.currentTimeMillis() < deadline) {
			val statusHeader = JSONObject()
				.put("path", "/api/command/status")
				.put("commandId", cmdId)
			val statusRes = api.handle(statusHeader, null)
			assertNotNull(statusRes)
			val cmdObj = JSONObject(statusRes!!.readString()).getJSONObject("command")
			status = cmdObj.getString("status")
			if (status == "COMPLETED") break
			Thread.sleep(25)
		}
		assertEquals("COMPLETED", status)

		// 3. Verify message listed
		val listHeader = JSONObject().put("path", "/api/messages").put("method", "GET")
		val listJson = JSONObject(api.handle(listHeader, null)!!.readString())
		assertTrue(listJson.getBoolean("ok"))
		val msgs = listJson.getJSONArray("messages")
		assertEquals(1, msgs.length())
	}
}

