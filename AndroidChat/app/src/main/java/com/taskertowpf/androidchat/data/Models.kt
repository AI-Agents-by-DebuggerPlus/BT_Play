package com.taskertowpf.androidchat.data

import com.taskertowpf.androidchat.headset.HeadsetConnectionConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val senderName: String = "AndroidChat",
    /** Текущий получатель исходящих (поле recipient_name в Supabase). */
    val recipientName: String = "WpfChat",
    /**
     * Список известных получателей для выбора в настройках (WpfChat, Hermes, …).
     * Текущий — [recipientName].
     */
    val recipientNames: List<String> = listOf("WpfChat"),
    /**
     * Показывать и озвучивать входящие с этим recipient_name
     * (WpfChat/Hermes шлют на «Android»).
     */
    val incomingRecipientName: String = "Android",
    /** Автоозвучка входящих (двухголосый TTS по JSON ru/en). */
    val enableIncomingTts: Boolean = true,
    /** Пауза (сек) между фрагментами EnglishLearning; после en×2 — двойная пауза. */
    val ttsPauseSeconds: Int = 3,
    /** Имя голоса TTS для английского (Google name или OpenAI: nova, onyx, …). */
    val ttsEnglishVoiceName: String = "nova",
    /** Имя голоса TTS для русского (Google name или OpenAI: nova, onyx, …). */
    val ttsRussianVoiceName: String = "onyx",
    /** Движок озвучки: openai | google */
    val ttsEngine: String = "openai",
    /** OpenAI API key для TTS (/v1/audio/speech). */
    val openAiApiKey: String = "",
    /** Модель OpenAI TTS: tts-1 | tts-1-hd */
    val openAiTtsModel: String = "tts-1",
    /** Locale для SpeechRecognizer, напр. ru-RU / en-US. */
    val voiceInputLocale: String = "ru-RU",
    val useAnonymousAuth: Boolean = true,
    /** Не-Play кнопки гарнитуры → сообщение в чат. */
    val enableHeadsetToChat: Boolean = true,
    /** Прямой MediaSession: Play = BT Play (STT / pause / next). */
    val enableNativeHeadsetCapture: Boolean = true,
    /** Папка исходящих файлов (откуда отправляем). */
    val fileOutgoingFolder: String = FileTransferConstants.defaultOutgoingFolder(),
    val fileOutgoingTreeUri: String = "",
    /** Папка входящих файлов (куда сохраняем из чата). */
    val fileIncomingFolder: String = FileTransferConstants.defaultIncomingFolder(),
    val fileIncomingTreeUri: String = "",
    /** Устарело — миграция в SettingsRepository. */
    val fileBackupsFolder: String = "",
    val fileBackupsTreeUri: String = "",
    /** OpenRouter API key (chat / генерация уроков). */
    val openRouterApiKey: String = "",
    /** Модель OpenRouter, напр. google/gemini-2.5-flash */
    val openRouterModel: String = OpenRouterService.DEFAULT_MODEL,
    /** Устарело — миграция в openRouterApiKey. */
    val geminiApiKey: String = "",
    /** Устарело — миграция в openRouterModel. */
    val geminiModel: String = "",
    /**
     * При ручной отправке логов в WpfChat не дублировать уже отправленные (status sent).
     */
    val skipDuplicateLogsToSupabase: Boolean = true,
    /** WebSocket-подписка на входящие сообщения (ChatRealtimePoller). */
    val enableSupabasePoll: Boolean = true,
    /** Размер шрифта EN на карточках урока (sp). */
    val lessonEnFontSp: Float = 18f,
    /** Размер шрифта RU на карточках урока (sp). */
    val lessonRuFontSp: Float = 13f,
    /** Имя целевой BT-гарнитуры в режиме урока (подстрока). */
    val lessonHeadsetDeviceName: String = HeadsetConnectionConstants.DEFAULT_DEVICE_NAME_HINT,
) {
    /** Список получателей с текущим [recipientName], без пустых и дублей. */
    fun resolvedRecipientNames(): List<String> {
        val fromList = recipientNames.map { it.trim() }.filter { it.isNotEmpty() }
        val current = recipientName.trim()
        val merged = LinkedHashSet<String>()
        merged.addAll(fromList)
        if (current.isNotEmpty()) {
            merged.add(current)
        }
        if (merged.isEmpty()) {
            merged.add("WpfChat")
        }
        return merged.toList()
    }

    fun withNormalizedRecipients(): AppSettings {
        val names = resolvedRecipientNames()
        val current = recipientName.trim().ifBlank { names.first() }
        val selected = names.firstOrNull { it.equals(current, ignoreCase = true) } ?: names.first()
        return copy(recipientNames = names, recipientName = selected)
    }

    fun resolvedOpenRouterApiKey(): String =
        openRouterApiKey.trim().ifBlank { geminiApiKey.trim() }

    fun resolvedOpenRouterModel(): String {
        val current = openRouterModel.trim()
        if (current.isNotEmpty()) return current
        val legacy = geminiModel.trim()
        if (legacy.isEmpty()) return OpenRouterService.DEFAULT_MODEL
        return if (legacy.contains('/')) legacy else "google/$legacy"
    }
}

@Serializable
data class MessageInsert(
    @SerialName("sender_id")
    val senderId: String,
    @SerialName("sender_name")
    val senderName: String,
    @SerialName("recipient_name")
    val recipientName: String,
    val content: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class LocalLogEntry(
    val timestampMillis: Long,
    val category: String,
    val message: String,
    val status: String,
) {
    fun shouldSkipManualSupabaseUpload(skipDuplicates: Boolean): Boolean {
        if (!skipDuplicates) {
            return false
        }
        if (status == "sent") {
            return true
        }
        if (status == "local" && category.equals("Bluetooth", ignoreCase = true)) {
            return true
        }
        return false
    }
}
