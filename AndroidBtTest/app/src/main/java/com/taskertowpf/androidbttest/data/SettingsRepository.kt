package com.taskertowpf.androidbttest.data

import android.content.Context
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AppSettings {
        val defaults = loadDefaultSettings()
        val raw = prefs.getString(KEY_SETTINGS, null)
        if (raw.isNullOrBlank()) {
            save(defaults)
            return defaults
        }
        return runCatching { json.decodeFromString<AppSettings>(raw) }
            .getOrDefault(defaults)
            .let { saved ->
                saved.copy(
                    supabaseUrl = savedOrDefault(saved.supabaseUrl, defaults.supabaseUrl),
                    supabaseAnonKey = saved.supabaseAnonKey.ifBlank { defaults.supabaseAnonKey },
                    senderName = saved.senderName.ifBlank { defaults.senderName },
                    recipientName = saved.recipientName.ifBlank { defaults.recipientName },
                )
            }
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
            return AppSettings()
        }
        return runCatching { json.decodeFromString<AppSettings>(text) }.getOrDefault(AppSettings())
    }

    companion object {
        private const val PREFS_NAME = "androidbttest_settings"
        private const val KEY_SETTINGS = "settings_json"
        private const val DEFAULT_ASSET = "default_settings.json"
    }
}
