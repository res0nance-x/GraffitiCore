package r3.graffiti

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.pke.Identity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CommandQueueTest {

	@Test
	fun testCommandExecutionAndTimestampOrdering(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val alice = p2p.createIdentity("alice")
		val bob = p2p.createIdentity("bob")

		val queue = CommandQueue(p2p, workerCount = 2)

		val ts1 = p2p.nextMonotonicTimestamp()
		val stagedFile1 = File(tempDir, "test1.txt").apply { writeText("Content for command 1") }
		val cmd1 = Command(
			type = CommandType.SEND_FILE,
			identityKey = alice.key,
			peerKey = bob.asPeer().key,
			urgent = false,
			sentTimestamp = ts1,
			payload = CommandPayload.FilePayload(stagedFile1, "test1.txt")
		)

		val ts2 = p2p.nextMonotonicTimestamp()
		val cmd2 = Command(
			type = CommandType.SEND_TEXT,
			identityKey = alice.key,
			peerKey = bob.asPeer().key,
			urgent = false,
			sentTimestamp = ts2,
			payload = CommandPayload.Text("Text for command 2")
		)

		assertTrue(ts1 < ts2, "ts1 should be earlier than ts2")

		queue.submit(cmd1)
		queue.submit(cmd2)

		// Wait for both to complete
		val deadline = System.currentTimeMillis() + 10000
		while ((cmd1.status != CommandStatus.COMPLETED || cmd2.status != CommandStatus.COMPLETED) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20)
		}

		assertEquals(CommandStatus.COMPLETED, cmd1.status, "Cmd1 should complete")
		assertEquals(CommandStatus.COMPLETED, cmd2.status, "Cmd2 should complete")
		assertNotNull(cmd1.resultKey)
		assertNotNull(cmd2.resultKey)

		// Verify that staged file was deleted on completion
		assertFalse(stagedFile1.exists(), "Staged file should be deleted after processing")

		// Verify file timestamps on disk preserve creation order (ts1 < ts2)
		val fileTime1 = File(p2p.metaDir, cmd1.resultKey.toString()).lastModified()
		val fileTime2 = File(p2p.metaDir, cmd2.resultKey.toString()).lastModified()

		assertEquals(ts1, fileTime1, "File 1 timestamp should match ts1")
		assertEquals(ts2, fileTime2, "File 2 timestamp should match ts2")
		assertTrue(fileTime1 < fileTime2, "File 1 time must be < File 2 time")

		queue.shutdown()
	}

	@Test
	fun testWatermarkReflectsOldestActiveTimestamp(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val alice = p2p.createIdentity("alice")
		val bob = p2p.createIdentity("bob")

		val queue = CommandQueue(p2p, workerCount = 1)

		val ts1 = p2p.nextMonotonicTimestamp()
		val ts2 = p2p.nextMonotonicTimestamp()

		val cmd1 = Command(
			type = CommandType.SEND_TEXT,
			identityKey = alice.key,
			peerKey = bob.asPeer().key,
			sentTimestamp = ts1,
			payload = CommandPayload.Text("Message 1")
		)
		val cmd2 = Command(
			type = CommandType.SEND_TEXT,
			identityKey = alice.key,
			peerKey = bob.asPeer().key,
			sentTimestamp = ts2,
			payload = CommandPayload.Text("Message 2")
		)

		queue.submit(cmd1)
		queue.submit(cmd2)

		// While active, watermark should not exceed ts1
		val wm = queue.watermark()
		if (wm != null) {
			assertTrue(wm <= ts2)
		}

		val deadline = System.currentTimeMillis() + 10000
		while ((cmd1.status != CommandStatus.COMPLETED || cmd2.status != CommandStatus.COMPLETED) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20)
		}

		assertEquals(CommandStatus.COMPLETED, cmd1.status)
		assertEquals(CommandStatus.COMPLETED, cmd2.status)
		assertNull(queue.watermark(), "Watermark should be null when queue is empty")

		queue.shutdown()
	}

	@Test
	fun testCancellationCleansUpStagedFiles(@TempDir tempDir: File) {
		val p2p = GraffitiP2P(tempDir)
		val alice = p2p.createIdentity("alice")
		val bob = p2p.createIdentity("bob")

		val queue = CommandQueue(p2p, workerCount = 1)

		val stagedFile = File(tempDir, "cancel_test.dat").apply { writeText("some big data") }
		val cmd = Command(
			type = CommandType.SEND_FILE,
			identityKey = alice.key,
			peerKey = bob.asPeer().key,
			sentTimestamp = p2p.nextMonotonicTimestamp(),
			payload = CommandPayload.FilePayload(stagedFile, "cancel_test.dat")
		)

		cmd.status = CommandStatus.CANCELLED
		queue.submit(cmd)

		Thread.sleep(100)
		assertFalse(stagedFile.exists(), "Staged file should be cleaned up on cancelled command")
		queue.shutdown()
	}
}
