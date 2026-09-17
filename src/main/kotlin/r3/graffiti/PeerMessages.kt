package r3.graffiti

import r3.io.Writable
import r3.pke.Peer
import r3.pke.PeerKey
import r3.source.ListWritable
import r3.source.StringWritable
import java.io.DataInputStream
import java.io.DataOutputStream

// Sent by node A to node B requesting peer public keys for a list of author/peer keys.
class PeerRequestMessage(val keys: List<PeerKey>) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		ListWritable(keys).write(dos)
	}

	companion object {
		val type = "peerrequest"
		fun read(dis: DataInputStream): PeerRequestMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val keys = ListWritable.read(dis, PeerKey::read).list
			return PeerRequestMessage(keys)
		}
	}
}

// Sent in response to PeerRequestMessage, returning the requested Peer objects.
class PeerResponseMessage(val peers: List<Peer>) : Writable {
	override fun write(dos: DataOutputStream) {
		StringWritable(type).write(dos)
		ListWritable(peers).write(dos)
	}

	companion object {
		val type = "peerresponse"
		fun read(dis: DataInputStream): PeerResponseMessage {
			val type = StringWritable.read(dis).str
			if (type != this.type) error("Invalid message type: $type")
			val peers = ListWritable.read(dis, Peer::read).list
			return PeerResponseMessage(peers)
		}
	}
}
