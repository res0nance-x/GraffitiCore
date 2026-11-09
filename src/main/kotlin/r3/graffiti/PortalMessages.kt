package r3.graffiti

import r3.io.Writable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Sent by client to host to request the list of files in the host's Portal directory.
 */
class PortalListRequestMessage(
	val requestId: Long
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
	}

	companion object {
		const val type = "portallistrequest"
		fun read(dis: DataInputStream): PortalListRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			return PortalListRequestMessage(requestId)
		}
	}
}

/**
 * Header sent by host in response to [PortalListRequestMessage].
 * Followed by a serialized `ListWritable<ContentMeta>` body.
 */
class PortalListResponseMessage(
	val requestId: Long
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
	}

	companion object {
		const val type = "portallistresponse"
		fun read(dis: DataInputStream): PortalListResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			return PortalListResponseMessage(requestId)
		}
	}
}

/**
 * Stateless chunk request sent by [r3.net.tcp.TCPInputStream] to fetch up to [maxLength] bytes
 * (default 64 KB) starting at byte offset [pos] for the portal file at [path].
 */
class PortalChunkRequestMessage(
	val requestId: Long,
	val path: String,
	val pos: Long,
	val maxLength: Int = 65536
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
		StringWritable(path).write(dos)
		dos.writeLong(pos)
		dos.writeInt(maxLength)
	}

	companion object {
		const val type = "portalchunkrequest"
		fun read(dis: DataInputStream): PortalChunkRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			val path = StringWritable.read(dis).str
			val pos = dis.readLong()
			val maxLength = dis.readInt()
			return PortalChunkRequestMessage(requestId, path, pos, maxLength)
		}
	}
}

/**
 * Header sent by host in response to [PortalChunkRequestMessage], accompanied by the raw
 * chunk bytes in the stream body (typically a single 64 KB `TCPBlock`).
 */
class PortalChunkResponseMessage(
	val requestId: Long,
	val status: Byte = STATUS_OK
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
		dos.writeByte(status.toInt())
	}

	companion object {
		const val type = "portalchunkresponse"
		const val STATUS_OK: Byte = 0
		const val STATUS_NOT_FOUND: Byte = 1
		const val STATUS_ERROR: Byte = 2

		fun read(dis: DataInputStream): PortalChunkResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			val status = dis.readByte()
			return PortalChunkResponseMessage(requestId, status)
		}
	}
}
