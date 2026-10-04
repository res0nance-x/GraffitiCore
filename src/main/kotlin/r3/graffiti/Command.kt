package r3.graffiti

import r3.org.json.JSONObject
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.pke.PeerKey
import java.io.File
import java.util.UUID

enum class CommandType {
	SEND_TEXT,
	SEND_FILE,
	CREATE_PACK,
	FORWARD,
	SEND_BELL
}

enum class CommandStatus {
	QUEUED,
	PROCESSING,
	COMPLETED,
	FAILED,
	CANCELLED;

	val isTerminal: Boolean
		get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

sealed interface CommandPayload {
	data class Text(val text: String) : CommandPayload
	data class FilePayload(val stagedFile: File, val originalFileName: String) : CommandPayload
	data class PackPayload(val stagingDir: File, val packName: String) : CommandPayload
	data class Forward(val sourceMessageKey: EncryptedMetaKey) : CommandPayload
	object Bell : CommandPayload
}

data class Command(
	val id: String = UUID.randomUUID().toString(),
	val type: CommandType,
	val identityKey: IdentityKey,
	val peerKey: PeerKey,
	val urgent: Boolean = false,
	val sentTimestamp: Long,
	val payload: CommandPayload,
	@Volatile var status: CommandStatus = CommandStatus.QUEUED,
	@Volatile var progress: Int = 0,
	@Volatile var statusMessage: String? = null,
	@Volatile var error: String? = null,
	@Volatile var resultKey: EncryptedMetaKey? = null,
	val createdAt: Long = System.currentTimeMillis()
) {
	fun toJson(): JSONObject = JSONObject().apply {
		put("id", id)
		put("type", type.name)
		put("identityKey", identityKey.toString())
		put("peerKey", peerKey.toString())
		put("urgent", urgent)
		put("sentTimestamp", sentTimestamp)
		put("status", status.name)
		put("progress", progress)
		put("createdAt", createdAt)
		statusMessage?.let { put("statusMessage", it) }
		error?.let { put("error", it) }
		resultKey?.let { put("resultKey", it.toString()) }
		when (payload) {
			is CommandPayload.Text -> put("text", payload.text)
			is CommandPayload.FilePayload -> put("fileName", payload.originalFileName)
			is CommandPayload.PackPayload -> put("packName", payload.packName)
			is CommandPayload.Forward -> put("sourceMessageKey", payload.sourceMessageKey.toString())
			is CommandPayload.Bell -> put("bell", true)
		}
	}
}
