package com.taskertowpf.androidchat.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Audio Speech API: POST /v1/audio/speech → mp3 bytes.
 * https://platform.openai.com/docs/api-reference/audio/createSpeech
 */
class OpenAiTtsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun synthesize(
        apiKey: String,
        text: String,
        voice: String,
        model: String = DEFAULT_MODEL,
    ): ByteArray {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "OpenAI API key пуст" }
        val input = text.trim()
        require(input.isNotEmpty()) { "Пустой текст для TTS" }

        val bodyJson = JSONObject()
            .put("model", model.trim().ifBlank { DEFAULT_MODEL })
            .put("input", input.take(MAX_INPUT_CHARS))
            .put("voice", normalizeVoice(voice))
            .put("response_format", "mp3")
            .toString()

        val request = Request.Builder()
            .url(SPEECH_URL)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val err = bytes.toString(Charsets.UTF_8).take(300)
                error("OpenAI TTS HTTP ${response.code}: $err")
            }
            if (bytes.isEmpty()) {
                error("OpenAI TTS: пустой ответ")
            }
            return bytes
        }
    }

    companion object {
        private const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_MODEL = "tts-1"
        const val MODEL_HD = "tts-1-hd"
        /** OpenAI limit ~4096 chars for input. */
        private const val MAX_INPUT_CHARS = 4096

        data class OpenAiVoice(
            val id: String,
            val gender: String,
            val labelRu: String,
        )

        val VOICES: List<OpenAiVoice> = listOf(
            OpenAiVoice("alloy", "", "нейтральный"),
            OpenAiVoice("ash", "male", "мужской"),
            OpenAiVoice("ballad", "female", "женский, мягкий"),
            OpenAiVoice("coral", "female", "женский"),
            OpenAiVoice("echo", "male", "мужской"),
            OpenAiVoice("fable", "male", "мужской, рассказчик"),
            OpenAiVoice("nova", "female", "женский, ясный"),
            OpenAiVoice("onyx", "male", "мужской, низкий"),
            OpenAiVoice("sage", "female", "женский"),
            OpenAiVoice("shimmer", "female", "женский, яркий"),
        )

        fun normalizeVoice(voice: String): String {
            val id = voice.trim().lowercase()
            return VOICES.firstOrNull { it.id == id }?.id ?: "nova"
        }
    }
}
