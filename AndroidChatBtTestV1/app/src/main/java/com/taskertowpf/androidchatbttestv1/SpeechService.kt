package com.taskertowpf.androidchatbttestv1

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class SpeechService(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.US
            } else {
                tts?.shutdown()
                tts = TextToSpeech(appContext) { fallbackStatus ->
                    ready = fallbackStatus == TextToSpeech.SUCCESS
                    if (ready) {
                        tts?.language = Locale.US
                    }
                }
            }
        }, GOOGLE_TTS_ENGINE)
    }

    fun isReady(): Boolean = ready && tts != null

    fun speak(text: String) {
        speakInternal(withVoicePrefix(text), "en")
    }

    fun speakRussian(text: String) {
        speakInternal(withVoicePrefix(text), "ru")
    }

    fun speakAndThen(text: String, onComplete: () -> Unit) {
        speakAndThenInternal(withVoicePrefix(text), "en", onComplete)
    }

    /** Все фразы: «V1 Play», «V1 ready», … */
    private fun withVoicePrefix(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            return cleaned
        }
        if (
            cleaned.equals(VOICE_PREFIX, ignoreCase = true) ||
            cleaned.startsWith("$VOICE_PREFIX ", ignoreCase = true)
        ) {
            return cleaned
        }
        return "$VOICE_PREFIX $cleaned"
    }

    private fun speakInternal(text: String, lang: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || !ready) {
            return
        }
        prepareAudioRouteForPlayback()
        val engine = tts ?: return
        applyLang(lang)
        engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun speakAndThenInternal(text: String, lang: String, onComplete: () -> Unit) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || !ready) {
            mainHandler.post(onComplete)
            return
        }
        prepareAudioRouteForPlayback()
        val engine = tts
        if (engine == null) {
            mainHandler.post(onComplete)
            return
        }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_STOP) {
                        mainHandler.post(onComplete)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_STOP) {
                        mainHandler.post(onComplete)
                    }
                }
            },
        )
        applyLang(lang)
        engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_STOP)
    }

    private fun prepareAudioRouteForPlayback() {
        val am = appContext.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure { ex ->
            Log.w(TAG, "prepareAudioRouteForPlayback: ${ex.message}")
        }
    }

    private fun applyLang(lang: String) {
        val engine = tts ?: return
        val locale = if (lang.equals("ru", ignoreCase = true)) Locale("ru", "RU") else Locale.US
        engine.language = locale
        val fallback = engine.voices
            ?.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            ?.maxByOrNull { it.quality }
        if (fallback != null) {
            engine.voice = fallback
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val TAG = "SpeechService"
        private const val VOICE_PREFIX = "V1"
        private const val UTTERANCE_ID = "androidchatbttestv1-speak"
        private const val UTTERANCE_STOP = "androidchatbttest-stop"
    }
}
