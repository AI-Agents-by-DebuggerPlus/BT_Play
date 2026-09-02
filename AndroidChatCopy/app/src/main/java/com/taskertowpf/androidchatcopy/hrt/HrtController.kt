package com.taskertowpf.androidchatcopy.hrt

import android.util.Base64
import com.taskertowpf.androidchatcopy.data.AppSettings
import com.taskertowpf.androidchatcopy.data.ChatMessage
import com.taskertowpf.androidchatcopy.data.ChatRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HrtController(
    private val chatRepository: ChatRepository,
    private val scope: CoroutineScope,
    private val logLocal: (String, String) -> Unit,
    private val settings: () -> AppSettings,
) {
    private val _state = MutableStateFlow(HrtUiState())
    val state: StateFlow<HrtUiState> = _state.asStateFlow()

    private val seenMessageIds = linkedSetOf<String>()
    private val seenShotKeys = linkedSetOf<String>()
    private var cachedShotBytes: ByteArray? = null
    private var cachedShotLabel: String = ""
    private var hideShotJob: Job? = null
    private var bootstrapped = false

    fun onMessage(message: ChatMessage, fromPoll: Boolean = false) {
        if (!HrtProtocol.isRemoteTerminal(message) || message.isHrtLog()) {
            return
        }
        if (!accept(message)) {
            return
        }
        scope.launch {
            handle(message, fromPoll)
        }
    }

    fun openAndBootstrap() {
        if (bootstrapped && _state.value.status != null) {
            return
        }
        pullSnapshot(includeScreenshot = false, reason = "bootstrap")
    }

    fun pullSnapshot(includeScreenshot: Boolean = true, reason: String = "pull") {
        scope.launch {
            _state.update { it.copy(isBusy = true, statusText = "HRT: загрузка снимка…") }
            val result = chatRepository.fetchHrtSnapshot(includeScreenshot)
            result.fold(
                onSuccess = { snap ->
                    bootstrapped = true
                    if (snap.statusMessage == null && snap.screenshotMessage == null) {
                        _state.update {
                            it.copy(
                                isBusy = false,
                                statusText = "HWT: нет снимка — нажмите «Обновить»",
                            )
                        }
                        appendFeed("HWT $reason: пусто (нужен refresh → hwt_status)")
                        logLocal("HRT", "$reason: no hwt_status in Supabase")
                        return@fold
                    }
                    snap.statusMessage?.let { onMessage(it, fromPoll = true) }
                    snap.screenshotMessage?.let { onMessage(it, fromPoll = true) }
                    _state.update { it.copy(isBusy = false) }
                    logLocal("HRT", "$reason ok")
                },
                onFailure = { error ->
                    val text = error.message ?: "ошибка снимка"
                    _state.update { it.copy(isBusy = false, statusText = "HRT: $text") }
                    logLocal("HRT", "$reason: $text")
                },
            )
        }
    }

    fun sendRefresh() = sendCommand("refresh", "Обновление HWT…")

    fun sendScreenshot() = sendCommand("screenshot", "Запрос скрина HWT…")

    fun sendRepeat() = sendCommand("повтор", "Повтор последнего скрина…")

    fun dismissScreenshot() {
        hideShotJob?.cancel()
        hideShotJob = null
        _state.update { it.copy(screenshotVisible = false) }
    }

    fun tapScreenshot() {
        val bytes = _state.value.screenshotBytes ?: cachedShotBytes ?: return
        showScreenshot(bytes, _state.value.screenshotLabel.ifBlank { cachedShotLabel }, restart = true)
    }

    private fun sendCommand(content: String, waiting: String) {
        scope.launch {
            _state.update { it.copy(isBusy = true, statusText = waiting) }
            val result = chatRepository.sendRoutedMessage(
                settings = settings(),
                senderName = HrtProtocol.SENDER,
                recipientName = HrtProtocol.COMMAND_RECIPIENT,
                content = content,
            )
            result.fold(
                onSuccess = {
                    appendFeed("cmd → ${HrtProtocol.COMMAND_RECIPIENT}: $content")
                    logLocal("HRT", "Sent $content → ${HrtProtocol.COMMAND_RECIPIENT}")
                    _state.update {
                        it.copy(isBusy = false, statusText = "Команда «$content» отправлена · ждём ответ")
                    }
                },
                onFailure = { error ->
                    val text = error.message ?: "ошибка отправки"
                    logLocal("HRT", "Send $content failed: $text")
                    _state.update { it.copy(isBusy = false, statusText = "HRT: $text") }
                },
            )
        }
    }

    private suspend fun handle(message: ChatMessage, fromPoll: Boolean) {
        if (HwtScreenshot.tryParseRepeat(message.content)) {
            replayCached(message)
            return
        }
        val shot = HwtScreenshot.tryParse(message.content)
        if (shot != null) {
            handleScreenshot(message, shot)
            return
        }
        val status = HwtStatus.tryParse(message.content)
        if (status != null) {
            val sourced = status.copy(source = if (fromPoll) "poll" else "supabase")
            _state.update {
                it.copy(
                    status = sourced,
                    statusText = "HWT ${sourced.symbol.ifBlank { "—" }} · ${formatTime(message.createdAt)}",
                    screenshotError = "",
                )
            }
            appendFeed("${formatTime(message.createdAt)}  HWT update (${message.senderName})")
            return
        }
        appendFeed(
            "${formatTime(message.createdAt)}  ${message.senderName} → ${message.recipientName}  " +
                flatten(message.content),
        )
    }

    private suspend fun handleScreenshot(message: ChatMessage, shot: HwtScreenshot) {
        if (!shot.replay && !seenShotKeys.add(shot.dedupKey)) {
            return
        }
        if (shot.replay) {
            seenShotKeys.add(shot.dedupKey)
        }
        val bytes = decodeOrDownload(shot)
        if (bytes == null || bytes.isEmpty()) {
            if (shot.replay) {
                replayCached(message)
                return
            }
            _state.update { it.copy(statusText = "Screenshot: не удалось скачать файл") }
            appendFeed("${formatTime(message.createdAt)}  screenshot FAIL (${shot.name})")
            return
        }
        val label = shot.name + if (shot.replay) " (повтор · 10 с)" else ""
        appendFeed("${formatTime(message.createdAt)}  screenshot ← ${message.senderName} · ${shot.name}")
        showScreenshot(bytes, label, restart = true)
    }

    private fun replayCached(message: ChatMessage) {
        val cached = cachedShotBytes
        if (cached == null || cached.isEmpty()) {
            _state.update {
                it.copy(statusText = "Screenshot repeat: нет кэша — нужен новый Screenshot")
            }
            appendFeed("${formatTime(message.createdAt)}  screenshot REPEAT FAIL (нет кэша)")
            return
        }
        appendFeed("${formatTime(message.createdAt)}  screenshot REPEAT (кэш · 10 с)")
        showScreenshot(cached, cachedShotLabel.ifBlank { "повтор" }, restart = true)
    }

    private suspend fun decodeOrDownload(shot: HwtScreenshot): ByteArray? {
        if (!shot.dataBase64.isNullOrBlank()) {
            return runCatching {
                Base64.decode(shot.dataBase64, Base64.DEFAULT)
            }.getOrNull()
        }
        val path = shot.path ?: return null
        val bucket = shot.bucket?.ifBlank { null } ?: "chat-files"
        return chatRepository.downloadStorageObject(bucket, path).getOrNull()
    }

    private fun showScreenshot(bytes: ByteArray, label: String, restart: Boolean) {
        cachedShotBytes = bytes
        cachedShotLabel = label
        hideShotJob?.cancel()
        _state.update {
            it.copy(
                screenshotVisible = true,
                screenshotBytes = bytes,
                screenshotLabel = label,
                screenshotError = "",
                statusText = "Скрин · 10 с · $label",
            )
        }
        if (!restart) return
        hideShotJob = scope.launch {
            delay(HrtProtocol.SCREENSHOT_SHOW_MS)
            _state.update {
                it.copy(
                    screenshotVisible = false,
                    statusText = "Скрин скрыт",
                )
            }
        }
    }

    private fun accept(message: ChatMessage): Boolean {
        val id = message.id.trim()
        if (id.isNotEmpty()) {
            if (seenMessageIds.contains(id)) return false
            seenMessageIds.add(id)
            if (seenMessageIds.size > 2000) {
                seenMessageIds.clear()
                seenShotKeys.clear()
                seenMessageIds.add(id)
            }
            return true
        }
        return true
    }

    private fun appendFeed(line: String) {
        _state.update { current ->
            current.copy(feed = (current.feed + line).takeLast(40))
        }
    }

    private fun formatTime(createdAt: String): String =
        runCatching {
            OffsetDateTime.parse(createdAt).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }.getOrElse {
            DateTimeFormatter.ofPattern("HH:mm:ss").format(OffsetDateTime.now())
        }

    private fun flatten(content: String): String =
        content.replace("\r\n", " ¶ ").replace('\n', '¶').replace('\r', '¶').take(160)

}
