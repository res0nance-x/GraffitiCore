package r3.graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Sent by a peer over TCPNode to request an on-demand direct transfer for a p2plink.
 */
class P2PLinkRequestMessage(
	val transferId: String,
	val offset: Long = 0L
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		StringWritable(transferId).write(dos)
		dos.writeLong(offset)
	}

	companion object {
		const val type = "p2plinkrequest"

		fun read(dis: DataInputStream): P2PLinkRequestMessage {
			val msgType = StringWritable.read(dis).str
			if (msgType != type) error("Invalid message type: $msgType (expected $type)")
			val transferId = StringWritable.read(dis).str
			val offset = dis.readLong()
			return P2PLinkRequestMessage(transferId, offset)
		}
	}
}
