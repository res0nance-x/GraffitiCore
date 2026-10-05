package r3.graffiti

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.ContentMeta
import r3.content.FileContent
import r3.key.Key256
import r3.key.hash256
import r3.pke.ContentKey
import r3.pke.Encrypt
import r3.pke.Identity
import r3.pke.Password256
import r3.source.FileSink
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.system.measureNanoTime

class PkeEncryptionBenchmarkTest {

	@Test
	fun benchmarkPkeEncryptionPipeline(@TempDir tempDir: Path) {
		val p2pDir = tempDir.resolve("p2p").toFile().apply { mkdirs() }
		val p2p = GraffitiP2P(p2pDir)
		val alice = p2p.createIdentity("Alice")
		val bob = p2p.createIdentity("Bob")

		val sizesMB = listOf(1, 10, 50)
		for (sizeMB in sizesMB) {
			println("\n=======================================================")
			println("PKE ENCRYPT BENCHMARK FOR $sizeMB MB FILE")
			println("=======================================================")
			val totalBytes = sizeMB * 1024 * 1024
			val testFile = tempDir.resolve("input_$sizeMB.bin").toFile()
			FileOutputStream(testFile).buffered().use { fos ->
				val buf = ByteArray(65536) { 0x33 }
				repeat(totalBytes / buf.size) { fos.write(buf) }
			}
			val content = FileContent(testFile)

			// 1. Measure content.hash256()
			var contentKey: ContentKey? = null
			val hashNanos = measureNanoTime {
				contentKey = ContentKey(content.hash256())
			}
			val hashMBps = sizeMB / (hashNanos / 1_000_000_000.0)
			println("[1] content.hash256() : ${"%.2f".format(hashNanos / 1_000_000.0)} ms (${"%.2f".format(hashMBps)} MB/s)")

			// 2. Measure meta.encrypt(...) including ECDH/RSA & Author signature
			val meta = ContentMeta(content)
			val pass = Password256.createPassword()
			var eMeta: EncryptedContentMetaData? = null
			val metaEncNanos = measureNanoTime {
				eMeta = meta.encrypt(alice, bob.asPeer(), contentKey!!, pass)
			}
			println("[2] meta.encrypt() + signing: ${"%.2f".format(metaEncNanos / 1_000_000.0)} ms")

			// 3. Measure content.encrypt(pass, FileSink) - The stream cipher writing encrypted content to disk
			val encOutputFile = tempDir.resolve("output_$sizeMB.enc").toFile()
			val streamEncNanos = measureNanoTime {
				Encrypt.encrypt(pass, content, FileSink(encOutputFile, false))
			}
			val streamEncMBps = sizeMB / (streamEncNanos / 1_000_000_000.0)
			println("[3] content.encrypt() (stream cipher): ${"%.2f".format(streamEncNanos / 1_000_000.0)} ms (${"%.2f".format(streamEncMBps)} MB/s)")

			// 4. Measure end-to-end p2p.pkeEncrypt(...)
			val fullNanos = measureNanoTime {
				p2p.pkeEncrypt(content, alice, bob.asPeer())
			}
			val fullMBps = sizeMB / (fullNanos / 1_000_000_000.0)
			println("[4] Full p2p.pkeEncrypt(): ${"%.2f".format(fullNanos / 1_000_000.0)} ms (${"%.2f".format(fullMBps)} MB/s)")

			testFile.delete()
			encOutputFile.delete()
		}
	}
}
