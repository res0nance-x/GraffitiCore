package graffiti

// Lightweight liveness messages — no payload needed.
// PingMessage is sent by GraffitiP2P's ping scheduler to every connected node every PING_INTERVAL_MS.
// The remote replies with PongMessage. Any received block (real data or pong) resets lastActivityMs
// in TCPNode, so data-active connections are never incorrectly timed out.
object PingMessage {
	val type = "ping"
}

object PongMessage {
	val type = "pong"
}

