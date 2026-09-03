package com.taskertowpf.androidchatbttest95.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenRouter Chat Completions (OpenAI-compatible).
 * https://openrouter.ai/api/v1/chat/completions
 */
class OpenRouterService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    suspend fun generateReply(
        apiKey: String,
        model: String,
        history: List<AssistantChatTurn>,
        userMessage: String,
        systemInstruction: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.trim()
            if (key.isBlank()) {
                Log.e(TAG, "API key missing")
                error("OpenRouter API key не задан. Добавьте ключ в настройки.")
            }

            val modelName = model.trim().ifBlank { DEFAULT_MODEL }
            val messages = buildList {
                systemInstruction?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add(OpenRouterMessage(role = "system", content = it))
                }
                history.forEach { turn ->
                    add(
                        OpenRouterMessage(
                            role = if (turn.isUser) "user" else "assistant",
                            content = turn.text,
                        ),
                    )
                }
                add(OpenRouterMessage(role = "user", content = userMessage))
            }

            Log.i(
                TAG,
                "Request model=$modelName messages=${messages.size} userLen=${userMessage.length}",
            )

            val requestBody = json.encodeToString(
                OpenRouterRequest.serializer(),
                OpenRouterRequest(model = modelName, messages = messages),
            )

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/taskertowpf/androidchat")
                .addHeader("X-Title", "AndroidChat")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val startedAt = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseError(body, response.code)
                    Log.e(TAG, "HTTP ${response.code} in ${elapsedMs}ms: $error")
                    error(error)
                }

                val parsed = json.decodeFromString<OpenRouterResponse>(body)
                val reply = parsed.choices
                    ?.firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: run {
                        Log.e(TAG, "Empty reply in ${elapsedMs}ms, bodyLen=${body.length}")
                        error("OpenRouter вернул пустой ответ.")
                    }
                Log.i(TAG, "OK in ${elapsedMs}ms replyLen=${reply.length}: ${reply.take(120)}")
                reply
            }
        }.onFailure { error ->
            Log.e(TAG, error.message ?: "OpenRouter failed", error)
        }
    }

    /**
     * OCR / расшифровка текста с фото (vision). Изображение не уходит в чат —
     * только текст ответа модели.
     */
    suspend fun extractTextFromImage(
        apiKey: String,
        model: String,
        jpegBytes: ByteArray,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.trim()
            if (key.isBlank()) {
                error("OpenRouter API key не задан. Добавьте ключ в настройки.")
            }
            if (jpegBytes.isEmpty()) {
                error("Пустое изображение")
            }
            val modelName = model.trim().ifBlank { DEFAULT_MODEL }
            val dataUrl = PhotoOcrService.toDataUrl(jpegBytes)
            val messages = listOf(
                OpenRouterVisionMessage(
                    role = "user",
                    content = listOf(
                        OpenRouterContentPart(
                            type = "text",
                            text = OCR_PROMPT,
                        ),
                        OpenRouterContentPart(
                            type = "image_url",
                            imageUrl = OpenRouterImageUrl(url = dataUrl),
                        ),
                    ),
                ),
            )
            Log.i(TAG, "OCR Request model=$modelName imageBytes=${jpegBytes.size}")

            val requestBody = json.encodeToString(
                OpenRouterVisionRequest.serializer(),
                OpenRouterVisionRequest(model = modelName, messages = messages),
            )

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/taskertowpf/androidchat")
                .addHeader("X-Title", "AndroidChat")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val startedAt = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseError(body, response.code)
                    Log.e(TAG, "OCR HTTP ${response.code} in ${elapsedMs}ms: $error")
                    error(error)
                }
                val parsed = json.decodeFromString<OpenRouterResponse>(body)
                val reply = parsed.choices
                    ?.firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("OpenRouter OCR вернул пустой ответ.")
                val cleaned = reply
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                Log.i(TAG, "OCR OK in ${elapsedMs}ms len=${cleaned.length}: ${cleaned.take(120)}")
                cleaned
            }
        }.onFailure { error ->
            Log.e(TAG, error.message ?: "OpenRouter OCR failed", error)
        }
    }

    private fun parseError(body: String, code: Int): String {
        val parsed = runCatching {
            json.decodeFromString<OpenRouterErrorResponse>(body)
        }.getOrNull()
        val message = parsed?.error?.message?.trim()
            ?: parsed?.message?.trim()
        return if (!message.isNullOrBlank()) {
            "OpenRouter ($code): $message"
        } else {
            "OpenRouter HTTP $code"
        }
    }

    companion object {
        private const val TAG = "OpenRouterService"
        const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash"
        private const val OCR_PROMPT =
            "Extract all readable text from this photo. " +
                "Preserve the original language (Russian or English). " +
                "Return only the transcribed text, no commentary, no markdown."
    }
}

data class AssistantChatTurn(
    val text: String,
    val isUser: Boolean,
)

@Serializable
private data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
)

@Serializable
private data class OpenRouterMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class OpenRouterVisionRequest(
    val model: String,
    val messages: List<OpenRouterVisionMessage>,
)

@Serializable
private data class OpenRouterVisionMessage(
    val role: String,
    val content: List<OpenRouterContentPart>,
)

@Serializable
private data class OpenRouterContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenRouterImageUrl? = null,
)

@Serializable
private data class OpenRouterImageUrl(
    val url: String,
)

@Serializable
private data class OpenRouterResponse(
    val choices: List<OpenRouterChoice>? = null,
)

@Serializable
private data class OpenRouterChoice(
    val message: OpenRouterMessageContent? = null,
)

@Serializable
private data class OpenRouterMessageContent(
    val content: String? = null,
)

@Serializable
private data class OpenRouterErrorResponse(
    val error: OpenRouterErrorBody? = null,
    val message: String? = null,
)

@Serializable
private data class OpenRouterErrorBody(
    val message: String? = null,
    @SerialName("code") val code: String? = null,
)
