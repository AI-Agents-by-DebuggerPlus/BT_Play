package com.taskertowpf.androidchatbttest98.headset

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.taskertowpf.androidchatbttest98.AndroidChatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Гарнитура → AndroidChat.
 *
 * Play / Play-Pause / HeadsetHook = **BT Play** (пауза TTS / STT / next).
 * Остальные кнопки (опционально) → сообщение в чат, если enableHeadsetToChat.
 */
class HeadsetButtonNotifier(
    private val app: AndroidChatApp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastSentKey: String? = null
    private var lastSentAtMs: Long = 0L

    /** Колбэк для UI-счётчика (main/any thread). */
    @Volatile
    var onBtPlayDetected: ((label: String) -> Unit)? = null

    /**
     * Режим изоляции (тесты / урок): без STT/pause/next в чат.
     * [isolatedBtPlayHandler] — локальная реакция на Play (STT тест, генератор урока).
     */
    @Volatile
    var btPlayTestIsolation: Boolean = false

    @Volatile
    var isolatedBtPlayHandler: (() -> Unit)? = null

    fun notifyButton(buttonLabel: String, source: String = "native") {
        val label = HeadsetButtonNames.normalize(buttonLabel)
        val now = System.currentTimeMillis()
        val debounceKey = if (isBtPlayLabel(label)) BT_PLAY_KEY else label

        scope.launch {
            mutex.withLock {
                if (debounceKey == lastSentKey && now - lastSentAtMs < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounced: $label ($source)")
                    return@launch
                }
                lastSentKey = debounceKey
                lastSentAtMs = now
            }

            if (isBtPlayLabel(label)) {
                onBtPlayDetected?.invoke(label)
                if (btPlayTestIsolation) {
                    val handler = isolatedBtPlayHandler
                    if (handler != null) {
                        app.localLogRepository.logLocal(
                            "Headset",
                            "BT Play ($label) via $source → isolated handler",
                        )
                        Log.i(TAG, "BT Play isolated handler: $label ($source)")
                        withContext(Dispatchers.Main) { handler() }
                    } else {
                        app.localLogRepository.logLocal(
                            "Headset",
                            "BT Play ($label) via $source → isolation (только счётчик)",
                        )
                        Log.i(TAG, "BT Play isolation counter: $label ($source)")
                    }
                    return@launch
                }
                app.localLogRepository.logLocal(
                    "Headset",
                    "BT Play ($label) via $source → STT / pause / next",
                )
                Log.i(TAG, "Native BT Play: $label ($source)")
                app.headsetPlayHandler.handleBtPlay(source = "native-bt-play")
                return@launch
            }

            if (btPlayTestIsolation) {
                Log.d(TAG, "Non-play button ignored in test isolation: $label")
                return@launch
            }

            val settings = app.chatRepository.activeSettingsOrNull()
                ?: app.settingsRepository.load()
            if (!settings.enableHeadsetToChat) {
                Log.d(TAG, "Relay skipped (disabled): $label")
                return@launch
            }

            val connectResult = app.chatRepository.ensureConnectedForRelay(settings, scope)
            if (connectResult.isFailure) {
                Log.e(TAG, "Relay connect failed: ${connectResult.exceptionOrNull()?.message}")
                app.localLogRepository.logLocal(
                    "Headset",
                    "Connect error: ${connectResult.exceptionOrNull()?.message}",
                )
                return@launch
            }

            val recipient = settings.recipientName.trim().ifBlank { "(пусто)" }
            val text = "🎧 [$source] $label"
            val result = app.chatRepository.sendMessage(settings, text)
            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "unknown"
                Log.e(TAG, "Relay send failed: $error")
                app.localLogRepository.logLocal("Headset", "Send error → $recipient: $error")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app.applicationContext,
                        "WpfChat: ошибка — $error",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                Log.i(TAG, "Relay sent to chat → $recipient: $label ($source)")
                app.localLogRepository.logLocal("Headset", "$label via $source → $recipient")
            }
        }
    }

    companion object {
        private const val TAG = "HeadsetButtonNotifier"
        private const val DEBOUNCE_MS = 500L
        private const val BT_PLAY_KEY = "BT_PLAY"

        fun isBtPlayLabel(label: String): Boolean {
            val n = label.trim().uppercase()
            return n == "MEDIA_PLAY" ||
                n == "MEDIA_PLAY_PAUSE" ||
                n == "HEADSETHOOK" ||
                n == "PLAY"
        }

        fun get(context: Context): HeadsetButtonNotifier {
            val app = context.applicationContext as AndroidChatApp
            return app.headsetButtonNotifier
        }
    }
}
