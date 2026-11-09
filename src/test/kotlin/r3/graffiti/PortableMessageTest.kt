package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.FileContent
import r3.content.TextContent
import r3.org.json.JSONObject
import r3.pack.BinaryPack
import r3.source.FileSource
import r3.source.readString
import java.io.File

class PortableMessageTest {

	@Test
	fun testSingleMessageExportAndImport(@TempDir tempDir: File) {
		val dirA = File(tempDir, "nodeA").also { it.mkdirs() }
		val dirB = File(tempDir, "nodeB").also { it.mkdirs() }

		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		val alice = p2pA.createIdentity("AliceSeed").also { p2pA.setIdentityPersistence(it.key, true) }
		val bob = p2pB.createIdentity("BobSeed").also { p2pB.setIdentityPersistence(it.key, true) }

		val bobPeer = bob.asPeer()
		p2pA.savePeer(bobPeer)

		// 1. Alice writes and encrypts a message for Bob
		val messageTime = 1700000000000L
		val encKey = p2pA.pkeEncrypt(TextContent("Sneakernet secret hello!"), alice, bobPeer, timestamp = messageTime)
		assertTrue(p2pA.hasContent(encKey))

		// 2. Alice exports the message to a .pmsg pack
		val exportFile = File(tempDir, "alice_to_bob.pmsg")
		val exportedCount = p2pA.exportMessages(listOf(encKey), exportFile)
		assertEquals(1, exportedCount)
		assertTrue(exportFile.exists())
		assertTrue(exportFile.length() > 0)

		// 3. Inspect pack entries
		val pack = BinaryPack.open(FileSource(exportFile))
		val entryPaths = pack.map { it.path }.toSet()
		assertTrue(entryPaths.contains("meta/$encKey"))
		assertTrue(entryPaths.contains("content/$encKey"))
		assertTrue(entryPaths.contains("peer/${alice.asPeer().key}"))

		// 4. Bob imports the portable message
		assertFalse(p2pB.hasContent(encKey))
		assertNull(p2pB.getPeerByKey(alice.asPeer().key))

		val importResult = p2pB.importPack(FileSource(exportFile))
		assertEquals(1, importResult.importedMessages)
		assertEquals(0, importResult.skippedMessages)
		assertEquals(1, importResult.importedPeers)

		// 5. Verify Bob now has Alice's peer, the message meta, content, and original timestamp
		assertNotNull(p2pB.getPeerByKey(alice.asPeer().key))
		assertTrue(p2pB.hasContent(encKey))
		assertEquals(messageTime, File(p2pB.metaDir, "$encKey").lastModified())

		// Decrypt and verify payload
		val decrypted = p2pB.getContent(encKey)
		assertEquals("Sneakernet secret hello!", decrypted.readString())

		// 6. Idempotent re-import
		val reimportResult = p2pB.importPack(FileSource(exportFile))
		assertEquals(0, reimportResult.importedMessages)
		assertEquals(1, reimportResult.skippedMessages)
		assertEquals(0, reimportResult.importedPeers)
	}

	@Test
	fun testConcatenatedPackFiles(@TempDir tempDir: File) {
		val dirA = File(tempDir, "nodeA").also { it.mkdirs() }
		val dirB = File(tempDir, "nodeB").also { it.mkdirs() }

		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		val alice = p2pA.createIdentity("AliceSeed2").also { p2pA.setIdentityPersistence(it.key, true) }
		val bob = p2pB.createIdentity("BobSeed2").also { p2pB.setIdentityPersistence(it.key, true) }
		p2pA.savePeer(bob.asPeer())

		// Alice creates message 1
		val key1 = p2pA.pkeEncrypt(TextContent("Message number one"), alice, bob.asPeer(), timestamp = 1710000001000L)
		val pack1 = File(tempDir, "msg1.pmsg")
		p2pA.exportMessages(listOf(key1), pack1)

		// Alice creates message 2
		val key2 = p2pA.pkeEncrypt(TextContent("Message number two"), alice, bob.asPeer(), timestamp = 1710000002000L)
		val pack2 = File(tempDir, "msg2.pmsg")
		p2pA.exportMessages(listOf(key2), pack2)

		// Concatenate pack1 and pack2 into a single file
		val combinedPack = File(tempDir, "combined.pmsg")
		combinedPack.writeBytes(pack1.readBytes() + pack2.readBytes())

		// Bob imports combined pack
		val result = p2pB.importPack(FileSource(combinedPack))
		assertEquals(2, result.importedMessages)
		assertEquals(0, result.skippedMessages)

		assertTrue(p2pB.hasContent(key1))
		assertTrue(p2pB.hasContent(key2))
		assertEquals("Message number one", p2pB.getContent(key1).readString())
		assertEquals("Message number two", p2pB.getContent(key2).readString())
	}

	@Test
	fun testApiExportAndImport(@TempDir tempDir: File) {
		val dirA = File(tempDir, "apiNodeA").also { it.mkdirs() }
		val dirB = File(tempDir, "apiNodeB").also { it.mkdirs() }

		val p2pA = GraffitiP2P(dirA)
		val p2pB = GraffitiP2P(dirB)

		val apiA = GraffitiAPI(p2pA) {}
		val apiB = GraffitiAPI(p2pB) {}

		val alice = p2pA.createIdentity("AliceSeed3").also { p2pA.setIdentityPersistence(it.key, true) }
		val bob = p2pB.createIdentity("BobSeed3").also { p2pB.setIdentityPersistence(it.key, true) }
		p2pA.savePeer(bob.asPeer())

		val key = p2pA.pkeEncrypt(TextContent("API Portable Test"), alice, bob.asPeer(), timestamp = 1720000000000L)

		// 1. Export via API
		val exportHeader = JSONObject()
			.put("path", "/api/messages/export")
			.put("key", key.toString())

		val exportContent = apiA.handle(exportHeader, null)
		assertNotNull(exportContent)
		assertTrue(exportContent is FileContent)
		val exportedFile = (exportContent as FileContent).file
		assertTrue(exportedFile.exists())
		assertTrue(exportedFile.length() > 0)

		// 2. Import via API on Node B
		val importHeader = JSONObject()
			.put("path", "/api/messages/import")

		val importContent = apiB.handle(importHeader, exportContent)
		assertNotNull(importContent)
		val importJson = JSONObject(importContent!!.readString())
		assertTrue(importJson.getBoolean("ok"))
		assertEquals(1, importJson.getInt("imported"))
		assertEquals(0, importJson.getInt("skipped"))

		// 3. Verify Node B now has the message and can read it
		assertTrue(p2pB.hasContent(key))
		assertEquals("API Portable Test", p2pB.getContent(key).readString())
	}
}
