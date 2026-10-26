package r3.graffiti

import r3.io.Writable
import r3.pke.EncryptedMetaKey
import r3.source.ListWritable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Sent by A to B requesting content (meta + data files) for a list of message keys.
class ContentRequestMessage(
	val keys: List<EncryptedMetaKey>,
	val offsets: Map<EncryptedMetaKey, Long> = emptyMap()
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		ListWritable(keys).write(dos)
		if (offsets.isNotEmpty()) {
			dos.writeInt(offsets.size)
			offsets.forEach { (key, offset) ->
				key.write(dos)
				dos.writeLong(offset)
			}
		} else {
			dos.writeInt(0)
		}
	}

	companion object {
		val type = "contentrequest"
		fun read(dis: DataInputStream): ContentRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val keys = ListWritable.read(dis, EncryptedMetaKey::read).list
			val offsets = mutableMapOf<EncryptedMetaKey, Long>()
			if (dis.available() >= 4) {
				try {
					val count = dis.readInt()
					repeat(count) {
						val key = EncryptedMetaKey.read(dis)
						val offset = dis.readLong()
						offsets[key] = offset
					}
				} catch (_: Exception) {}
			}
			return ContentRequestMessage(keys, offsets)
		}
	}
}

