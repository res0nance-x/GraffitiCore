package r3.graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Signals that the file is Content data
class EncryptedContentMessage(
	val eMeta: EncryptedContentMetaData,
	val contentLength: Long = 0L,
	val offset: Long = 0L
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(contentLength)
		eMeta.write(dos)
		dos.writeLong(offset)
	}

	companion object {
		val type = "content"
		fun read(dis: DataInputStream): EncryptedContentMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) {
				error("Invalid message type: $type")
			}
			val contentLength = dis.readLong()
			val eMeta = EncryptedContentMetaData.read(dis)
			val offset = if (dis.available() >= 8) {
				try {
					dis.readLong()
				} catch (_: Exception) {
					0L
				}
			} else 0L
			return EncryptedContentMessage(eMeta, contentLength, offset)
		}
	}
}