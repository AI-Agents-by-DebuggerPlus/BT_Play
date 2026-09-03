package com.taskertowpf.androidchatbttest98.data

import android.util.Log
import com.taskertowpf.androidchatbttest98.hrt.isHrtLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ChatRepository(
    private val localLogRepository: LocalLogRepository,
    private val backupFolderStorage: BackupFolderStorage,
) {
    private val mutex = Mutex()
    private var client: SupabaseClient? = null
    private var cachedUserId: String? = null
    private var realtimePoller: ChatRealtimePoller? = null
    private var activeScope: CoroutineScope? = null
    private var activeSettings: AppSettings? = null
    private val downloadedFileMessageIds = mutableSetOf<String>()
    private val failedFileMessageIds = mutableSetOf<String>()
    /** Message ids received after connect (WebSocket / send ack) — only these trigger auto-download. */
    private val sessionIncomingMessageIds = mutableSetOf<String>()
    private val downloadRetryMutex = Mutex()

    /** Fired for new inbound messages addressed to this device (not history, not own sends). */
    var onNewIncomingMessage: ((ChatMessage) -> Unit)? = null

    /** HRT feed: recipient_name=RemoteTerminal (hwt_status / screenshot). Not shown in chat. */
    var onHrtMessage: ((ChatMessage) -> Unit)? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _realtimeStatus = MutableStateFlow("WebSocket: отключено")
    val realtimeStatus: StateFlow<String> = _realtimeStatus.asStateFlow()

    private val _fileTransferStatus = MutableStateFlow("")
    val fileTransferStatus: StateFlow<String> = _fileTransferStatus.asStateFlow()

    private val _fileReceiveLog = MutableStateFlow<List<String>>(emptyList())
    val fileReceiveLog: StateFlow<List<String>> = _fileReceiveLog.asStateFlow()

    suspend fun connect(settings: AppSettings, scope: CoroutineScope): Result<Unit> = runCatching {
        mutex.withLock {
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                error("Supabase URL и anon key обязательны.")
            }

            stopRealtimeLocked()
            client?.close()

            client = createSupabaseClient(
                supabaseUrl = settings.supabaseUrl.trim(),
                supabaseKey = settings.supabaseAnonKey.trim(),
            ) {
                defaultSerializer = KotlinXSerializer(json)
                install(Auth)
                install(Postgrest)
                install(Storage)
            }
            cachedUserId = null
            activeSettings = settings

            sessionIncomingMessageIds.clear()
            failedFileMessageIds.clear()
            downloadedFileMessageIds.clear()
            ensureSessionLocked(settings)
            loadHistoryLocked()
        }

        startRealtime(settings, scope)
    }

    fun updateActiveSettings(settings: AppSettings) {
        activeSettings = settings
    }

    /** Актуальные настройки UI/сессии; для Send с гарнитуры предпочтительнее диска. */
    fun activeSettingsOrNull(): AppSettings? = activeSettings

    /** Retry only files that failed during this session (real-time), not chat history. */
    suspend fun retryDownloadIncomingFiles() {
        if (!downloadRetryMutex.tryLock()) {
            return
        }
        try {
            val pending = mutex.withLock {
                val retryIds = failedFileMessageIds.toSet()
                _messages.value.filter { it.id in retryIds && FileMessageFormat.tryParse(it.content) != null }
            }
            pending.forEach { message ->
                downloadedFileMessageIds.remove(message.id)
                maybeDownloadIncomingFile(message, forceRetry = true)
            }
        } finally {
            downloadRetryMutex.unlock()
        }
    }

    suspend fun sendFile(settings: AppSettings, localFilePath: String): Result<Unit> {
        val fileName = backupFolderStorage.resolveFileName(localFilePath)
            ?: return Result.failure(IllegalArgumentException("Файл не найден."))
        val bytes = runCatching { backupFolderStorage.readBytes(localFilePath) }
            .getOrElse { return Result.failure(it) }
        return sendFileBytes(
            settings = settings,
            fileName = fileName,
            bytes = bytes,
            mime = guessMimeType(fileName),
        )
    }

    suspend fun sendFileBytes(
        settings: AppSettings,
        fileName: String,
        bytes: ByteArray,
        mime: String,
        kind: String = "",
    ): Result<Unit> {
        if (bytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Пустой файл."))
        }
        val safeName = fileName.trim().ifBlank { "file.bin" }
        return runCatching {
            mutex.withLock {
                ensureClientLocked(settings)
                val userId = ensureSessionLocked(settings)
                val active = client ?: error("Supabase не подключён.")
                val storagePath = "$userId/${java.util.UUID.randomUUID().toString().replace("-", "")}/$safeName"

                active.storage.from(FileTransferConstants.CHAT_FILES_BUCKET).upload(storagePath, bytes) {
                    upsert = true
                }

                val payload = FileMessagePayload(
                    type = "file",
                    name = safeName,
                    bucket = FileTransferConstants.CHAT_FILES_BUCKET,
                    path = storagePath,
                    mime = mime.ifBlank { guessMimeType(safeName) },
                    size = bytes.size.toLong(),
                    kind = kind.trim(),
                    nonce = if (kind.equals(FileMessageFormat.KIND_PHOTO, ignoreCase = true)) {
                        java.util.UUID.randomUUID().toString().replace("-", "")
                    } else {
                        ""
                    },
                )

                val row = MessageInsert(
                    senderId = userId,
                    senderName = settings.senderName.trim().ifEmpty { "AndroidChatBtTest98" },
                    recipientName = settings.recipientName.trim().ifEmpty { "WpfChat" },
                    content = FileMessageFormat.buildContent(payload),
                    createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()),
                )

                val saved = active.postgrest.from("messages").insert(row) {
                    select()
                }.decodeSingle<ChatMessage>()

                appendMessage(saved)
                _fileTransferStatus.value = "Отправлено: $safeName"
                Log.i(TAG, "File sent: $safeName kind=${payload.kind} -> $storagePath")
            }
        }
    }

    fun listBackupFiles(settings: AppSettings): List<LocalFileItem> =
        backupFolderStorage.listOutgoingFiles(settings)

    fun listIncomingFiles(settings: AppSettings): List<LocalFileItem> =
        backupFolderStorage.listFiles(settings, FileFolderKind.Incoming)

    suspend fun sendMessage(settings: AppSettings, content: String): Result<Unit> = mutex.withLock {
        val text = content.trim()
        if (text.isEmpty()) {
            return@withLock Result.failure(IllegalArgumentException("Пустое сообщение."))
        }

        ensureClientLocked(settings)
        val first = runCatching { insertMessageLocked(settings, text) }
        if (first.isSuccess) {
            return@withLock first
        }

        Log.w(TAG, "Send failed, retry after re-auth: ${first.exceptionOrNull()?.message}")
        cachedUserId = null
        runCatching { client?.auth?.signOut() }
        runCatching { insertMessageLocked(settings, text) }
    }

    private suspend fun insertMessageLocked(settings: AppSettings, text: String) {
        insertRoutedMessageLocked(
            settings = settings,
            senderName = settings.senderName.trim().ifEmpty { "AndroidChatBtTest98" },
            recipientName = settings.recipientName.trim().ifEmpty { "WpfChat" },
            content = text,
        )
    }

    /**
     * INSERT с явным маршрутом (HRT-команды на Hermes.Mt5Terminal).
     * В чат попадает только если [ChatMessageFilter.shouldShowInChat].
     */
    suspend fun sendRoutedMessage(
        settings: AppSettings,
        senderName: String,
        recipientName: String,
        content: String,
    ): Result<Unit> = mutex.withLock {
        val text = content.trim()
        if (text.isEmpty()) {
            return@withLock Result.failure(IllegalArgumentException("Пустое сообщение."))
        }
        runCatching {
            ensureClientLocked(settings)
            insertRoutedMessageLocked(settings, senderName, recipientName, text)
        }
    }

    private suspend fun insertRoutedMessageLocked(
        settings: AppSettings,
        senderName: String,
        recipientName: String,
        content: String,
    ) {
        val userId = ensureSessionLocked(settings)
        val active = client ?: error("Supabase не подключён.")

        val row = MessageInsert(
            senderId = userId,
            senderName = senderName.trim().ifEmpty { "AndroidChatBtTest98" },
            recipientName = recipientName.trim().ifEmpty { "WpfChat" },
            content = content,
            createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()),
        )

        val saved = active.postgrest.from("messages").insert(row) {
            select()
        }.decodeSingle<ChatMessage>()

        appendMessage(saved)
    }

    /** Последний hwt_status (+ опционально скрин) для RemoteTerminal. Разовый SELECT, не poll. */
    suspend fun fetchHrtSnapshot(includeScreenshot: Boolean = false): Result<com.taskertowpf.androidchatbttest98.hrt.HrtSnapshot> =
        mutex.withLock {
            runCatching {
                val active = client ?: error("Supabase не подключён.")
                val rows = active.postgrest.from("messages").select {
                    filter {
                        eq("recipient_name", com.taskertowpf.androidchatbttest98.hrt.HrtProtocol.RECIPIENT)
                    }
                    order(column = "created_at", order = Order.DESCENDING)
                    limit(com.taskertowpf.androidchatbttest98.hrt.HrtProtocol.SNAPSHOT_LIMIT.toLong())
                }.decodeList<ChatMessage>()

                val forHrt = rows.filter { com.taskertowpf.androidchatbttest98.hrt.HrtProtocol.isRemoteTerminal(it) }
                var status: ChatMessage? = null
                var shot: ChatMessage? = null
                for (row in forHrt) {
                    if (row.isHrtLog()) continue
                    if (status == null && com.taskertowpf.androidchatbttest98.hrt.HwtStatus.tryParse(row.content) != null) {
                        status = row
                    }
                    if (includeScreenshot
                        && shot == null
                        && (
                            com.taskertowpf.androidchatbttest98.hrt.HwtScreenshot.tryParse(row.content) != null
                                || com.taskertowpf.androidchatbttest98.hrt.HwtScreenshot.tryParseRepeat(row.content)
                            )
                    ) {
                        shot = row
                    }
                    if (status != null && (!includeScreenshot || shot != null)) break
                }
                com.taskertowpf.androidchatbttest98.hrt.HrtSnapshot(status, shot)
            }
        }

    suspend fun downloadStorageObject(bucket: String, path: String): Result<ByteArray> = mutex.withLock {
        runCatching {
            val settings = activeSettings ?: error("Supabase не подключён.")
            val active = client ?: error("Supabase не подключён.")
            ensureSessionLocked(settings)
            active.storage.from(bucket.ifBlank { FileTransferConstants.CHAT_FILES_BUCKET })
                .downloadAuthenticated(path)
        }
    }

    suspend fun disconnect() = mutex.withLock {
        stopRealtimeLocked()
        client?.close()
        client = null
        cachedUserId = null
        sessionIncomingMessageIds.clear()
        failedFileMessageIds.clear()
        downloadedFileMessageIds.clear()
        _messages.value = emptyList()
        _realtimeStatus.value = "WebSocket: отключено"
    }

    fun currentUserId(): String? = cachedUserId

    fun isReady(): Boolean = client != null && !cachedUserId.isNullOrBlank()

    /** Подключение для реле гарнитуры → WpfChat без пересоздания существующей сессии. */
    suspend fun ensureConnectedForRelay(settings: AppSettings, scope: CoroutineScope): Result<Unit> =
        runCatching {
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                error("Supabase URL и anon key обязательны.")
            }
            mutex.withLock {
                ensureClientLocked(settings)
                ensureSessionLocked(settings)
                activeSettings = settings
            }
            if (realtimePoller == null && settings.enableSupabasePoll) {
                startRealtime(settings, scope)
            }
        }

    private suspend fun ensureClientLocked(settings: AppSettings) {
        if (client != null) {
            return
        }

        client = createSupabaseClient(
            supabaseUrl = settings.supabaseUrl.trim(),
            supabaseKey = settings.supabaseAnonKey.trim(),
        ) {
            defaultSerializer = KotlinXSerializer(json)
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
        cachedUserId = null
    }

    private suspend fun ensureSessionLocked(settings: AppSettings): String {
        val active = client ?: error("Supabase не подключён.")
        val existing = active.auth.currentUserOrNull()?.id
        if (!existing.isNullOrBlank()) {
            cachedUserId = existing
            return existing
        }

        if (!settings.useAnonymousAuth) {
            error("Нет сессии. Включите анонимную аутентификацию.")
        }

        active.auth.signInAnonymously()
        val userId = active.auth.currentUserOrNull()?.id
            ?: error("Анонимная сессия не создана.")
        cachedUserId = userId
        return userId
    }

    suspend fun refreshHistory(): Result<Int> = mutex.withLock {
        runCatching {
            loadHistoryLocked()
            _messages.value.size
        }
    }

    /** Удаляет все строки public.messages в Supabase и очищает локальный список. */
    suspend fun clearAllMessages(): Result<Int> = mutex.withLock {
        runCatching {
            val active = client ?: error("Supabase не подключён.")
            val rows = active.postgrest.from("messages").select().decodeList<ChatMessage>()
            if (rows.isEmpty()) {
                _messages.value = emptyList()
                return@runCatching 0
            }

            var deleted = 0
            for (chunk in rows.chunked(100)) {
                val ids = chunk.map { it.id }.filter { it.isNotBlank() }
                if (ids.isEmpty()) {
                    continue
                }
                active.postgrest.from("messages").delete {
                    filter {
                        isIn("id", ids)
                    }
                }
                deleted += ids.size
            }

            val remaining = active.postgrest.from("messages").select().decodeList<ChatMessage>()
            if (remaining.isNotEmpty()) {
                error(
                    "Не удалены все сообщения на сервере (осталось ${remaining.size}). " +
                        "Проверьте политику RLS DELETE для таблицы messages.",
                )
            }

            _messages.value = emptyList()
            sessionIncomingMessageIds.clear()
            failedFileMessageIds.clear()
            downloadedFileMessageIds.clear()
            deleted
        }
    }

    private suspend fun loadHistoryLocked() {
        val active = client ?: return
        val settings = activeSettings ?: return
        val rows = active.postgrest.from("messages").select {
            order(column = "created_at", order = Order.ASCENDING)
        }.decodeList<ChatMessage>()

        val filtered = rows.filter { ChatMessageFilter.shouldShowInChat(it, settings) }
        _messages.value = filtered
        // History is shown in chat only; files are downloaded for real-time messages after connect.
    }

    private fun startRealtime(settings: AppSettings, scope: CoroutineScope) {
        stopRealtimeLocked()
        activeScope = scope

        if (!settings.enableSupabasePoll) {
            _realtimeStatus.value = "WebSocket: выключено (настройки)"
            Log.i(TAG, "WebSocket realtime disabled in settings")
            return
        }

        val poller = ChatRealtimePoller(
            onMessage = { message -> appendMessage(message) },
            onStatus = { status -> _realtimeStatus.value = status },
        )
        realtimePoller = poller
        poller.start(settings, scope)
        Log.i(TAG, "WebSocket realtime poller started")
    }

    private fun appendMessage(message: ChatMessage) {
        val settings = activeSettings ?: return
        if (com.taskertowpf.androidchatbttest98.hrt.HrtProtocol.isRemoteTerminal(message)) {
            if (!message.isHrtLog()) {
                onHrtMessage?.invoke(message)
            }
            return
        }
        if (!ChatMessageFilter.shouldShowInChat(message, settings)) {
            return
        }

        val isNew = _messages.value.none { it.id == message.id }
        _messages.update { current ->
            if (current.any { it.id == message.id }) {
                current
            } else {
                (current + message).sortedBy { it.createdAt }
            }
        }

        if (isNew) {
            sessionIncomingMessageIds.add(message.id)
            maybeDownloadIncomingFile(message)
            if (ChatMessageFilter.shouldAutoSpeak(message, settings)) {
                onNewIncomingMessage?.invoke(message)
            }
        }
    }

    private fun maybeDownloadIncomingFile(message: ChatMessage, forceRetry: Boolean = false) {
        val payload = FileMessageFormat.tryParse(message.content) ?: return
        val settings = activeSettings ?: return
        val userId = cachedUserId ?: return

        if (message.senderId.equals(userId, ignoreCase = true)) {
            return
        }

        if (!forceRetry && !sessionIncomingMessageIds.contains(message.id)) {
            return
        }

        if (!forceRetry && failedFileMessageIds.contains(message.id)) {
            return
        }

        if (downloadedFileMessageIds.contains(message.id)) {
            return
        }

        val scope = activeScope ?: return

        if (!downloadedFileMessageIds.add(message.id)) {
            return
        }

        scope.launch {
            val result = runCatching { downloadIncomingFileLocked(settings, payload) }
            result.onSuccess { downloadResult ->
                val status = "Сохранено: ${payload.name}\n${downloadResult.path}"
                _fileTransferStatus.value = status
                if (downloadResult.alreadyExisted) {
                    appendFileReceiveLog("SKIP ${payload.name} → ${downloadResult.path}")
                } else {
                    appendFileReceiveLog("OK   ${payload.name} → ${downloadResult.path}")
                    notifyFileSavedInChat(settings, payload.name)
                }
                Log.i(TAG, "File saved: ${downloadResult.path}")
            }.onFailure { error ->
                downloadedFileMessageIds.remove(message.id)
                failedFileMessageIds.add(message.id)
                val errorText = error.message ?: "неизвестная ошибка"
                _fileTransferStatus.value = "Ошибка загрузки: $errorText"
                appendFileReceiveLog("ERR ${payload.name}: $errorText")
                Log.e(TAG, "File download failed", error)
            }
        }
    }

    private suspend fun downloadIncomingFileLocked(
        settings: AppSettings,
        payload: FileMessagePayload,
    ): FileDownloadResult =
        mutex.withLock {
            val active = client ?: error("Supabase не подключён.")
            ensureSessionLocked(settings)

            val bytes = active.storage.from(payload.bucket).downloadAuthenticated(payload.path)
            val writeResult = backupFolderStorage.writeIncomingFile(settings, payload.name, bytes)
            FileDownloadResult(writeResult.path, writeResult.alreadyExisted)
        }

    private data class FileDownloadResult(
        val path: String,
        val alreadyExisted: Boolean,
    )

    private suspend fun notifyFileSavedInChat(settings: AppSettings, fileName: String) {
        runCatching {
            mutex.withLock {
                val text = "📁 Файл сохранён во входящие: $fileName. Экран «Файлы»."
                insertMessageLocked(settings, text)
            }
        }.onFailure { error ->
            Log.w(TAG, "Chat notify for saved file failed", error)
        }
    }

    private fun appendFileReceiveLog(line: String) {
        val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss")
            .format(OffsetDateTime.now())
        _fileReceiveLog.update { current ->
            (current + "[$timestamp] $line").takeLast(30)
        }
        activeScope?.launch {
            localLogRepository.logLocal("FileTransfer", line)
        }
    }

    private fun guessMimeType(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "xml" -> "application/xml"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    private fun stopRealtimeLocked() {
        realtimePoller?.stop()
        realtimePoller = null
        activeScope = null
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}
