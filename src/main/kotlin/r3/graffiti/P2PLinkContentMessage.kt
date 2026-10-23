package r3.graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Header message sent along with the streaming file data for an on-demand p2plink transfer.
 */
class P2PLinkContentMessage(
	val transferId: String,
	val status: Int = STATUS_OK,
	val length: Long = 0L
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		StringWritable(transferId).write(dos)
		dos.writeInt(status)
		dos.writeLong(length)
	}

	companion object {
		const val type = "p2plinkcontent"

		const val STATUS_OK = 0
		const val STATUS_NOT_FOUND = 1
		const val STATUS_ERROR = 2

		fun read(dis: DataInputStream): P2PLinkContentMessage {
			val msgType = StringWritable.read(dis).str
			if (msgType != type) error("Invalid message type: $msgType (expected $type)")
			val transferId = StringWritable.read(dis).str
			val status = dis.readInt()
			val length = dis.readLong()
			return P2PLinkContentMessage(transferId, status, length)
		}
	}
}
