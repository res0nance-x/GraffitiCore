package graffiti

import r3.io.Writable
import r3.pke.Peer
import r3.pke.Signature
import r3.source.BlockWritable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Step 1 of mutual challenge-response authentication.
 *
 * Both peers send this immediately on connection. The [nonce] is 32 cryptographically
 * random bytes chosen by the sender. The receiver must reply with [ChallengeResponseMessage].
 *
 * Because the verifier picks the nonce, each handshake is unique — replay of a
 * captured response from a previous session is cryptographically impossible.
 */
class ChallengeMessage(val nonce: ByteArray) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		BlockWritable(nonce).write(dos)
	}

	companion object {
		val type = "challenge"
		fun read(dis: DataInputStream): ChallengeMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			return ChallengeMessage(BlockWritable.read(dis).arr)
		}
	}
}

/**
 * Step 2 of mutual challenge-response authentication.
 *
 * Sent in response to a received [ChallengeMessage].
 * [peer] is the sender's public key.
 * [signature] = identity.sign(received nonce) — proves ownership of the private key.
 *
 * The recipient verifies with [isValidFor], passing the nonce they originally sent.
 */
class ChallengeResponseMessage(val peer: Peer, val signature: Signature, val isRelay: Boolean = false) : Writable {
	fun isValidFor(nonce: ByteArray): Boolean = try {
		peer.verify(nonce, signature)
	} catch (_: Exception) {
		false
	}

	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		peer.write(dos)
		signature.write(dos)
		dos.writeBoolean(isRelay)
	}

	companion object {
		val type = "challenge_response"
		fun read(dis: DataInputStream): ChallengeResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val peer = Peer.read(dis)
			val signature = Signature.read(dis)
			val isRelay = try {
				dis.readBoolean()
			} catch (e: Exception) {
				false
			}
			return ChallengeResponseMessage(peer, signature, isRelay)
		}
	}
}

