package graffiti

import r3.io.Writable
import r3.pke.IdentityKey
import r3.pke.PeerKey
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

class QueryMessage(
	val authorSet: QueryCondition,
	val recipientSet: QueryCondition
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		authorSet.write(dos)
		recipientSet.write(dos)
	}

	fun matches(eMeta: EncryptedContentMetaData): Boolean {
		return matches(eMeta.author, eMeta.recipient)
	}

	fun matches(header: EncryptedContentHeader): Boolean {
		return matches(header.author, header.recipient)
	}

	fun matches(author: PeerKey, recipient: IdentityKey): Boolean {
		return authorSet.matches(author) && recipientSet.matches(recipient)
	}

	companion object {
		val type = "query"
		fun read(dis: DataInputStream): QueryMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) {
				error("Invalid message type: $type")
			}
			val authorSet = QueryCondition.read(dis)
			val recipientSet = QueryCondition.read(dis)
			return QueryMessage(authorSet, recipientSet)
		}
	}
}