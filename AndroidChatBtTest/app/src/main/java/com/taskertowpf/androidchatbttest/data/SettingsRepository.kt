package com.taskertowpf.androidchatbttest.data

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
        val merged = (saved ?: defaults).let { current ->
            current.copy(
                supabaseUrl = savedOrDefault(current.supabaseUrl, defaults.supabaseUrl),
                supabaseAnonKey = savedOrDefault(current.supabaseAnonKey, defaults.supabaseAnonKey),
                senderName = current.senderName.ifBlank { defaults.senderName },
                recipientName = current.recipientName.ifBlank { defaults.recipientName },
            )
        }
        if (merged != saved || saved == null) {
            save(merged)
        }
        return merged
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(AppSettings.serializer(), settings)).apply()
    }

    private fun savedOrDefault(value: String, default: String): String =
        value.trim().ifBlank { default }

    private fun loadDefaultSettings(): AppSettings {
        val text = runCatching {
            appContext.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        if (text.isBlank()) {
            return BUILT_IN_DEFAULTS
        }
        return runCatching { json.decodeFromString<AppSettings>(text) }
            .getOrDefault(BUILT_IN_DEFAULTS)
            .let { fromAsset ->
                fromAsset.copy(
                    supabaseUrl = savedOrDefault(fromAsset.supabaseUrl, BUILT_IN_DEFAULTS.supabaseUrl),
                    supabaseAnonKey = savedOrDefault(fromAsset.supabaseAnonKey, BUILT_IN_DEFAULTS.supabaseAnonKey),
                    senderName = fromAsset.senderName.ifBlank { BUILT_IN_DEFAULTS.senderName },
                    recipientName = fromAsset.recipientName.ifBlank { BUILT_IN_DEFAULTS.recipientName },
                )
            }
    }

    companion object {
        private const val PREFS_NAME = "androidchatbttest_settings"
        private const val KEY_SETTINGS = "settings_json"
        private const val DEFAULT_ASSET = "default_settings.json"

        // Реальные URL/key — только в gitignored default_settings.json на устройстве/сборке.
        private val BUILT_IN_DEFAULTS = AppSettings(
            supabaseUrl = "",
            supabaseAnonKey = "",
            senderName = "AndroidChatBtTest",
            recipientName = "WpfChat",
            useAnonymousAuth = true,
        )
    }
}
