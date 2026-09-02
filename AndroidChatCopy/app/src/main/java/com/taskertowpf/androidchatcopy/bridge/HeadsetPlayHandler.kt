package com.taskertowpf.androidchatcopy.bridge

import android.util.Log
import com.taskertowpf.androidchatcopy.AndroidChatApp
import com.taskertowpf.androidchatcopy.ContentBtPlayResult
import com.taskertowpf.androidchatcopy.data.AppSettings
import com.taskertowpf.androidchatcopy.voice.VoiceInputResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Native BT Play → пауза TTS / STT / next (EnglishLearning).
 */
class HeadsetPlayHandler(
    private val app: AndroidChatApp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handleBtPlay(source: String = "native") {
        scope.launch {
            handleBtPlayEvent(reason = "BT Play ($source)")
        }
    }

    private suspend fun handleBtPlayEvent(reason: String) {
        if (app.voiceInputService.isListening()) {
            app.localLogRepository.logLocal(
                "Headset",
                "$reason → уже слушаем → cancel",
            )
            app.voiceInputService.cancel()
            return
        }

        val speech = app.speechService
        when {
            speech.isContentPlaybackPaused() -> {
                app.localLogRepository.logLocal("Headset", "$reason → Continue (cue)")
                Log.i(TAG, "$reason → Continue cue then RESUME")
                speech.speakCue("Continue") {
                    when (speech.handleContentBtPlay()) {
                        ContentBtPlayResult.RESUMED -> {
                            Log.i(TAG, "$reason → RESUME озвучки")
                            scope.launch {
                                app.localLogRepository.logLocal("Headset", "$reason → RESUME озвучки")
                            }
                        }
                        else -> Unit
                    }
                }
            }
            speech.isContentPlaybackActive() -> {
                when (speech.handleContentBtPlay()) {
                    ContentBtPlayResult.PAUSED -> {
                        app.localLogRepository.logLocal("Headset", "$reason → Pause (cue)")
                        Log.i(TAG, "$reason → PAUSE + cue")
                        speech.speakCue("Pause")
                    }
                    else -> Unit
                }
            }
            else -> {
                val previewRecipient = resolveSettings().recipientName.trim().ifBlank { "(пусто)" }
                app.localLogRepository.logLocal(
                    "Headset",
                    "$reason → idle → VoiceInput (SpeechRecognizer); recipient=$previewRecipient",
                )
                Log.i(TAG, "$reason → start VoiceInput → $previewRecipient")
                listenAndDeliver(reason = reason)
            }
        }
    }

    private suspend fun listenAndDeliver(reason: String) {
        val locale = resolveSettings().voiceInputLocale.ifBlank { "ru-RU" }
        val voiceResult = suspendCancellableCoroutine { cont ->
            app.voiceInputService.startListening(localeTag = locale) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
            cont.invokeOnCancellation {
                app.voiceInputService.cancel()
            }
        }

        when (voiceResult) {
            is VoiceInputResult.Success -> {
                app.localLogRepository.logLocal(
                    "Voice",
                    "$reason → распознано: ${voiceResult.text.take(120)}",
                )
                deliverChat(
                    content = voiceResult.text,
                    source = "android-stt",
                )
            }
            is VoiceInputResult.Empty,
            is VoiceInputResult.Error,
            is VoiceInputResult.Cancelled,
            -> {
                val detail = when (voiceResult) {
                    is VoiceInputResult.Error -> voiceResult.message
                    is VoiceInputResult.Cancelled -> "cancelled"
                    else -> "empty"
                }
                app.localLogRepository.logLocal(
                    "Voice",
                    "$reason → нет текста ($detail)",
                )
                // Пустой голос после Play → голосовое подтверждение «Play».
                app.speechService.speakCue("Play") {
                    val settings = resolveSettings()
                    val recipient = settings.recipientName.trim()
                    if (recipient.equals(RECIPIENT_ENGLISH_LEARNING, ignoreCase = true)) {
                        scope.launch {
                            app.localLogRepository.logLocal(
                                "Headset",
                                "$reason → Play cue → $ENGLISH_NAV_NEXT → $recipient",
                            )
                            deliverChat(
                                content = ENGLISH_NAV_NEXT,
                                source = "android-stt-fallback",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun deliverChat(
        content: String,
        source: String,
    ) {
        // Всегда актуальный recipient из UI (activeSettings), не снимок с начала Play.
        val settings = resolveSettings()
        val recipient = settings.recipientName.trim().ifBlank { "(пусто)" }
        val connectResult = app.chatRepository.ensureConnectedForRelay(settings, scope)
        if (connectResult.isFailure) {
            val err = connectResult.exceptionOrNull()?.message
            Log.e(TAG, "Connect failed: $err")
            app.localLogRepository.logLocal(
                "Headset",
                "Connect error: $err; recipient_name=$recipient",
            )
            return
        }

        val result = app.chatRepository.sendMessage(settings, content)
        app.localLogRepository.logLocal(
            "Headset",
            "Chat ($source) → $recipient: $content",
        )
        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message
            Log.e(TAG, "Send failed: $err")
            app.localLogRepository.logLocal(
                "Headset",
                "Send error → $recipient: $err",
            )
        } else {
            Log.i(TAG, "Chat sent → $recipient ($source): ${content.take(80)}")
        }
    }

    /**
     * UI [MainViewModel.syncActiveSettings] — источник истины для «Кому».
     * Диск — только запасной вариант до инициализации UI.
     */
    private fun resolveSettings(): AppSettings {
        return app.chatRepository.activeSettingsOrNull()
            ?: app.settingsRepository.load()
    }

    companion object {
        private const val TAG = "HeadsetPlayHandler"
        const val RECIPIENT_ENGLISH_LEARNING = "EnglishLearning"
        const val ENGLISH_NAV_NEXT = "next"
    }
}
