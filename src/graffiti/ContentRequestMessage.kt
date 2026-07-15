package graffiti

import r3.io.Writable
import r3.pke.EncryptedMetaKey
import r3.source.ListWritable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Sent by A to B requesting content (meta + data files) for a list of message keys.
class ContentRequestMessage(val keys: List<EncryptedMetaKey>) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		ListWritable(keys).write(dos)
	}

	companion object {
		val type = "contentrequest"
		fun read(dis: DataInputStream): ContentRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val keys = ListWritable.read(dis, EncryptedMetaKey::read).list
			return ContentRequestMessage(keys)
		}
	}
}

