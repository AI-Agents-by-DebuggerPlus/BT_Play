package com.taskertowpf.androidchatcopyv1.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FileMessagePayload(
    val type: String = "file",
    val name: String = "",
    val bucket: String = FileTransferConstants.CHAT_FILES_BUCKET,
    val path: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0L,
    /** Камера AndroidChat: "photo". Пусто — обычный файл (XML и т.п.). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val kind: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val nonce: String = "",
)

object FileMessageFormat {
    const val KIND_PHOTO = "photo"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isCameraPhoto(payload: FileMessagePayload): Boolean =
        payload.kind.equals(KIND_PHOTO, ignoreCase = true)
            || payload.mime.startsWith("image/", ignoreCase = true)

    fun tryParse(content: String): FileMessagePayload? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }

        return runCatching {
            val payload = json.decodeFromString<FileMessagePayload>(trimmed)
            if (!payload.type.equals("file", ignoreCase = true)) {
                null
            } else if (payload.path.isBlank() || payload.name.isBlank()) {
                null
            } else {
                payload
            }
        }.getOrNull()
    }

    fun buildContent(payload: FileMessagePayload): String = json.encodeToString(payload)

    fun toDisplayText(payload: FileMessagePayload): String =
        "📎 ${payload.name} (${formatSize(payload.size)})"

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0)
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
