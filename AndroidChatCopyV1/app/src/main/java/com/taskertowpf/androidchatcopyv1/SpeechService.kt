package com.taskertowpf.androidchatcopyv1

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.taskertowpf.androidchatcopyv1.data.AppSettings
import com.taskertowpf.androidchatcopyv1.data.OpenAiTtsClient
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val language: String,
    val displayLabel: String,
    val quality: Int,
    val isNetwork: Boolean,
    /** "female", "male", or "" if unknown */
    val gender: String = "",
    /** "google" | "openai" */
    val provider: String = "google",
)

/** Результат «BT Play» во время озвучки входящего контента. */
enum class ContentBtPlayResult {
    /** Озвучка шла → поставлена на паузу; next не слать. */
    PAUSED,
    /** Была пауза → возобновлено; next не слать. */
    RESUMED,
    /** Озвучка контента не активна → можно слать next. */
    IDLE_SEND_NEXT,
}

class SpeechService(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val planToken = AtomicInteger(0)
    private val cueToken = AtomicInteger(0)
    private val openAiExecutor = Executors.newSingleThreadExecutor()
    private val openAiClient = OpenAiTtsClient()
    private var mediaPlayer: MediaPlayer? = null

    private var preferredEnglishVoiceName: String = "nova"
    private var preferredRussianVoiceName: String = "onyx"
    private var ttsEngine: String = ENGINE_OPENAI
    private var openAiApiKey: String = ""
    private var openAiModel: String = OpenAiTtsClient.DEFAULT_MODEL
    private var voiceSenderPrefix: String = "AndroidChatCopyV1"

    /** Активен план озвучки входящего контента (EnglishLearning и т.п.). */
    private val contentPlanActive = AtomicBoolean(false)
    private val contentPaused = AtomicBoolean(false)
    private val pauseLock = Object()

    /** Google-план: текущий индекс шага для паузы/resume. */
    @Volatile private var googlePlanSteps: List<SpeakStep>? = null
    @Volatile private var googlePlanIndex: Int = 0
    @Volatile private var googlePlanToken: Int = -1

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

    fun applyTtsSettings(settings: AppSettings) {
        ttsEngine = settings.ttsEngine.trim().lowercase(Locale.ROOT).ifBlank { ENGINE_OPENAI }
        openAiApiKey = settings.openAiApiKey.trim()
        openAiModel = settings.openAiTtsModel.trim().ifBlank { OpenAiTtsClient.DEFAULT_MODEL }
        preferredEnglishVoiceName = settings.ttsEnglishVoiceName.trim()
            .ifBlank { if (usesOpenAi()) "nova" else "" }
        preferredRussianVoiceName = settings.ttsRussianVoiceName.trim()
            .ifBlank { if (usesOpenAi()) "onyx" else "" }
        voiceSenderPrefix = settings.senderName.trim().ifBlank { "AndroidChatCopyV1" }
    }

    /** Формат озвучки: «&lt;senderName&gt; &lt;text&gt;». */
    private fun withSenderPrefix(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            return cleaned
        }
        val prefix = voiceSenderPrefix.trim()
        if (prefix.isEmpty()) {
            return cleaned
        }
        if (
            cleaned.equals(prefix, ignoreCase = true) ||
            cleaned.startsWith("$prefix ", ignoreCase = true)
        ) {
            return cleaned
        }
        return "$prefix $cleaned"
    }

    /** @deprecated Prefer [applyTtsSettings]. */
    fun applyVoicePreferences(englishVoiceName: String, russianVoiceName: String) {
        preferredEnglishVoiceName = englishVoiceName.trim()
        preferredRussianVoiceName = russianVoiceName.trim()
    }

    fun usesOpenAi(): Boolean =
        ttsEngine == ENGINE_OPENAI && openAiApiKey.isNotBlank()

    fun isReady(): Boolean = usesOpenAi() || (ready && tts != null)

    fun listOpenAiVoices(): List<TtsVoiceInfo> {
        return OpenAiTtsClient.VOICES.flatMap { voice ->
            val genderMark = when (voice.gender) {
                "female" -> "♀"
                "male" -> "♂"
                else -> "·"
            }
            val label = "OpenAI $genderMark ${voice.id} (${voice.labelRu})"
            listOf("en", "ru").map { lang ->
                TtsVoiceInfo(
                    name = voice.id,
                    localeTag = "openai",
                    language = lang,
                    displayLabel = label,
                    quality = 400,
                    isNetwork = true,
                    gender = voice.gender,
                    provider = "openai",
                )
            }
        }
    }

    fun listGoogleVoices(): List<TtsVoiceInfo> {
        val engine = tts ?: return emptyList()
        if (!ready) {
            return emptyList()
        }

        val probeLocales = listOf(
            Locale.US,
            Locale.UK,
            Locale.CANADA,
            Locale("en", "AU"),
            Locale("en", "IN"),
            Locale("en", "IE"),
            Locale("ru", "RU"),
        )
        for (locale in probeLocales) {
            runCatching { engine.setLanguage(locale) }
        }

        val raw = engine.voices
            .asSequence()
            .filter { voice ->
                val lang = voice.locale.language.lowercase(Locale.ROOT)
                lang == "en" || lang == "ru"
            }
            .map { voice -> toVoiceInfo(voice) }
            .distinctBy { it.name }
            .toList()

        return raw
            .groupBy { voiceFamilyKey(it.name) }
            .values
            .mapNotNull { family ->
                family.sortedWith(
                    compareBy<TtsVoiceInfo> { it.isNetwork }
                        .thenByDescending { it.quality }
                        .thenBy { it.name },
                ).firstOrNull()
            }
            .distinctBy {
                "${it.language}|${it.localeTag.lowercase(Locale.ROOT)}|${it.gender}|${shortVoiceCode(it.name)}"
            }
            .sortedWith(
                compareBy<TtsVoiceInfo> { it.language }
                    .thenBy { it.localeTag.lowercase(Locale.ROOT) }
                    .thenBy { genderSortKey(it.gender) }
                    .thenBy { it.displayLabel.lowercase(Locale.ROOT) },
            )
    }

    fun speak(text: String) {
        speakInternal(withSenderPrefix(text), "en")
    }

    fun speakRussian(text: String) {
        speakInternal(withSenderPrefix(text), "ru")
    }

    fun speakRussianAndThen(text: String, onComplete: () -> Unit) {
        speakAndThenInternal(withSenderPrefix(text), "ru", onComplete)
    }

    fun speakAndThen(text: String, onComplete: () -> Unit) {
        speakAndThenInternal(withSenderPrefix(text), "en", onComplete)
    }

    /**
     * Короткая метка (Play / Pause / Continue) без сброса content-plan и паузы.
     * Использует Google TTS, чтобы не трогать MediaPlayer озвучки OpenAI.
     */
    fun speakCue(text: String, lang: String = "en", onComplete: (() -> Unit)? = null) {
        val cleaned = withSenderPrefix(text)
        if (cleaned.isEmpty()) {
            onComplete?.let { mainHandler.post(it) }
            return
        }
        prepareAudioRouteForPlayback()
        val engine = tts
        if (!ready || engine == null) {
            Log.w(TAG, "speakCue: TTS not ready for \"$cleaned\"")
            onComplete?.let { mainHandler.post(it) }
            return
        }
        val utteranceId = "$UTTERANCE_CUE-${cueToken.incrementAndGet()}"
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId) {
                        onComplete?.let { mainHandler.post(it) }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(doneId: String?) {
                    if (doneId == utteranceId) {
                        onComplete?.let { mainHandler.post(it) }
                    }
                }
            },
        )
        applyLang(lang)
        val ok = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (ok == TextToSpeech.ERROR) {
            Log.w(TAG, "speakCue ERROR for \"$cleaned\"")
            onComplete?.let { mainHandler.post(it) }
        } else {
            Log.i(TAG, "speakCue: $cleaned")
        }
    }

    fun speakWithVoice(voiceName: String, text: String, langHint: String) {
        val sample = withSenderPrefix(
            text.trim().ifEmpty {
                if (langHint.equals("ru", ignoreCase = true)) SAMPLE_RU else SAMPLE_EN
            },
        )
        cancelPlan()
        if (usesOpenAi() || voiceName in OpenAiTtsClient.VOICES.map { it.id }) {
            val token = planToken.incrementAndGet()
            openAiExecutor.execute {
                runCatching {
                    playOpenAiUtterance(sample, voiceName, token)
                }.onFailure { ex ->
                    Log.e(TAG, "OpenAI preview failed: ${ex.message}")
                    mainHandler.post {
                        // Fallback to Google for preview if possible.
                        speakWithGoogleVoice(voiceName, sample, langHint)
                    }
                }
            }
            return
        }
        speakWithGoogleVoice(voiceName, sample, langHint)
    }

    fun speakVoiceSegments(segments: List<VoiceSegment>) {
        val steps = segments.map { SpeakStep.Speak(it.lang, it.text) }
        speakPlan(steps)
    }

    fun speakPlan(steps: List<SpeakStep>, markAsContent: Boolean = true) {
        val cleaned = steps.mapNotNull { step ->
            when (step) {
                is SpeakStep.Pause -> step.takeIf { it.millis > 0 }
                is SpeakStep.Speak -> {
                    val text = withSenderPrefix(step.text)
                    if (text.isEmpty()) null else step.copy(text = text)
                }
            }
        }
        if (cleaned.isEmpty()) {
            return
        }

        prepareAudioRouteForPlayback()
        val token = planToken.incrementAndGet()
        stopMediaPlayer()
        tts?.stop()
        if (markAsContent) {
            beginContentPlan()
        } else {
            synchronized(pauseLock) {
                contentPlanActive.set(false)
                contentPaused.set(false)
                pauseLock.notifyAll()
            }
        }

        if (usesOpenAi()) {
            openAiExecutor.execute {
                try {
                    runOpenAiPlan(cleaned, token)
                } finally {
                    if (markAsContent) {
                        endContentPlan(token)
                    }
                }
            }
            return
        }

        val engine = tts
        if (!ready || engine == null) {
            Log.w(TAG, "TTS not ready (no OpenAI key / Google engine)")
            if (markAsContent) {
                endContentPlan(token)
            }
            return
        }
        if (markAsContent) {
            googlePlanSteps = cleaned
            googlePlanIndex = 0
            googlePlanToken = token
        }
        runPlanStep(engine, cleaned, 0, token, markAsContent)
    }

    /**
     * BT Play (пустое SEND_CHAT / NOT HEARD) во время озвучки входящего контента:
     * speaking → pause, paused → resume, idle → next.
     */
    fun handleContentBtPlay(): ContentBtPlayResult {
        synchronized(pauseLock) {
            if (!contentPlanActive.get()) {
                return ContentBtPlayResult.IDLE_SEND_NEXT
            }
            if (contentPaused.get()) {
                contentPaused.set(false)
                pauseLock.notifyAll()
                mainHandler.post {
                    runCatching {
                        val player = mediaPlayer
                        if (player != null && !player.isPlaying) {
                            player.start()
                            Log.i(TAG, "Content TTS resumed (MediaPlayer)")
                        }
                    }
                    val token = googlePlanToken
                    val steps = googlePlanSteps
                    val index = googlePlanIndex
                    val engine = tts
                    if (steps != null && engine != null && token == planToken.get() && !usesOpenAi()) {
                        runPlanStep(engine, steps, index, token)
                    }
                }
                Log.i(TAG, "Content BT Play → RESUMED")
                return ContentBtPlayResult.RESUMED
            }
            contentPaused.set(true)
            mainHandler.post {
                runCatching {
                    val player = mediaPlayer
                    if (player != null && player.isPlaying) {
                        player.pause()
                        Log.i(TAG, "Content TTS paused (MediaPlayer)")
                    }
                }
                tts?.stop()
            }
            Log.i(TAG, "Content BT Play → PAUSED")
            return ContentBtPlayResult.PAUSED
        }
    }

    fun isContentPlaybackActive(): Boolean = contentPlanActive.get()

    fun isContentPlaybackPaused(): Boolean = contentPaused.get()

    /** OpenAI MediaPlayer или content-plan TTS ещё играет. */
    fun isPlaybackActive(): Boolean {
        if (contentPlanActive.get()) return true
        val player = mediaPlayer ?: return false
        return runCatching { player.isPlaying }.getOrDefault(false)
    }

    fun stopSpeaking() {
        cancelPlan()
        stopMediaPlayer()
        tts?.stop()
    }

    private fun beginContentPlan() {
        synchronized(pauseLock) {
            contentPlanActive.set(true)
            contentPaused.set(false)
            pauseLock.notifyAll()
        }
    }

    private fun endContentPlan(token: Int) {
        if (token != planToken.get()) {
            return
        }
        synchronized(pauseLock) {
            contentPlanActive.set(false)
            contentPaused.set(false)
            googlePlanSteps = null
            googlePlanIndex = 0
            googlePlanToken = -1
            pauseLock.notifyAll()
        }
        Log.i(TAG, "Content TTS plan finished")
    }

    private fun awaitIfContentPaused(token: Int) {
        synchronized(pauseLock) {
            while (contentPaused.get() && token == planToken.get() && contentPlanActive.get()) {
                try {
                    pauseLock.wait(250)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun interruptibleSleep(millis: Long, token: Int) {
        var remaining = millis
        while (remaining > 0 && token == planToken.get()) {
            awaitIfContentPaused(token)
            if (token != planToken.get()) {
                return
            }
            val chunk = minOf(100L, remaining)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                return
            }
            remaining -= chunk
        }
    }

    private fun runOpenAiPlan(steps: List<SpeakStep>, token: Int) {
        for (step in steps) {
            if (token != planToken.get()) {
                return
            }
            awaitIfContentPaused(token)
            if (token != planToken.get()) {
                return
            }
            when (step) {
                is SpeakStep.Pause -> {
                    interruptibleSleep(step.millis, token)
                }
                is SpeakStep.Speak -> {
                    val voice = if (step.lang.equals("ru", ignoreCase = true)) {
                        preferredRussianVoiceName
                    } else {
                        preferredEnglishVoiceName
                    }
                    runCatching {
                        playOpenAiUtterance(step.text, voice, token)
                    }.onFailure { ex ->
                        Log.e(TAG, "OpenAI speak failed, fallback Google: ${ex.message}")
                        if (token != planToken.get()) {
                            return
                        }
                        val latch = CountDownLatch(1)
                        mainHandler.post {
                            val engine = tts
                            if (!ready || engine == null) {
                                latch.countDown()
                                return@post
                            }
                            speakGoogleBlocking(engine, step, token, latch)
                        }
                        latch.await(60, TimeUnit.SECONDS)
                    }
                }
            }
        }
    }

    private fun playOpenAiUtterance(text: String, voice: String, token: Int) {
        if (token != planToken.get()) {
            return
        }
        prepareAudioRouteForPlayback()
        val bytes = openAiClient.synthesize(
            apiKey = openAiApiKey,
            text = text,
            voice = voice,
            model = openAiModel,
        )
        Log.i(TAG, "OpenAI TTS ok voice=$voice bytes=${bytes.size} textLen=${text.length}")
        if (token != planToken.get()) {
            return
        }
        awaitIfContentPaused(token)
        if (token != planToken.get()) {
            return
        }
        val cacheFile = File(appContext.cacheDir, "openai-tts-$token-${System.nanoTime()}.mp3")
        cacheFile.writeBytes(bytes)
        try {
            playFileBlocking(cacheFile, token)
        } finally {
            cacheFile.delete()
        }
    }

    private fun playFileBlocking(file: File, token: Int) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            if (token != planToken.get()) {
                latch.countDown()
                return@post
            }
            stopMediaPlayer()
            val player = MediaPlayer()
            mediaPlayer = player
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener {
                    latch.countDown()
                    stopMediaPlayer()
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    latch.countDown()
                    stopMediaPlayer()
                    true
                }
                player.prepare()
                player.start()
                Log.i(TAG, "OpenAI TTS playing ${file.name}")
            } catch (ex: Exception) {
                Log.e(TAG, "MediaPlayer failed: ${ex.message}")
                latch.countDown()
                stopMediaPlayer()
            }
        }
        latch.await(120, TimeUnit.SECONDS)
    }

    private fun speakGoogleBlocking(
        engine: TextToSpeech,
        step: SpeakStep.Speak,
        token: Int,
        latch: CountDownLatch,
    ) {
        val utteranceId = "$UTTERANCE_PLAN-$token-fb"
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(doneId: String?) {
                    if (doneId == utteranceId) {
                        latch.countDown()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(doneId: String?) {
                    if (doneId == utteranceId) {
                        latch.countDown()
                    }
                }
            },
        )
        applyLang(step.lang)
        engine.speak(step.text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    private fun speakWithGoogleVoice(voiceName: String, text: String, langHint: String) {
        val engine = tts
        if (!ready || engine == null) {
            return
        }
        engine.stop()
        val voice = engine.voices?.firstOrNull { it.name == voiceName }
        if (voice != null) {
            engine.voice = voice
            engine.language = voice.locale
        } else {
            applyLang(langHint)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_PREVIEW)
    }

    private fun cancelPlan() {
        planToken.incrementAndGet()
        synchronized(pauseLock) {
            contentPlanActive.set(false)
            contentPaused.set(false)
            googlePlanSteps = null
            googlePlanIndex = 0
            googlePlanToken = -1
            pauseLock.notifyAll()
        }
    }

    /** После BT SCO (STT) сбросить маршрут на динамик/гарнитуру A2DP. */
    private fun prepareAudioRouteForPlayback() {
        val am = appContext.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                Log.i(TAG, "SCO stopped before TTS playback")
            }
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "Audio mode → NORMAL for TTS")
            }
        }.onFailure { ex ->
            Log.w(TAG, "prepareAudioRouteForPlayback: ${ex.message}")
        }
    }

    private fun stopMediaPlayer() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    private fun runPlanStep(
        engine: TextToSpeech,
        steps: List<SpeakStep>,
        index: Int,
        token: Int,
        markAsContent: Boolean = true,
    ) {
        if (token != planToken.get()) {
            return
        }
        if (index >= steps.size) {
            if (markAsContent) {
                endContentPlan(token)
            }
            return
        }
        if (markAsContent && contentPaused.get()) {
            googlePlanIndex = index
            mainHandler.postDelayed({
                if (token == planToken.get()) {
                    runPlanStep(engine, steps, index, token, markAsContent)
                }
            }, 200)
            return
        }
        if (markAsContent) {
            googlePlanIndex = index
            googlePlanSteps = steps
            googlePlanToken = token
        }
        when (val step = steps[index]) {
            is SpeakStep.Pause -> {
                mainHandler.postDelayed({
                    if (token == planToken.get()) {
                        if (markAsContent && contentPaused.get()) {
                            runPlanStep(engine, steps, index, token, markAsContent)
                        } else {
                            runPlanStep(engine, steps, index + 1, token, markAsContent)
                        }
                    }
                }, step.millis)
            }
            is SpeakStep.Speak -> {
                val utteranceId = "$UTTERANCE_PLAN-$token-$index"
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(doneId: String?) {
                            if (doneId != utteranceId || token != planToken.get()) {
                                return
                            }
                            if (markAsContent && contentPaused.get()) {
                                return
                            }
                            mainHandler.post {
                                runPlanStep(engine, steps, index + 1, token, markAsContent)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(doneId: String?) {
                            if (doneId != utteranceId || token != planToken.get()) {
                                return
                            }
                            if (markAsContent && contentPaused.get()) {
                                return
                            }
                            mainHandler.post {
                                runPlanStep(engine, steps, index + 1, token, markAsContent)
                            }
                        }
                    },
                )
                applyLang(step.lang)
                engine.speak(step.text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            }
        }
    }

    private fun speakInternal(text: String, lang: String) {
        cancelPlan()
        prepareAudioRouteForPlayback()
        if (usesOpenAi()) {
            val token = planToken.incrementAndGet()
            openAiExecutor.execute {
                runCatching {
                    val voice = if (lang == "ru") preferredRussianVoiceName else preferredEnglishVoiceName
                    playOpenAiUtterance(text, voice, token)
                }.onFailure { Log.e(TAG, it.message ?: "OpenAI speak failed") }
            }
            return
        }
        if (!ready) {
            return
        }
        val engine = tts ?: return
        applyLang(lang)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun speakAndThenInternal(text: String, lang: String, onComplete: () -> Unit) {
        cancelPlan()
        prepareAudioRouteForPlayback()
        if (usesOpenAi()) {
            val token = planToken.incrementAndGet()
            openAiExecutor.execute {
                runCatching {
                    val voice = if (lang == "ru") preferredRussianVoiceName else preferredEnglishVoiceName
                    playOpenAiUtterance(text, voice, token)
                }
                mainHandler.post(onComplete)
            }
            return
        }
        val engine = tts
        if (!ready || engine == null) {
            onComplete()
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
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_STOP)
    }

    private fun toVoiceInfo(voice: Voice): TtsVoiceInfo {
        val lang = voice.locale.language.lowercase(Locale.ROOT)
        val gender = detectGender(voice)
        return TtsVoiceInfo(
            name = voice.name,
            localeTag = voice.locale.toLanguageTag(),
            language = lang,
            displayLabel = buildVoiceLabel(voice, gender),
            quality = voice.quality,
            isNetwork = voice.isNetworkConnectionRequired,
            gender = gender,
            provider = "google",
        )
    }

    private fun detectGender(voice: Voice): String {
        val features = voice.features.orEmpty().map { it.lowercase(Locale.ROOT) }
        when {
            features.any { it.contains("female") } -> return "female"
            features.any { it.contains("male") } -> return "male"
        }
        val name = voice.name.lowercase(Locale.ROOT)
        return when {
            name.contains("female") || name.contains("-f-") || name.endsWith("-f") -> "female"
            name.contains("male") || name.contains("-m-") || name.endsWith("-m") -> "male"
            else -> ""
        }
    }

    private fun voiceFamilyKey(name: String): String {
        var key = name.trim().lowercase(Locale.ROOT)
        key = key.removeSuffix("-local").removeSuffix("-network")
        key = key.removeSuffix("-l").removeSuffix("-n")
        return key
    }

    private fun genderSortKey(gender: String): Int = when (gender) {
        "female" -> 0
        "male" -> 1
        else -> 2
    }

    private fun shortVoiceCode(name: String): String {
        val parts = name.lowercase(Locale.ROOT).split('-')
        val x = parts.indexOf("x")
        if (x >= 0 && x + 1 < parts.size) {
            val code = parts[x + 1]
            if (code !in listOf("local", "network", "l", "n")) {
                return code
            }
        }
        return voiceFamilyKey(name).takeLast(6)
    }

    private fun buildVoiceLabel(voice: Voice, gender: String): String {
        val net = if (voice.isNetworkConnectionRequired) "online" else "offline"
        val genderMark = when (gender) {
            "female" -> "♀"
            "male" -> "♂"
            else -> "·"
        }
        val code = shortVoiceCode(voice.name)
        return "${voice.locale.toLanguageTag()} $genderMark $code ($net)"
    }

    private fun applyLang(lang: String) {
        val engine = tts ?: return
        val isRu = lang.equals("ru", ignoreCase = true)
        val preferredName = if (isRu) preferredRussianVoiceName else preferredEnglishVoiceName
        val preferred = preferredName.takeIf { it.isNotEmpty() }
            ?.let { name -> engine.voices?.firstOrNull { it.name == name } }
        if (preferred != null) {
            engine.voice = preferred
            engine.language = preferred.locale
            return
        }
        val locale = if (isRu) Locale("ru", "RU") else Locale.US
        engine.language = locale
        val fallback = engine.voices
            ?.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            ?.maxByOrNull { it.quality }
        if (fallback != null) {
            engine.voice = fallback
        }
    }

    fun shutdown() {
        cancelPlan()
        stopMediaPlayer()
        openAiExecutor.shutdownNow()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "SpeechService"
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        const val ENGINE_OPENAI = "openai"
        const val ENGINE_GOOGLE = "google"
        private const val UTTERANCE_ID = "androidchat-tts"
        private const val UTTERANCE_STOP = "androidchat-stop"
        private const val UTTERANCE_PLAN = "androidchat-plan"
        private const val UTTERANCE_PREVIEW = "androidchat-preview"
        private const val UTTERANCE_CUE = "androidchat-cue"
        const val SAMPLE_EN = "This is a sample of the selected English voice."
        const val SAMPLE_RU = "Это образец выбранного русского голоса."
    }
}
