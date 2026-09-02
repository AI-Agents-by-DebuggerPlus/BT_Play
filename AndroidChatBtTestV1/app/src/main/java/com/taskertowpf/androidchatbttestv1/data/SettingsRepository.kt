package com.taskertowpf.androidchatbttestv1.data

import android.content.Context
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppSettings {
        val defaults = loadDefaultSettings()
        val raw = prefs.getString(KEY_SETTINGS, null)?.trim()
        val saved = when {
            raw.isNullOrBlank() || raw == "{}" -> null
            else -> runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        }
        val base = saved ?: defaults
        val merged = base.copy(
            supabaseUrl = base.supabaseUrl.trim().ifBlank { defaults.supabaseUrl },
            supabaseAnonKey = base.supabaseAnonKey.trim().ifBlank { defaults.supabaseAnonKey },
            senderName = base.senderName.trim()
                .ifBlank { defaults.senderName }
                .let { if (it == "AndroidChatBtTest") defaults.senderName else it },
            recipientName = base.recipientName.trim().ifBlank { defaults.recipientName },
            useAnonymousAuth = base.useAnonymousAuth,
        )
        // Всегда пишем в prefs итоговые URL/key, чтобы после рестарта UI и upload видели их.
        if (raw.isNullOrBlank() || merged != saved) {
            save(merged)
        }
        return merged
    }

    fun save(settings: AppSettings) {
        val defaults = loadDefaultSettings()
        val toStore = settings.copy(
            supabaseUrl = settings.supabaseUrl.trim().ifBlank { defaults.supabaseUrl },
            supabaseAnonKey = settings.supabaseAnonKey.trim().ifBlank { defaults.supabaseAnonKey },
            senderName = settings.senderName.trim().ifBlank { defaults.senderName },
            recipientName = settings.recipientName.trim().ifBlank { defaults.recipientName },
        )
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(AppSettings.serializer(), toStore)).apply()
    }

    private fun loadDefaultSettings(): AppSettings {
        val text = runCatching {
            appContext.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        if (text.isBlank()) {
            return BUILT_IN_DEFAULTS
        }
        return runCatching { json.decodeFromString<AppSettings>(text) }
            .getOrElse { BUILT_IN_DEFAULTS }
            .let { fromAsset ->
                fromAsset.copy(
                    supabaseUrl = fromAsset.supabaseUrl.trim().ifBlank { BUILT_IN_DEFAULTS.supabaseUrl },
                    supabaseAnonKey = fromAsset.supabaseAnonKey.trim()
                        .ifBlank { BUILT_IN_DEFAULTS.supabaseAnonKey },
                    senderName = fromAsset.senderName.trim().ifBlank { BUILT_IN_DEFAULTS.senderName },
                    recipientName = fromAsset.recipientName.trim()
                        .ifBlank { BUILT_IN_DEFAULTS.recipientName },
                )
            }
    }

    companion object {
        // v2 — сброс старых prefs с пустыми supabaseUrl/anonKey.
        private const val PREFS_NAME = "androidchatbttestv1_settings_v2"
        private const val KEY_SETTINGS = "settings_json"
        private const val DEFAULT_ASSET = "default_settings.json"

        private val BUILT_IN_DEFAULTS = AppSettings(
            supabaseUrl = "https://dauvhkttddxmqfkfunqg.supabase.co",
            supabaseAnonKey = "sb_publishable_D1-ieyE_Tskl6BUrOSJ7RA_x-9spRcz",
            senderName = "AndroidChatBtTestV1",
            recipientName = "WpfChat",
            useAnonymousAuth = true,
        )
    }
}
