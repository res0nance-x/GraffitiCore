package r3.graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Sent in response to a QueryMessage, or pushed unsolicited when new messages arrive.
// Header: QueryResponseMessage (type + serverTime)
// File body: ListWritable<EncryptedContentHeader> (serialized with ListWritable)
// The data is sent as a streaming file body to handle arbitrarily large metadata lists.
class QueryResponseMessage(
	val serverTime: Long = 0L
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(serverTime)
	}

	companion object {
		const val type = "syncresponse"
		fun read(dis: DataInputStream): QueryResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) {
				error("Invalid message type: $type")
			}
			val serverTime = if (dis.available() >= 8) dis.readLong() else 0L
			return QueryResponseMessage(serverTime)
		}
	}
}

