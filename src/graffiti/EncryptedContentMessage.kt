package graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Signals that the file is Content data
class EncryptedContentMessage(val eMeta: EncryptedContentMetaData) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		eMeta.write(dos)
	}

	companion object {
		val type = "content"
		fun read(dis: DataInputStream): EncryptedContentMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) {
				error("Invalid message type: $type")
			}
			val eMeta = EncryptedContentMetaData.read(dis)
			return EncryptedContentMessage(eMeta)
		}
	}
}