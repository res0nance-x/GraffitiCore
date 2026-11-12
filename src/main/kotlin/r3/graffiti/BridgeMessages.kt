package r3.graffiti

import r3.io.Writable
import r3.pke.PeerKey
import r3.source.ListWritable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Entry returned by a Bridge describing a connected peer and its assigned negative virtual port.
 */
class BridgePeerEntry(
	val peerKey: PeerKey,
	val virtualPort: Int
) : Writable {
	override fun write(dos: DataOutputStream) {
		peerKey.write(dos)
		dos.writeInt(virtualPort)
	}

	companion object {
		fun read(dis: DataInputStream): BridgePeerEntry {
			val peerKey = PeerKey.read(dis)
			val virtualPort = dis.readInt()
			return BridgePeerEntry(peerKey, virtualPort)
		}
	}
}

/**
 * Sent by a client to a connected Bridge to request the list of other peers currently
 * connected to that Bridge (used by BridgeDiscovery).
 */
class BridgeListRequestMessage(
	val requestId: Long
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
	}

	companion object {
		const val type = "bridgelistrequest"
		fun read(dis: DataInputStream): BridgeListRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			return BridgeListRequestMessage(requestId)
		}
	}
}

/**
 * Sent by a Bridge in response to [BridgeListRequestMessage].
 */
class BridgeListResponseMessage(
	val requestId: Long,
	val entries: List<BridgePeerEntry>
) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		dos.writeLong(requestId)
		ListWritable(entries).write(dos)
	}

	companion object {
		const val type = "bridgelistresponse"
		fun read(dis: DataInputStream): BridgeListResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val requestId = dis.readLong()
			val entries = ListWritable.read(dis, BridgePeerEntry::read).list
			return BridgeListResponseMessage(requestId, entries)
		}
	}
}
