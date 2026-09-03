package com.taskertowpf.androidchatbttest98.hrt

import com.taskertowpf.androidchatbttest98.LogMessageFormat
import com.taskertowpf.androidchatbttest98.data.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object HrtProtocol {
    const val RECIPIENT = "RemoteTerminal"
    const val COMMAND_RECIPIENT = "Hermes.Mt5Terminal"
    const val SENDER = "HrtMobile"
    const val SCREENSHOT_SHOW_MS = 10_000L
    const val SNAPSHOT_LIMIT = 40

    fun isRemoteTerminal(message: ChatMessage): Boolean =
        message.recipientName.trim().equals(RECIPIENT, ignoreCase = true)

    fun isHwtContent(content: String): Boolean {
        val type = peekType(content) ?: return looksLikeRawStatus(content)
        return type.equals("hwt_status", ignoreCase = true)
            || type.equals("hwt_screenshot", ignoreCase = true)
            || type.equals("hwt_screenshot_repeat", ignoreCase = true)
    }

    fun peekType(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val root = json.parseToJsonElement(trimmed).jsonObject
            root["type"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    fun looksLikeRawStatus(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return false
        return runCatching {
            val root = json.parseToJsonElement(trimmed).jsonObject
            root.containsKey("symbol")
                || root.containsKey("bid")
                || root.containsKey("real_trading")
                || root.containsKey("account")
        }.getOrDefault(false)
    }

    private val json = Json { ignoreUnknownKeys = true }
}

data class HwtStatus(
    val source: String = "",
    val utc: String = "",
    val build: String = "",
    val note: String = "",
    val symbol: String = "",
    val bid: String = "",
    val ask: String = "",
    val lot: String = "",
    val account: String = "",
    val marketStatus: String = "",
    val realTrading: Boolean = false,
    val autoTrade: Boolean = false,
    val positionsHeader: String = "",
    val positions: List<String> = emptyList(),
    val pendingOrders: List<String> = emptyList(),
) {
    val accountDisplay: String
        get() = account
            .replace(" | ", "\n")
            .replace(Regex(" {2,}"), "\n")
            .trim()
            .ifBlank { "—" }

    val openPositions: List<String>
        get() = positions.filterNot { looksPending(it) }

    val resolvedPending: List<String>
        get() = pendingOrders.ifEmpty { positions.filter { looksPending(it) } }

    companion object {
        fun tryParse(content: String): HwtStatus? {
            val trimmed = content.trim()
            if (!trimmed.startsWith("{")) return null
            return runCatching {
                val root = json.parseToJsonElement(trimmed).jsonObject
                val type = root.string("type")
                val raw = looksLikeRaw(root)
                if (!type.equals("hwt_status", ignoreCase = true) && !raw) {
                    return@runCatching null
                }
                val status = HwtStatus(
                    utc = root.string("utc"),
                    build = root.string("build"),
                    note = root.string("note"),
                    symbol = root.string("symbol"),
                    bid = root.string("bid"),
                    ask = root.string("ask"),
                    lot = root.string("lot"),
                    account = root.string("account"),
                    marketStatus = root.string("market_status"),
                    realTrading = root.bool("real_trading"),
                    autoTrade = root.bool("auto_trade"),
                    positionsHeader = root.string("positions_header"),
                    positions = root.stringList("positions"),
                    pendingOrders = root.stringList("pending_orders").ifEmpty {
                        root.stringList("pending")
                    },
                )
                if (raw || status.positions.isNotEmpty() || status.pendingOrders.isNotEmpty()) {
                    status
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun looksLikeRaw(root: JsonObject): Boolean =
            root.containsKey("symbol")
                || root.containsKey("bid")
                || root.containsKey("real_trading")
                || root.containsKey("account")

        private fun looksPending(line: String): Boolean {
            val s = line.lowercase()
            return s.contains("pending")
                || s.contains("buy limit")
                || s.contains("sell limit")
                || s.contains("buy stop")
                || s.contains("sell stop")
                || s.contains("отлож")
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

data class HwtScreenshot(
    val name: String,
    val bucket: String?,
    val path: String?,
    val dataBase64: String?,
    val replay: Boolean,
    val nonce: String?,
) {
    val dedupKey: String
        get() = when {
            !nonce.isNullOrBlank() -> "n:$nonce"
            !path.isNullOrBlank() -> path
            !dataBase64.isNullOrBlank() -> "b64:${dataBase64.length}:$name"
            else -> name
        }

    companion object {
        fun tryParseRepeat(content: String): Boolean {
            val type = HrtProtocol.peekType(content) ?: return false
            return type.equals("hwt_screenshot_repeat", ignoreCase = true)
        }

        fun tryParse(content: String): HwtScreenshot? {
            val trimmed = content.trim()
            if (!trimmed.startsWith("{")) return null
            return runCatching {
                val root = json.parseToJsonElement(trimmed).jsonObject
                val type = root.string("type")
                when {
                    type.equals("hwt_screenshot", ignoreCase = true) -> {
                        val shot = HwtScreenshot(
                            name = root.string("name").ifBlank { "shot.png" },
                            bucket = root.string("bucket").ifBlank { null },
                            path = root.string("path").ifBlank { null },
                            dataBase64 = root.string("data_base64").ifBlank { null },
                            replay = root.bool("replay"),
                            nonce = root.string("nonce").ifBlank { null },
                        )
                        if (shot.path.isNullOrBlank() && shot.dataBase64.isNullOrBlank()) null else shot
                    }
                    type.equals("file", ignoreCase = true) -> {
                        val mime = root.string("mime")
                        if (!mime.startsWith("image/", ignoreCase = true)) {
                            return@runCatching null
                        }
                        val shot = HwtScreenshot(
                            name = root.string("name").ifBlank { "file.png" },
                            bucket = root.string("bucket").ifBlank { "chat-files" },
                            path = root.string("path").ifBlank { null },
                            dataBase64 = null,
                            replay = false,
                            nonce = null,
                        )
                        if (shot.path.isNullOrBlank()) null else shot
                    }
                    else -> null
                }
            }.getOrNull()
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

data class HrtSnapshot(
    val statusMessage: ChatMessage? = null,
    val screenshotMessage: ChatMessage? = null,
)

data class HrtUiState(
    val status: HwtStatus? = null,
    val feed: List<String> = emptyList(),
    val statusText: String = "HRT: ожидание снимка",
    val isBusy: Boolean = false,
    val screenshotVisible: Boolean = false,
    val screenshotBytes: ByteArray? = null,
    val screenshotLabel: String = "",
    val screenshotError: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HrtUiState) return false
        return status == other.status
            && feed == other.feed
            && statusText == other.statusText
            && isBusy == other.isBusy
            && screenshotVisible == other.screenshotVisible
            && screenshotLabel == other.screenshotLabel
            && screenshotError == other.screenshotError
            && screenshotBytes.contentEquals(other.screenshotBytes)
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + feed.hashCode()
        result = 31 * result + statusText.hashCode()
        result = 31 * result + isBusy.hashCode()
        result = 31 * result + screenshotVisible.hashCode()
        result = 31 * result + (screenshotBytes?.contentHashCode() ?: 0)
        result = 31 * result + screenshotLabel.hashCode()
        result = 31 * result + screenshotError.hashCode()
        return result
    }
}

internal fun JsonObject.string(key: String): String {
    val el = this[key] ?: return ""
    return when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        else -> ""
    }
}

internal fun JsonObject.bool(key: String): Boolean {
    val el = this[key] as? JsonPrimitive ?: return false
    return el.booleanOrNull == true
}

internal fun JsonObject.stringList(key: String): List<String> {
    val arr = this[key] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.distinct()
}

fun ChatMessage.isHrtLog(): Boolean =
    LogMessageFormat.isLogContent(content)
        || content.trimStart().startsWith("[LOG:", ignoreCase = true)
