package com.taskertowpf.androidchatbttest98.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AppSettings {
        val defaults = loadDefaultSettings()
        val raw = prefs.getString(KEY_SETTINGS, null)
        val loaded = if (!raw.isNullOrBlank()) {
            val saved = runCatching { json.decodeFromString<AppSettings>(raw) }
                .getOrDefault(defaults)
            mergeMissingDefaults(saved, defaults)
        } else {
            defaults
        }
        val migrated = migrateOpenRouter(migrateDeprecatedOpenRouterModel(migrateNativeBtPlayDefaults(loaded)))
        val normalized = migrated.withNormalizedRecipients()
        // Не перезаписывать prefs при каждом load(): гонка с selectRecipient/save затирала recipient.
        if (raw.isNullOrBlank()) {
            save(normalized, commit = true)
        }
        return normalized
    }

    private fun migrateNativeBtPlayDefaults(settings: AppSettings): AppSettings {
        if (prefs.getBoolean(KEY_MIGRATED_NATIVE_BT_PLAY, false)) {
            return settings
        }
        val updated = settings.copy(enableNativeHeadsetCapture = true)
        prefs.edit().putBoolean(KEY_MIGRATED_NATIVE_BT_PLAY, true).apply()
        return updated
    }

    private fun migrateDeprecatedOpenRouterModel(settings: AppSettings): AppSettings {
        val model = settings.openRouterModel.trim()
        if (model !in DEPRECATED_OPENROUTER_MODELS) {
            return settings
        }
        return settings.copy(openRouterModel = OpenRouterService.DEFAULT_MODEL)
    }

    private fun migrateOpenRouter(settings: AppSettings): AppSettings {
        val key = settings.resolvedOpenRouterApiKey()
        val model = settings.resolvedOpenRouterModel()
        if (key == settings.openRouterApiKey && model == settings.openRouterModel) {
            return settings
        }
        return settings.copy(
            openRouterApiKey = key,
            openRouterModel = model,
            geminiApiKey = "",
            geminiModel = "",
        )
    }

    private fun mergeMissingDefaults(saved: AppSettings, defaults: AppSettings): AppSettings {
        val migrated = migrateLegacyFolders(saved, defaults)
        val withRecipients = migrateRecipientNames(migrated, defaults)
        return withRecipients.copy(
            openRouterApiKey = withRecipients.resolvedOpenRouterApiKey()
                .ifBlank { defaults.resolvedOpenRouterApiKey() },
            openRouterModel = withRecipients.resolvedOpenRouterModel()
                .ifBlank { defaults.resolvedOpenRouterModel() },
            openAiApiKey = withRecipients.openAiApiKey.ifBlank { defaults.openAiApiKey },
            openAiTtsModel = withRecipients.openAiTtsModel.ifBlank { defaults.openAiTtsModel },
            ttsEngine = withRecipients.ttsEngine.ifBlank { defaults.ttsEngine },
            voiceInputLocale = withRecipients.voiceInputLocale.ifBlank { defaults.voiceInputLocale },
        )
    }

    private fun migrateRecipientNames(saved: AppSettings, defaults: AppSettings): AppSettings {
        if (saved.recipientNames.any { it.isNotBlank() }) {
            return saved.withNormalizedRecipients()
        }
        val current = saved.recipientName.trim()
            .ifBlank { defaults.recipientName.trim().ifBlank { "WpfChat" } }
        val fromDefaults = defaults.recipientNames.map { it.trim() }.filter { it.isNotEmpty() }
        val names = (listOf(current) + fromDefaults).distinct()
        return saved.copy(recipientNames = names, recipientName = current).withNormalizedRecipients()
    }

    fun save(settings: AppSettings, commit: Boolean = false) {
        val editor = prefs.edit()
            .putString(KEY_SETTINGS, json.encodeToString(settings.withNormalizedRecipients()))
        if (commit) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    private fun migrateLegacyFolders(saved: AppSettings, defaults: AppSettings): AppSettings {
        val legacyFolder = saved.fileBackupsFolder.trim()
        val legacyUri = saved.fileBackupsTreeUri.trim()

        val outgoingFolder = normalizeOutgoingPath(
            saved.fileOutgoingFolder.trim().ifBlank {
                legacyFolder.ifBlank { defaults.fileOutgoingFolder }
            },
            defaults.fileOutgoingFolder,
        )
        val outgoingUri = saved.fileOutgoingTreeUri.trim().ifBlank { legacyUri }

        val incomingFolder = saved.fileIncomingFolder.trim().ifBlank {
            defaults.fileIncomingFolder
        }
        val incomingUri = saved.fileIncomingTreeUri.trim()

        return saved.copy(
            fileOutgoingFolder = outgoingFolder,
            fileOutgoingTreeUri = outgoingUri,
            fileIncomingFolder = incomingFolder,
            fileIncomingTreeUri = incomingUri,
        )
    }

    private fun normalizeOutgoingPath(path: String, default: String): String {
        if (path.contains("BackUps", ignoreCase = true)) {
            return default
        }
        return path
    }

    private fun loadDefaultSettings(): AppSettings {
        return runCatching {
            appContext.assets.open(DEFAULT_ASSET).bufferedReader().use { reader ->
                json.decodeFromString<AppSettings>(reader.readText())
            }
        }.getOrDefault(AppSettings())
    }

    companion object {
        private const val PREFS_NAME = "androidchatbttest98_settings"
        private const val KEY_SETTINGS = "settings_json"
        private const val KEY_MIGRATED_NATIVE_BT_PLAY = "migrated_native_bt_play_v19"
        private val DEPRECATED_OPENROUTER_MODELS = setOf(
            "google/gemini-2.0-flash-001",
            "google/gemini-2.0-flash-lite-001",
            "google/gemini-flash-1.5",
        )
        private const val DEFAULT_ASSET = "default_settings.json"
    }
}
