package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.Content
import r3.org.json.JSONObject
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
}
