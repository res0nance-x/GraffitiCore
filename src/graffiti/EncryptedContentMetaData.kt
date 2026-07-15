package graffiti

import r3.content.ContentMeta
import r3.hash.hash256
import r3.io.Writable
import r3.io.serialize
import r3.io.toDataInputStream
import r3.key.Key256
import r3.key.hash256
import r3.pke.*
import r3.source.BlockWritable
import r3.source.Source
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.math.BigInteger

class EncryptedContentMetaData(
	val eMeta: ByteArray,
	val author: PeerKey, // during creation this is an Identity. For storage and reading, this is a Peer
	val recipient: IdentityKey, // during creation this is the Peer. For storage and reading, this is an Identity
	val my: BigInteger,
	val ePass: Key256, // Encrypted password. Need recipient Identity to decrypt
	val contentKey: ContentKey,
	val sign: Signature
) : Writable {
	val key = EncryptedMetaKey(sign.serialize().hash256())

	fun toHeader(): EncryptedContentHeader {
		return EncryptedContentHeader(key, author, recipient)
	}

	fun decrypt(identity: Identity): Pair<ContentMeta, Password256> {
		if (identity.key != recipient) {
			error("The recipient key doesn't match the supplied identity key")
		}
		identity.createCipherKey(my).createDecrypt().doFinal(ePass.arr).toDataInputStream().use { Password256.read(it) }
			.let { pass ->
				val metaArr = Encrypt.decrypt(Password256(pass), eMeta)
				return Pair(ContentMeta.read(metaArr.toDataInputStream()), pass)
			}
	}

	fun verify(peer: Peer): Boolean {
		val baos = ByteArrayOutputStream()
		baos.write(author.arr)
		baos.write(recipient.arr)
		baos.write(contentKey.arr)
		baos.write(eMeta)
		return peer.verify(baos.toByteArray(), sign)
	}

	fun decryptStream(identity: Identity, contentSource: Source): InputStream {
		val (_, pass) = decrypt(identity)
		return EncryptSource(pass, contentSource).createInputStream()
	}

	fun verify(peer: Peer, contentSource: Source, identity: Identity): Boolean {
		if (!verify(peer)) return false
		return try {
			val (_, pass) = decrypt(identity)
			val hash = EncryptSource(pass, contentSource).hash256()
			contentKey.arr.contentEquals(hash)
		} catch (e: Exception) {
			false
		}
	}

	override fun write(dos: DataOutputStream) {
		author.write(dos)
		recipient.write(dos)
		BigIntegerWritable(my).write(dos)
		ePass.write(dos)
		contentKey.write(dos)
		sign.write(dos)
		BlockWritable(eMeta).write(dos)
	}

	override fun toString(): String {
		return """author: $author, recipient: $recipient, contentKey: $contentKey"""
	}

	companion object {
		fun read(dis: DataInputStream): EncryptedContentMetaData {
			val author = PeerKey.read(dis)
			val recipient = IdentityKey.read(dis)
			val my = BigIntegerWritable.read(dis).bi
			val ePass = Key256.read(dis)
			val contentKey = ContentKey.read(dis)
			val sign = Signature.read(dis)
			val eMeta = BlockWritable.read(dis).arr
			return EncryptedContentMetaData(eMeta, author, recipient, my, ePass, contentKey, sign)
		}
	}
}