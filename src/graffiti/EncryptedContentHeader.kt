package graffiti

import r3.io.Writable
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.pke.PeerKey
import java.io.DataInputStream
import java.io.DataOutputStream

class EncryptedContentHeader(
	val key: EncryptedMetaKey,
	val author: PeerKey,
	val recipient: IdentityKey
) : Writable {
	override fun write(dos: DataOutputStream) {
		key.write(dos)
		author.write(dos)
		recipient.write(dos)
	}

	companion object {
		fun read(dis: DataInputStream): EncryptedContentHeader {
			val key = EncryptedMetaKey.read(dis)
			val author = PeerKey.read(dis)
			val recipient = IdentityKey.read(dis)
			return EncryptedContentHeader(key, author, recipient)
		}
	}
}
