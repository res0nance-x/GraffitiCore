package r3.graffiti

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import r3.hash.hash256
import r3.pke.Encrypt
import r3.pke.Password256
import java.security.SecureRandom

class StreamingHashVerifierTest {
	@Test
	fun testStreamingVerificationSuccess() {
		val srnd = SecureRandom()
		val rawData = ByteArray(1024 * 1024 * 2) // 2 MB
		srnd.nextBytes(rawData)

		val expectedHash = rawData.hash256()
		val pass = Password256.createPassword()
		val encrypted = Encrypt.encrypt(pass, rawData)

		val verifier = StreamingHashVerifier(pass, expectedHash, rawData.size.toLong())
		val chunkSize = 65536
		var offset = 0
		while (offset < encrypted.size) {
			val len = minOf(chunkSize, encrypted.size - offset)
			val chunk = encrypted.copyOfRange(offset, offset + len)
			verifier.update(chunk)
			offset += len
		}

		assertTrue(verifier.finish(), "Inline streaming hash verification should succeed for valid ciphertext")
		verifier.close()
	}

	@Test
	fun testStreamingVerificationFailureOnCorruption() {
		val srnd = SecureRandom()
		val rawData = ByteArray(1024 * 512) // 512 KB
		srnd.nextBytes(rawData)

		val expectedHash = rawData.hash256()
		val pass = Password256.createPassword()
		val encrypted = Encrypt.encrypt(pass, rawData)

		// Corrupt one byte in ciphertext
		encrypted[encrypted.size / 2] = (encrypted[encrypted.size / 2].toInt() xor 0xFF).toByte()

		val verifier = StreamingHashVerifier(pass, expectedHash, rawData.size.toLong())
		val chunkSize = 32768
		var offset = 0
		while (offset < encrypted.size) {
			val len = minOf(chunkSize, encrypted.size - offset)
			val chunk = encrypted.copyOfRange(offset, offset + len)
			verifier.update(chunk)
			offset += len
		}

		assertFalse(verifier.finish(), "Inline streaming hash verification should fail for corrupt ciphertext")
		verifier.close()
	}
}
