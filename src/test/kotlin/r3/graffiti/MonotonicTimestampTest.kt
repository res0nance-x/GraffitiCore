package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.Content
import r3.io.serialize
import r3.io.toDataInputStream
import r3.pke.EncryptedMetaKey
import r3.pke.Identity
import r3.source.BinarySource
import java.io.File
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonotonicTimestampTest {

	@Test
	fun testNextMonotonicTimestampStrictlyIncreasing(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val count = 2000
		val timestamps = LongArray(count)

		// Generate 2000 timestamps in tight loop
		for (i in 0 until count) {
			timestamps[i] = p2p.nextMonotonicTimestamp()
		}

		for (i in 0 until count - 1) {
			assertTrue(
				timestamps[i] < timestamps[i + 1],
				"Timestamps must be strictly increasing: ${timestamps[i]} vs ${timestamps[i + 1]} at index $i"
			)
		}
	}

	@Test
	fun testNextMonotonicTimestampConcurrent(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val count = 1000
		val collected = ConcurrentSkipListSet<Long>()
		val pool = Executors.newFixedThreadPool(8)

		for (i in 0 until count) {
			pool.submit {
				collected.add(p2p.nextMonotonicTimestamp())
			}
		}

		pool.shutdown()
		assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
		assertEquals(count, collected.size, "All concurrently generated timestamps must be unique")
	}

	@Test
	fun testPkeEncryptSetsMonotonicFileTimestamps(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val alice = p2p.createIdentity("test-alice")
		val bob = p2p.createIdentity("test-bob")

		val msgCount = 10
		val keys = mutableListOf<EncryptedMetaKey>()

		for (i in 0 until msgCount) {
			val content = TestStringContent("Hello $i")
			val key = p2p.pkeEncrypt(content, alice, bob.asPeer())
			keys.add(key)
		}

		// Verify files on disk have strictly increasing lastModified timestamps
		val fileTimes = keys.map { key ->
			File(p2p.metaDir, "$key").lastModified()
		}

		for (i in 0 until fileTimes.size - 1) {
			assertTrue(
				fileTimes[i] < fileTimes[i + 1],
				"File timestamp for msg $i (${fileTimes[i]}) must be < msg ${i + 1} (${fileTimes[i + 1]})"
			)
		}
	}

	@Test
	fun testQueryOrderTimestampsPreservedEvenWhenContentArrivesOutOfOrder(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val alice = p2p.createIdentity("query-alice")
		val bob = p2p.createIdentity("query-bob")

		// Pre-create 3 messages via pkeEncrypt in a separate directory so we have valid encrypted meta
		val tempSenderDir = File(tempDir, "sender").also { it.mkdirs() }
		val senderP2P = GraffitiP2P(tempSenderDir)
		val senderAlice = senderP2P.createIdentity("query-alice")

		val createdMeta = (1..3).map { i ->
			val encKey = senderP2P.pkeEncrypt(TestStringContent("Payload $i"), senderAlice, bob.asPeer())
			val metaFile = File(senderP2P.metaDir, "$encKey")
			metaFile.toDataInputStream().use { EncryptedContentMetaData.read(it) }
		}

		// Simulate QueryResponseMessage pre-registering order: item1, item2, item3
		createdMeta.forEach { eMeta ->
			p2p.queryOrderTimestamps.computeIfAbsent(eMeta.key) { p2p.nextMonotonicTimestamp() }
		}

		// Now simulate content arriving in reverse order: item3, then item1, then item2
		val receiveOrder = listOf(createdMeta[2], createdMeta[0], createdMeta[1])
		receiveOrder.forEach { eMeta ->
			val metaFile = File(p2p.metaDir, eMeta.key.toString())
			metaFile.writeBytes(eMeta.serialize())
			val ts = p2p.queryOrderTimestamps.remove(eMeta.key) ?: p2p.nextMonotonicTimestamp()
			metaFile.setLastModified(ts)
		}

		// Check the files on disk: item1 must be < item2 < item3
		val time1 = File(p2p.metaDir, "${createdMeta[0].key}").lastModified()
		val time2 = File(p2p.metaDir, "${createdMeta[1].key}").lastModified()
		val time3 = File(p2p.metaDir, "${createdMeta[2].key}").lastModified()

		assertTrue(time1 < time2, "Item 1 ($time1) should be earlier than Item 2 ($time2)")
		assertTrue(time2 < time3, "Item 2 ($time2) should be earlier than Item 3 ($time3)")
	}
}
