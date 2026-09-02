package com.taskertowpf.androidchatcopyv1.data

import android.net.Uri
import android.util.Log
import com.taskertowpf.androidchatcopyv1.LogMessageFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Supabase Realtime через WebSocket (Phoenix postgres_changes INSERT).
 * Модель как в EchoTrigger: anon key для подписки, reconnect + heartbeat.
 */
class ChatRealtimePoller(
    private val onMessage: (ChatMessage) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val wsHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var settings: AppSettings? = null
    private var scope: CoroutineScope? = null
    private var realtimeJob: Job? = null
    private var heartbeatJob: Job? = null

    private val rowMutex = Mutex()
    private var lastSeenId: String? = null
    private val refSeq = AtomicInteger(1)

    fun start(settings: AppSettings, scope: CoroutineScope) {
        if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
            return
        }
        stop()
        this.settings = settings
        this.scope = scope

        onStatus("WebSocket: подключение…")
        realtimeJob = scope.launch(Dispatchers.IO) {
            syncBaselineSilent(settings)
            var backoff = MIN_RECONNECT_MS
            while (isActive) {
                val url = websocketUrl(settings)
                if (url == null) {
                    onStatus("WebSocket: нет URL")
                    delay(10_000)
                    continue
                }
                val ranOk = runCatching {
                    connectUntilClosed(url)
                    true
                }.getOrElse { e ->
                    Log.e(TAG, "Realtime loop error", e)
                    onStatus("WebSocket: ошибка — ${e.message}")
                    false
                }
                if (!ranOk) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_MS)
                } else {
                    backoff = MIN_RECONNECT_MS
                    delay(MIN_RECONNECT_MS)
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        realtimeJob?.cancel()
        realtimeJob = null
        settings = null
        scope = null
        onStatus("WebSocket: отключено")
    }

    private suspend fun syncBaselineSilent(settings: AppSettings) {
        val base = settings.supabaseUrl.trim().trimEnd('/')
        val key = settings.supabaseAnonKey.trim()
        if (base.isEmpty() || key.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                val url = "$base/rest/v1/messages?order=created_at.desc&limit=1&select=id"
                val request = Request.Builder()
                    .url(url)
                    .header("apikey", key)
                    .header("Authorization", "Bearer $key")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Baseline HTTP ${response.code}")
                        return@withContext
                    }
                    val body = response.body?.string() ?: return@withContext
                    val arr = JSONArray(body)
                    if (arr.length() == 0) return@withContext
                    val id = arr.getJSONObject(0).optString("id")
                    if (id.isBlank()) return@withContext
                    rowMutex.withLock { lastSeenId = id }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Baseline error", e)
            }
        }
    }

    private fun websocketUrl(settings: AppSettings): String? {
        val base = settings.supabaseUrl.trim().trimEnd('/')
        val key = settings.supabaseAnonKey.trim()
        if (base.isEmpty() || key.isEmpty()) return null
        val wsBase = base.replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val enc = Uri.encode(key)
        return "$wsBase/realtime/v1/websocket?apikey=$enc&vsn=1.0.0"
    }

    private suspend fun connectUntilClosed(url: String) {
        val activeScope = scope ?: return
        suspendCancellableCoroutine { cont ->
            val wsListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket open")
                    onStatus("WebSocket: активен")
                    webSocket.send(realtimeJoinJson())
                    heartbeatJob?.cancel()
                    heartbeatJob = activeScope.launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(HEARTBEAT_MS)
                            webSocket.send(phoenixHeartbeatJson())
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    activeScope.launch(Dispatchers.IO) {
                        handleRealtimeText(text)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1000 || reason.isNotBlank()) {
                        Log.w(TAG, "WebSocket closed code=$code reason=$reason")
                    }
                    onStatus("WebSocket: переподключение…")
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    onStatus("WebSocket: сбой — ${t.message}")
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            val request = Request.Builder().url(url).build()
            val ws = wsHttp.newWebSocket(request, wsListener)
            cont.invokeOnCancellation {
                ws.cancel()
                heartbeatJob?.cancel()
                heartbeatJob = null
            }
        }
    }

    private fun nextRef(): String = refSeq.getAndIncrement().toString()

    private fun realtimeJoinJson(): String {
        val ref = nextRef()
        val postgresChanges = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("event", "INSERT")
                    put("schema", "public")
                    put("table", "messages")
                },
            )
        }
        val config = JSONObject().apply {
            put(
                "broadcast",
                JSONObject().apply {
                    put("ack", false)
                    put("self", false)
                },
            )
            put(
                "presence",
                JSONObject().apply {
                    put("enabled", false)
                },
            )
            put("postgres_changes", postgresChanges)
            put("private", false)
        }
        return JSONObject().apply {
            put("topic", REALTIME_TOPIC)
            put("event", "phx_join")
            put("payload", JSONObject().apply { put("config", config) })
            put("ref", ref)
            put("join_ref", ref)
        }.toString()
    }

    private fun phoenixHeartbeatJson(): String {
        val ref = nextRef()
        return JSONObject().apply {
            put("topic", "phoenix")
            put("event", "heartbeat")
            put("payload", JSONObject())
            put("ref", ref)
        }.toString()
    }

    private suspend fun handleRealtimeText(text: String) {
        try {
            val trimmed = text.trimStart()
            when {
                trimmed.startsWith("{") -> handleRealtimeJsonObject(JSONObject(text))
                trimmed.startsWith("[") -> handleRealtimeJsonArray(JSONArray(text))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Realtime parse error: ${text.take(200)}", e)
        }
    }

    private suspend fun handleRealtimeJsonObject(obj: JSONObject) {
        when (obj.optString("event")) {
            "phx_reply" -> {
                val status = obj.optJSONObject("payload")?.optString("status")
                if (status == "error") {
                    Log.e(TAG, "Realtime join error: ${obj.optJSONObject("payload")}")
                    onStatus("WebSocket: join error")
                }
            }
            "system" -> {
                val payload = obj.optJSONObject("payload") ?: return
                val st = payload.optString("status")
                if (st.isNotEmpty() && st != "ok") {
                    Log.w(TAG, "Realtime system: $st ${payload.optString("message")}")
                }
            }
            "postgres_changes" -> {
                val payload = obj.optJSONObject("payload") ?: return
                dispatchPostgresPayload(payload)
            }
        }
    }

    private suspend fun handleRealtimeJsonArray(arr: JSONArray) {
        if (arr.length() < 4) return
        if (arr.optString(3) != "postgres_changes") return
        val payload = arr.opt(4) as? JSONObject ?: return
        dispatchPostgresPayload(payload)
    }

    private suspend fun dispatchPostgresPayload(payload: JSONObject) {
        val data = payload.optJSONObject("data") ?: return
        if (data.optString("table") != "messages") return
        if (data.optString("type") != "INSERT") return
        val record = data.optJSONObject("record") ?: return

        val id = record.fieldAsString("id") ?: return
        val content = record.optString("content", "")
        val senderId = record.optString("sender_id", "")
        val senderName = record.optString("sender_name", "")
        val recipientName = record.optString("recipient_name", "")
        val createdAt = record.optString("created_at", "")

        processInboundRow(
            ChatMessage(
                id = id,
                senderId = senderId,
                senderName = senderName,
                recipientName = recipientName,
                content = content,
                createdAt = createdAt,
            ),
        )
    }

    private fun JSONObject.fieldAsString(key: String): String? {
        if (!has(key) || JSONObject.NULL == opt(key)) return null
        return when (val raw = opt(key)) {
            is String -> raw.ifBlank { null }
            is Number -> raw.toString()
            else -> optString(key).takeIf { it.isNotBlank() }
        }
    }

    private suspend fun processInboundRow(message: ChatMessage) {
        val shouldEmit = rowMutex.withLock {
            if (message.id.isBlank() || message.id == lastSeenId) return@withLock false
            if (message.content.isBlank()) return@withLock false
            if (LogMessageFormat.isLogContent(message.content)) {
                lastSeenId = message.id
                return@withLock false
            }
            lastSeenId = message.id
            true
        }
        if (!shouldEmit) return

        Log.i(TAG, "New message (WS): ${message.senderName} → ${message.content.take(80)}")
        onMessage(message)
    }

    companion object {
        private const val TAG = "ChatRealtimePoller"
        private const val REALTIME_TOPIC = "realtime:public:messages"
        private const val MIN_RECONNECT_MS = 2_500L
        private const val MAX_RECONNECT_MS = 60_000L
        private const val HEARTBEAT_MS = 20_000L
    }
}
