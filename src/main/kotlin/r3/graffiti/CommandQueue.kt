package r3.graffiti

import r3.content.BinaryContent
import r3.content.FileContent
import r3.content.TextContent
import r3.io.log
import r3.pack.BinaryPack
import r3.pack.DirPack
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.source.FileSink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class CommandQueue(
	val p2p: GraffitiP2P,
	val stateManager: StateManager? = null,
	workerCount: Int = 2
) {
	private val workerCounter = AtomicInteger(0)
	private val executor: ExecutorService = Executors.newFixedThreadPool(
		workerCount.coerceAtLeast(1),
		ThreadFactory { runnable ->
			Thread(runnable, "Graffiti-CommandWorker-${workerCounter.incrementAndGet()}").apply {
				isDaemon = true
			}
		}
	)

	private val commands = ConcurrentHashMap<String, Command>()
	private val activeFutures = ConcurrentHashMap<String, Future<*>>()
	private val activeCommandTimestamps = ConcurrentHashMap<String, Long>()

	var onCommandUpdated: ((Command) -> Unit)? = null

	init {
		p2p.activeWatermarkSupplier = { watermark() }
	}

	fun watermark(): Long? = activeCommandTimestamps.values.minOrNull()

	fun isBusy(): Boolean = activeCommandTimestamps.isNotEmpty()

	fun submit(command: Command): Command {
		commands[command.id] = command
		activeCommandTimestamps[command.id] = command.sentTimestamp
		stateManager?.setEncoding(isBusy())
		onCommandUpdated?.invoke(command)

		val future = executor.submit {
			processCommand(command)
		}
		activeFutures[command.id] = future
		return command
	}

	private fun processCommand(command: Command) {
		if (command.status == CommandStatus.CANCELLED) {
			cleanupPayload(command.payload)
			activeCommandTimestamps.remove(command.id)
			activeFutures.remove(command.id)
			stateManager?.setEncoding(isBusy())
			return
		}

		command.status = CommandStatus.PROCESSING
		command.progress = 10
		command.statusMessage = "Processing ${command.type.name}..."
		onCommandUpdated?.invoke(command)

		try {
			val iden = p2p.getIdentityByKey(command.identityKey)
				?: throw IllegalStateException("No identity found for ${command.identityKey}")
			val peer = p2p.getPeerByKey(command.peerKey)
				?: p2p.getIdentityByKey(IdentityKey(command.peerKey.arr))?.asPeer()
				?: throw IllegalStateException("No peer found for ${command.peerKey}")

			val encKey: EncryptedMetaKey = when (val payload = command.payload) {
				is CommandPayload.Text -> {
					command.progress = 30
					command.statusMessage = "Encrypting message..."
					onCommandUpdated?.invoke(command)

					val textContent = TextContent(payload.text)
					p2p.pkeEncrypt(textContent, iden, peer, timestamp = command.sentTimestamp)
				}

				is CommandPayload.FilePayload -> {
					command.progress = 30
					command.statusMessage = "Encrypting ${payload.originalFileName}..."
					onCommandUpdated?.invoke(command)

					val originalName = payload.originalFileName
					val wrappedContent = MutableMetaDataContent(FileContent(payload.stagedFile)).apply {
						path = originalName
						ext = originalName.substringAfterLast('.', "").lowercase()
					}
					try {
						p2p.pkeEncrypt(wrappedContent, iden, peer, timestamp = command.sentTimestamp)
					} finally {
						if (payload.stagedFile.exists()) {
							payload.stagedFile.delete()
						}
					}
				}

				is CommandPayload.PackPayload -> {
					command.progress = 25
					command.statusMessage = "Compiling pack archive: ${payload.packName}..."
					onCommandUpdated?.invoke(command)

					val tempPackFile = File(p2p.tmpDir, "pack_${command.id}.pack")
					try {
						val dirPack = DirPack(payload.stagingDir)
						val sink = FileSink(tempPackFile, append = false)
						BinaryPack.create(dirPack, sink)

						command.progress = 60
						command.statusMessage = "Encrypting pack archive..."
						onCommandUpdated?.invoke(command)

						val wrappedContent = MutableMetaDataContent(FileContent(tempPackFile)).apply {
							path = payload.packName
							ext = "pack"
						}
						p2p.pkeEncrypt(wrappedContent, iden, peer, timestamp = command.sentTimestamp)
					} finally {
						payload.stagingDir.deleteRecursively()
						if (tempPackFile.exists()) {
							tempPackFile.delete()
						}
					}
				}

				is CommandPayload.Forward -> {
					command.progress = 30
					command.statusMessage = "Preparing forward message..."
					onCommandUpdated?.invoke(command)

					if (!p2p.hasContent(payload.sourceMessageKey)) {
						throw IllegalStateException("Message content is not available locally for ${payload.sourceMessageKey}")
					}
					val content = p2p.getContent(payload.sourceMessageKey)
					val wrappedContent = MutableMetaDataContent(
						content,
						lastModified = command.sentTimestamp
					)

					command.progress = 60
					command.statusMessage = "Encrypting forward message..."
					onCommandUpdated?.invoke(command)

					p2p.pkeEncrypt(wrappedContent, iden, peer, timestamp = command.sentTimestamp)
				}
			}

			p2p.pushNewMessage(encKey)
			command.resultKey = encKey
			command.status = CommandStatus.COMPLETED
			command.progress = 100
			command.statusMessage = "Completed"
			stateManager?.onMessagesChanged()
		} catch (e: Exception) {
			log("Command ${command.id} (${command.type}) failed: ${e.message}")
			command.status = CommandStatus.FAILED
			command.error = e.message ?: "Execution failed"
			command.statusMessage = "Failed: ${command.error}"
		} finally {
			activeCommandTimestamps.remove(command.id)
			activeFutures.remove(command.id)
			stateManager?.setEncoding(isBusy())
			onCommandUpdated?.invoke(command)
		}
	}

	fun cancel(commandId: String): Boolean {
		val command = commands[commandId] ?: return false
		if (command.status.isTerminal) return false

		val future = activeFutures[commandId]
		future?.cancel(true)
		command.status = CommandStatus.CANCELLED
		command.statusMessage = "Cancelled"
		activeCommandTimestamps.remove(commandId)
		activeFutures.remove(commandId)
		cleanupPayload(command.payload)
		stateManager?.setEncoding(isBusy())
		onCommandUpdated?.invoke(command)
		return true
	}

	private fun cleanupPayload(payload: CommandPayload) {
		when (payload) {
			is CommandPayload.FilePayload -> {
				if (payload.stagedFile.exists()) {
					payload.stagedFile.delete()
				}
			}
			is CommandPayload.PackPayload -> {
				payload.stagingDir.deleteRecursively()
			}
			else -> {}
		}
	}

	fun get(commandId: String): Command? = commands[commandId]

	fun listActive(): List<Command> = commands.values.filter { !it.status.isTerminal }

	fun listAll(): List<Command> = commands.values.sortedByDescending { it.createdAt }

	fun shutdown() {
		executor.shutdownNow()
		commands.values.forEach { cmd ->
			if (!cmd.status.isTerminal) {
				cleanupPayload(cmd.payload)
			}
		}
	}
}
