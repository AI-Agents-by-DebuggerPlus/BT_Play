package com.taskertowpf.androidchatbttest98.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class SupabaseRepository {
    private val mutex = Mutex()
    private var client: SupabaseClient? = null
    private var cachedUserId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun connect(settings: AppSettings): Result<Unit> = mutex.withLock {
        runCatching {
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                error("Supabase URL и anon key обязательны.")
            }

            client?.close()
            client = createSupabaseClient(
                supabaseUrl = settings.supabaseUrl.trim(),
                supabaseKey = settings.supabaseAnonKey.trim(),
            ) {
                defaultSerializer = KotlinXSerializer(json)
                install(Auth)
                install(Postgrest)
            }
            cachedUserId = null
        }
    }

    suspend fun ensureSession(settings: AppSettings): Result<String> = mutex.withLock {
        runCatching {
            ensureSessionLocked(settings)
        }
    }

    suspend fun sendLogMessage(
        settings: AppSettings,
        category: String,
        message: String,
        senderName: String = "AndroidChatBtTest98",
    ): Result<Unit> = mutex.withLock {
        runCatching {
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                error("Supabase URL и anon key обязательны.")
            }

            if (client == null) {
                client = createSupabaseClient(
                    supabaseUrl = settings.supabaseUrl.trim(),
                    supabaseKey = settings.supabaseAnonKey.trim(),
                ) {
                    defaultSerializer = KotlinXSerializer(json)
                    install(Auth)
                    install(Postgrest)
                }
                cachedUserId = null
            }

            val userId = ensureSessionLocked(settings)
            val active = client ?: error("Supabase не подключён.")

            val content = com.taskertowpf.androidchatbttest98.LogMessageFormat.buildContent(category, message)
            val row = MessageInsert(
                senderId = userId,
                senderName = senderName.trim().ifEmpty { "AndroidChatBtTest98" },
                recipientName = settings.recipientName.trim().ifEmpty { "WpfChat" },
                content = content,
                createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()),
            )

            active.postgrest.from("messages").insert(row)
            Unit
        }
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

    fun currentUserId(): String? = cachedUserId

    suspend fun disconnect() = mutex.withLock {
        client?.close()
        client = null
        cachedUserId = null
    }
}
