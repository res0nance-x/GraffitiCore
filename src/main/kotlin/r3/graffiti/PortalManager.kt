package r3.graffiti

import r3.content.ContentMeta
import r3.http.resolveFile
import r3.io.consistentPath
import r3.io.debug
import r3.io.getExtension
import r3.io.makeRelativePath
import r3.io.serialize
import r3.io.toDataInputStream
import r3.net.tcp.TCPInputStream
import r3.net.tcp.TCPNode
import r3.net.tcp.TCPPack
import r3.net.tcp.TCPPackClient
import r3.pke.name
import r3.source.ListWritable
import java.io.File
import java.io.RandomAccessFile

/**
 * Manages the local and remote Portal virtual filesystem ([TCPPack]) for a [GraffitiP2P] node.
 */
class PortalManager(
	private val p2p: GraffitiP2P,
	private val requestTracker: NodeRequestTracker
) {
	private fun generateDefaultPortalHtml(): ByteArray {
		val nodeName = p2p.serverIdentity.key.name
		val html = """
			<!DOCTYPE html>
			<html lang="en">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>$nodeName — Portal</title>
				<style>
					:root {
						color-scheme: dark light;
					}
					body {
						margin: 0;
						min-height: 100vh;
						display: flex;
						align-items: center;
						justify-content: center;
						font-family: system-ui, -apple-system, sans-serif;
						background: #121614;
						color: #e2e8e4;
						padding: 1.5rem;
						box-sizing: border-box;
					}
					.card {
						max-width: 520px;
						width: 100%;
						background: rgba(255, 255, 255, 0.04);
						border: 1px solid rgba(255, 255, 255, 0.12);
						border-radius: 12px;
						padding: 2rem;
						text-align: center;
						box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
					}
					.icon {
						font-size: 3rem;
						margin-bottom: 0.75rem;
					}
					h1 {
						margin: 0 0 0.5rem;
						font-size: 1.4rem;
					}
					p {
						margin: 0.5rem 0;
						line-height: 1.5;
						opacity: 0.82;
						font-size: 0.95rem;
					}
					.hint {
						margin-top: 1.25rem;
						padding: 0.75rem 1rem;
						background: rgba(0, 0, 0, 0.25);
						border-radius: 8px;
						font-size: 0.82rem;
						opacity: 0.7;
					}
				</style>
			</head>
			<body>
				<div class="card">
					<div class="icon">🌐</div>
					<h1>$nodeName's Portal</h1>
					<p>This node has not customized their portal yet.</p>
					<div class="hint">
						To customize your portal, add media files or an <code>index.html</code> web app to your node's Portal folder.
					</div>
				</div>
			</body>
			</html>
		""".trimIndent()
		return html.toByteArray(Charsets.UTF_8)
	}

	private fun isPortalFileAllowed(file: File): Boolean {
		val portalDir = p2p.portalDir
		var cur: File? = file
		val root = portalDir.canonicalFile
		while (cur != null && cur.canonicalFile != root) {
			if (cur.name.startsWith(".")) return false
			cur = cur.parentFile
		}
		return file.isFile && !file.name.startsWith(".")
	}

	fun getLocalPortalMetaList(): List<ContentMeta> {
		val portalDir = p2p.portalDir
		if (!portalDir.exists()) {
			portalDir.mkdirs()
		}
		val files = portalDir.walkTopDown()
			.onEnter { dir -> dir == portalDir || !dir.name.startsWith(".") }
			.filter { it.isFile && !it.name.startsWith(".") }
			.toList()
		if (files.isEmpty()) {
			val defaultBytes = generateDefaultPortalHtml()
			return listOf(ContentMeta("index.html", "html", defaultBytes.size.toLong(), System.currentTimeMillis()))
		}
		val rootPath = portalDir.consistentPath()
		return files.map { f ->
			val relPath = makeRelativePath(portalDir, f).let {
				if (f.consistentPath().startsWith(rootPath)) f.consistentPath().substring(rootPath.length) else it
			}
			val ext = getExtension(f.name)
			ContentMeta(relPath, ext, f.length(), f.lastModified())
		}.sortedBy { it.name }
	}

	fun readLocalPortalChunk(path: String, pos: Long, maxLength: Int): ByteArray? {
		if (pos < 0L || maxLength <= 0) return ByteArray(0)
		val cappedMax = maxLength.coerceIn(1, TCPInputStream.DEFAULT_CHUNK_SIZE)
		val portalDir = p2p.portalDir
		if (!portalDir.exists()) {
			portalDir.mkdirs()
		}
		val cleanRel = path.trimStart('/', '\\')
		val targetFile = try {
			resolveFile(portalDir, cleanRel)
		} catch (_: Exception) {
			return null
		}
		if (targetFile.exists() && isPortalFileAllowed(targetFile)) {
			val fileLen = targetFile.length()
			if (pos >= fileLen) return ByteArray(0)
			val toRead = minOf(cappedMax.toLong(), fileLen - pos).toInt()
			val buf = ByteArray(toRead)
			RandomAccessFile(targetFile, "r").use { raf ->
				raf.seek(pos)
				var totalRead = 0
				while (totalRead < toRead) {
					val r = raf.read(buf, totalRead, toRead - totalRead)
					if (r <= 0) break
					totalRead += r
				}
				return if (totalRead == toRead) buf else buf.copyOf(totalRead)
			}
		}
		val hasAnyFiles = portalDir.walkTopDown()
			.onEnter { dir -> dir == portalDir || !dir.name.startsWith(".") }
			.any { it.isFile && !it.name.startsWith(".") }
		if (!hasAnyFiles && (cleanRel.equals("index.html", ignoreCase = true) || cleanRel.isEmpty())) {
			val defaultBytes = generateDefaultPortalHtml()
			if (pos >= defaultBytes.size) return ByteArray(0)
			val end = minOf(defaultBytes.size, (pos + cappedMax).toInt())
			return defaultBytes.copyOfRange(pos.toInt(), end)
		}
		return null
	}

	fun createPortalPack(node: TCPNode? = null): TCPPack {
		if (node == null) {
			val selfClient = object : TCPPackClient {
				override fun listContent(): List<ContentMeta> = getLocalPortalMetaList()
				override fun readChunk(path: String, pos: Long, maxLength: Int): ByteArray =
					readLocalPortalChunk(path, pos, maxLength) ?: ByteArray(0)
			}
			return TCPPack(selfClient)
		}
		val remoteClient = object : TCPPackClient {
			override fun listContent(): List<ContentMeta> {
				return requestTracker.request(
					node = node,
					timeoutMs = 15_000L,
					defaultValue = emptyList(),
					onError = { e -> p2p.logNodeError(node, "Portal listContent timed out or failed: ${e.message}") }
				) { reqId ->
					node.sendPriority(PortalListRequestMessage(reqId).serialize())
				}
			}

			override fun readChunk(path: String, pos: Long, maxLength: Int): ByteArray {
				return requestTracker.request(
					node = node,
					timeoutMs = 15_000L,
					defaultValue = ByteArray(0),
					onError = { e -> p2p.logNodeError(node, "Portal readChunk ($path @ $pos) timed out or failed: ${e.message}") }
				) { reqId ->
					node.sendPriority(
						PortalChunkRequestMessage(
							requestId = reqId,
							path = path,
							pos = pos,
							maxLength = maxLength.coerceIn(1, TCPInputStream.DEFAULT_CHUNK_SIZE)
						).serialize()
					)
				}
			}
		}
		return TCPPack(remoteClient)
	}

	fun handleListRequest(node: TCPNode, rawHead: ByteArray) {
		val req = PortalListRequestMessage.read(rawHead.toDataInputStream())
		p2p.runOnContentSender {
			try {
				val metaList = getLocalPortalMetaList()
				node.send(
					PortalListResponseMessage(req.requestId).serialize(),
					ListWritable(metaList).serialize()
				)
				debug("Served PortalListResponse (${metaList.size} items) to ${node.remoteAddress}")
			} catch (e: Exception) {
				p2p.logNodeError(node, "Error serving PortalListRequest: ${e.message}")
			}
		}
	}

	fun handleListResponse(node: TCPNode, rawHead: ByteArray, file: File?) {
		val resp = PortalListResponseMessage.read(rawHead.toDataInputStream())
		val list = if (file != null && file.exists()) {
			try {
				file.toDataInputStream().use { dis ->
					ListWritable.read(dis, ContentMeta::read).list
				}
			} catch (e: Exception) {
				p2p.logNodeError(node, "Failed to read PortalListResponse body: ${e.message}")
				emptyList()
			}
		} else {
			emptyList()
		}
		requestTracker.complete(resp.requestId, list)
	}

	fun handleChunkRequest(node: TCPNode, rawHead: ByteArray) {
		val req = PortalChunkRequestMessage.read(rawHead.toDataInputStream())
		p2p.runOnContentSender {
			try {
				val chunk = readLocalPortalChunk(req.path, req.pos, req.maxLength)
				if (chunk != null) {
					if (chunk.isNotEmpty()) {
						node.send(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_OK).serialize(), chunk)
					} else {
						node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_OK).serialize())
					}
				} else {
					node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_NOT_FOUND).serialize())
				}
			} catch (e: Exception) {
				p2p.logNodeError(node, "Error serving PortalChunkRequest (${req.path} @ ${req.pos}): ${e.message}")
				try {
					node.sendPriority(PortalChunkResponseMessage(req.requestId, PortalChunkResponseMessage.STATUS_ERROR).serialize())
				} catch (_: Exception) {}
			}
		}
	}

	fun handleChunkResponse(rawHead: ByteArray, file: File?) {
		val resp = PortalChunkResponseMessage.read(rawHead.toDataInputStream())
		val data = if (resp.status == PortalChunkResponseMessage.STATUS_OK && file != null && file.exists()) {
			try {
				file.readBytes()
			} catch (_: Exception) {
				ByteArray(0)
			}
		} else {
			ByteArray(0)
		}
		requestTracker.complete(resp.requestId, data)
	}
}
