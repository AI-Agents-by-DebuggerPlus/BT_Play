package com.taskertowpf.androidchatbttest98.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.taskertowpf.androidchatbttest98.AndroidChatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Запись + транскрипция через системный Google SpeechRecognizer
 * (android.speech — Google recognition service).
 */
class VoiceInputService(
    private val app: AndroidChatApp,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listening = AtomicBoolean(false)
    private var recognizer: SpeechRecognizer? = null
    private var pendingCallback: ((VoiceInputResult) -> Unit)? = null
    private var scoEnabled = false

    fun isListening(): Boolean = listening.get()

    /**
     * Запускает распознавание. Колбэк на main thread.
     * Если уже слушает — отмена предыдущего с [VoiceInputResult.Cancelled].
     */
    fun startListening(
        localeTag: String = DEFAULT_LOCALE,
        onFinished: (VoiceInputResult) -> Unit,
    ) {
        mainHandler.post {
            if (listening.get()) {
                Log.i(TAG, "Already listening → cancel previous")
                finishWith(VoiceInputResult.Cancelled)
            }
            pendingCallback = onFinished

            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO not granted")
                logVoice("RECORD_AUDIO нет — запросите разрешение")
                requestPermissionThenRetry(localeTag, onFinished)
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                Log.e(TAG, "SpeechRecognizer not available")
                finishWith(VoiceInputResult.Error("SpeechRecognizer недоступен на устройстве"))
                return@post
            }

            beginRecognition(localeTag)
        }
    }

    fun cancel() {
        mainHandler.post {
            if (!listening.get()) {
                return@post
            }
            finishWith(VoiceInputResult.Cancelled)
        }
    }

    private fun logVoice(message: String) {
        logScope.launch {
            app.localLogRepository.logLocal("Voice", message)
        }
    }

    private fun requestPermissionThenRetry(
        localeTag: String,
        onFinished: (VoiceInputResult) -> Unit,
    ) {
        val requested = app.requestRecordAudioPermission { granted ->
            if (granted) {
                startListening(localeTag, onFinished)
            } else {
                finishWith(VoiceInputResult.Error("Нет разрешения RECORD_AUDIO"))
            }
        }
        if (!requested) {
            finishWith(VoiceInputResult.Error("Нет UI для запроса RECORD_AUDIO — откройте AndroidChat"))
        }
    }

    private fun beginRecognition(localeTag: String) {
        destroyRecognizer()
        enableScoIfNeeded()

        val sr = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = sr
        listening.set(true)

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "Ready for speech")
                logVoice("Слушаю (Google SpeechRecognizer)…")
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                Log.i(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                val msg = errorLabel(error)
                Log.w(TAG, "Recognition error: $msg ($error)")
                finishWith(VoiceInputResult.Error(msg, errorCode = error))
            }

            override fun onResults(results: Bundle?) {
                val texts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val best = texts.firstOrNull()?.trim().orEmpty()
                Log.i(TAG, "Results: ${texts.take(3)} → '$best'")
                if (best.isEmpty()) {
                    finishWith(VoiceInputResult.Empty)
                } else {
                    finishWith(VoiceInputResult.Success(best))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }

        try {
            sr.startListening(intent)
            Log.i(TAG, "startListening locale=$localeTag")
        } catch (ex: Exception) {
            Log.e(TAG, "startListening failed: ${ex.message}")
            finishWith(VoiceInputResult.Error(ex.message ?: "startListening failed"))
        }
    }

    private fun finishWith(result: VoiceInputResult) {
        val cb = pendingCallback
        pendingCallback = null
        listening.set(false)
        destroyRecognizer()
        disableScoIfNeeded()
        if (cb != null) {
            mainHandler.post { cb(result) }
        }
    }

    private fun destroyRecognizer() {
        runCatching {
            recognizer?.stopListening()
        }
        runCatching {
            recognizer?.cancel()
        }
        runCatching {
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun enableScoIfNeeded() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoEnabled = true
                Log.i(TAG, "Bluetooth SCO started")
            }
        }
    }

    private fun disableScoIfNeeded() {
        if (!scoEnabled) {
            return
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            am.mode = AudioManager.MODE_NORMAL
            scoEnabled = false
            Log.i(TAG, "Bluetooth SCO stopped")
        }
    }

    companion object {
        private const val TAG = "VoiceInputService"
        const val DEFAULT_LOCALE = "ru-RU"

        private fun errorLabel(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_$code"
        }
    }
}

sealed class VoiceInputResult {
    data class Success(val text: String) : VoiceInputResult()
    data object Empty : VoiceInputResult()
    data object Cancelled : VoiceInputResult()
    data class Error(val message: String, val errorCode: Int = -1) : VoiceInputResult()
}
