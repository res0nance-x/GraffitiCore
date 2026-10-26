package r3.graffiti

import r3.math.EncryptedSequence
import r3.pke.Password256
import java.security.MessageDigest

class StreamingHashVerifier(
	val pass: Password256,
	val expectedHash: ByteArray,
	val expectedLength: Long = 0L
) : AutoCloseable {
	private val seq = EncryptedSequence.createSequence(pass)
	private val md = MessageDigest.getInstance("SHA-256")
	private val decryptBuf = ByteArray(r3.net.tcp.TCPBlock.MAXBLOCKSIZE)
	@Volatile var totalBytes: Long = 0L
		private set
	@Volatile var verified: Boolean? = null
		private set

	fun update(chunk: ByteArray) {
		if (chunk.isEmpty()) return
		val len = chunk.size
		val buf = if (len <= decryptBuf.size) decryptBuf else ByteArray(len)
		System.arraycopy(chunk, 0, buf, 0, len)
		seq.xor(buf, 0, len, totalBytes)
		md.update(buf, 0, len)
		totalBytes += len
	}

	fun finish(): Boolean {
		if (verified != null) return verified!!
		val hash = md.digest()
		val lenMatches = expectedLength <= 0L || totalBytes == expectedLength
		val hashMatches = hash.contentEquals(expectedHash)
		verified = lenMatches && hashMatches
		return verified!!
	}

	override fun close() {
		try {
			seq.close()
		} catch (_: Exception) {}
	}
}
