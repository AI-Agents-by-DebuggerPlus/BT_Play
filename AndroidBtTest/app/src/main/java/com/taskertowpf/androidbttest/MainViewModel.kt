package com.taskertowpf.androidbttest

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskertowpf.androidbttest.bluetooth.BluetoothAclReceiver
import com.taskertowpf.androidbttest.bluetooth.BluetoothAdapterStateReceiver
import com.taskertowpf.androidbttest.bluetooth.BluetoothSnapshot
import com.taskertowpf.androidbttest.data.AppSettings
import com.taskertowpf.androidbttest.data.LocalLogEntry
import com.taskertowpf.androidbttest.headset.HeadsetButtonNames
import com.taskertowpf.androidbttest.headset.HeadsetMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

data class BtTestUiState(
    val settings: AppSettings = AppSettings(),
    val snapshot: BluetoothSnapshot? = null,
    val logEntries: List<LocalLogEntry> = emptyList(),
    val statusText: String = "",
    val isBusy: Boolean = false,
    val showSettings: Boolean = false,
    val selectedTab: Int = 0,
    val supabaseUrlDraft: String = "",
    val supabaseAnonDraft: String = "",
    val recipientDraft: String = "",
    val nativeCaptureOn: Boolean = true,
    val btPlayPressCount: Int = 0,
    val btPlayLastLabel: String = "",
    val btPlayLastAt: String = "",
    val btPlayEventLog: List<String> = emptyList(),
    val activeSessionOwners: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndroidBtTestApp
    private var aclReceiver: BluetoothAclReceiver? = null
    private var adapterStateReceiver: BluetoothAdapterStateReceiver? = null
    private var stopInProgress = false
    private var exitAppCallback: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(BtTestUiState(settings = app.settingsRepository.load()))
    val uiState: StateFlow<BtTestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.localLogRepository.entries.collect { entries ->
                _uiState.update { it.copy(logEntries = entries) }
            }
        }
        app.headsetButtonNotifier.onButton = { label, source ->
            recordHeadsetEvent(label, source)
        }
        app.headsetButtonNotifier.onActiveSessionsChanged = { owners ->
            _uiState.update { it.copy(activeSessionOwners = owners) }
        }
        HeadsetMonitorService.start(getApplication())
        viewModelScope.launch {
            delay(800)
            app.speechService.speak("Android Bt Test is ready")
            logLocal("App", "Application started — ${AppBuildInfo.versionLabel}")
            HeadsetMonitorService.reassert(getApplication())
            HeadsetMonitorService.diagnose(getApplication())
            scanBluetooth(speakResult = false)
        }
        viewModelScope.launch {
            while (true) {
                delay(STATUS_POLL_MS)
                refreshStatusQuiet()
            }
        }
        registerAcl()
        registerAdapterState()
    }

    fun bindAppExit(onExit: () -> Unit) {
        exitAppCallback = onExit
    }

    fun selectTab(index: Int) {
        val tab = index.coerceIn(0, 3)
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == 1) {
            // При открытии Play — поднять MediaSession и снять диагностический снимок.
            reassertHeadsetCapture(speakCue = false)
        }
    }

    fun reassertHeadsetCapture(speakCue: Boolean = true) {
        val ctx = getApplication<Application>()
        logLocal(
            "HeadsetDiag",
            "UI requested reassert; stop AndroidChat/music if Play still silent",
        )
        HeadsetMonitorService.reassert(ctx)
        HeadsetMonitorService.diagnose(ctx)
        _uiState.update {
            it.copy(
                nativeCaptureOn = true,
                statusText = "MediaSession reassert + diagnose",
            )
        }
        if (speakCue) {
            app.speechService.speak("Session ready")
        }
    }

    fun diagnoseHeadsetCapture() {
        logLocal("HeadsetDiag", "UI requested diagnose dump")
        HeadsetMonitorService.diagnose(getApplication())
        _uiState.update { it.copy(statusText = "Диагностика MediaSession записана в логи") }
    }

    fun openNotificationAccessSettings() {
        logLocal("HeadsetDiag", "UI open Notification Access settings")
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
        _uiState.update {
            it.copy(statusText = "Включите AndroidBtTest в Notification Access, затем Диагностика")
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        logLocal("Bluetooth", if (granted) "BLUETOOTH_CONNECT granted" else "BLUETOOTH_CONNECT denied")
        if (granted) {
            app.bluetoothInventory.bindProfiles()
        }
        scanBluetooth(speakResult = false)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        logLocal(
            "HeadsetDiag",
            if (granted) {
                "POST_NOTIFICATIONS granted (FGS MediaStyle visible)"
            } else {
                "POST_NOTIFICATIONS denied — media session may lose button priority"
            },
        )
        if (granted) {
            HeadsetMonitorService.reassert(getApplication())
        }
    }

    fun scanBluetooth(speakResult: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Сканирование Bluetooth…") }
            val snap = withContext(Dispatchers.IO) {
                app.bluetoothInventory.waitForProfiles()
                app.bluetoothInventory.snapshot()
            }
            _uiState.update {
                it.copy(
                    snapshot = snap,
                    statusText = snap.activeSummary,
                    isBusy = false,
                )
            }
            logLocal(
                "Bluetooth",
                "Scan: adapter=${snap.adapterName} enabled=${snap.adapterEnabled} " +
                    "profiles=${snap.profilesReady} devices=${snap.devices.size} " +
                    "connected=${snap.devices.count { it.connected }} active=${snap.activeSummary}",
            )
            snap.devices.forEach { row ->
                logLocal(
                    "Bluetooth",
                    "${row.name} [${row.address}] ${row.roleLabel} " +
                        "connected=${row.connected} audioRouted=${row.audioRouted} " +
                        "hfpAudio=${row.hfpAudio} a2dpPlaying=${row.a2dpPlaying}",
                )
            }
            if (speakResult) {
                val connected = snap.devices.filter { it.connected }
                val phrase = if (connected.isEmpty()) {
                    "Нет подключённых bluetooth устройств"
                } else {
                    connected.joinToString(". ") { row -> "${row.name} подключены" }
                }
                app.speechService.speakRussian(phrase)
            }
        }
    }

    fun simulatePlay() {
        app.headsetButtonNotifier.notifyButton("MEDIA_PLAY", source = "ui-simulate")
    }

    fun resetPlayCounter() {
        _uiState.update {
            it.copy(
                btPlayPressCount = 0,
                btPlayLastLabel = "",
                btPlayLastAt = "",
                btPlayEventLog = emptyList(),
            )
        }
        logLocal("Headset", "BT Play counter reset")
    }

    fun openSettings() {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                showSettings = true,
                supabaseUrlDraft = settings.supabaseUrl,
                supabaseAnonDraft = settings.supabaseAnonKey,
                recipientDraft = settings.recipientName,
            )
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun updateUrlDraft(value: String) {
        _uiState.update { it.copy(supabaseUrlDraft = value) }
    }

    fun updateAnonDraft(value: String) {
        _uiState.update { it.copy(supabaseAnonDraft = value) }
    }

    fun updateRecipientDraft(value: String) {
        _uiState.update { it.copy(recipientDraft = value) }
    }

    fun saveSettings() {
        val next = _uiState.value.settings.copy(
            supabaseUrl = _uiState.value.supabaseUrlDraft.trim(),
            supabaseAnonKey = _uiState.value.supabaseAnonDraft.trim(),
            recipientName = _uiState.value.recipientDraft.trim().ifBlank { "WpfChat" },
        )
        app.settingsRepository.save(next)
        _uiState.update { it.copy(settings = next, showSettings = false, statusText = "Настройки сохранены") }
        logLocal("Settings", "Saved; recipient=${next.recipientName}")
    }

    fun sendLogsToSupabase() {
        viewModelScope.launch {
            val entries = _uiState.value.logEntries
            if (entries.isEmpty()) {
                _uiState.update { it.copy(statusText = "Нет логов для отправки") }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, statusText = "Отправка логов…") }
            val settings = _uiState.value.settings
            var sent = 0
            var failed = 0
            var skipped = 0
            for (entry in entries) {
                if (entry.shouldSkipManualSupabaseUpload(skipDuplicates = true)) {
                    skipped++
                    continue
                }
                val message = "${entry.message} (${entry.status})"
                val result = app.supabaseRepository.sendLogMessage(
                    settings = settings,
                    category = entry.category,
                    message = message,
                )
                if (result.isSuccess) {
                    sent++
                    app.localLogRepository.markSent { it.timestampMillis == entry.timestampMillis }
                } else {
                    failed++
                    logLocal("Supabase", "Send failed: ${result.exceptionOrNull()?.message}")
                }
            }
            val status = "Логи: отправлено $sent, пропущено $skipped, ошибок $failed"
            logLocal("Supabase", "Log batch sent: $sent ok, $failed failed, $skipped skipped")
            _uiState.update { it.copy(isBusy = false, statusText = status) }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            app.localLogRepository.clear()
            _uiState.update { it.copy(statusText = "Логи очищены") }
        }
    }

    fun stopApp() {
        if (stopInProgress) {
            return
        }
        stopInProgress = true
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Остановка AndroidBtTest…") }
            logLocal("App", "Stop requested")
            suspendCancellableCoroutine { continuation ->
                app.speechService.speakAndThen("Android Bt Test will be stopped.") {
                    continuation.resume(Unit)
                }
            }
            app.headsetButtonNotifier.onButton = null
            app.headsetButtonNotifier.onActiveSessionsChanged = null
            HeadsetMonitorService.stop(getApplication())
            unregisterAcl()
            unregisterAdapterState()
            app.bluetoothInventory.unbindProfiles()
            app.speechService.shutdown()
            exitAppCallback?.invoke()
        }
    }

    private fun recordHeadsetEvent(label: String, source: String) {
        val at = DateTimeFormatter.ofPattern("HH:mm:ss")
            .format(Instant.now().atZone(ZoneId.systemDefault()))
        val isPlay = HeadsetButtonNames.isBtPlayLabel(label)
        _uiState.update { state ->
            val line = "$at  $label  ($source)"
            state.copy(
                selectedTab = 1,
                btPlayPressCount = if (isPlay) state.btPlayPressCount + 1 else state.btPlayPressCount,
                btPlayLastLabel = label,
                btPlayLastAt = at,
                btPlayEventLog = (listOf(line) + state.btPlayEventLog).take(40),
                statusText = "Headset: $label",
            )
        }
        if (isPlay) {
            app.speechService.speak("Play")
        }
    }

    private fun registerAcl() {
        val receiver = BluetoothAclReceiver { connected, name, address ->
            val phrase = if (connected) "$name подключены" else "$name отключены"
            app.speechService.speakRussian(phrase)
            viewModelScope.launch {
                logLocal("Bluetooth", "ACL ${if (connected) "connected" else "disconnected"}: $name $address")
                if (connected) {
                    logLocal("HeadsetDiag", "ACL connected → reassert MediaSession")
                    HeadsetMonitorService.reassert(getApplication())
                }
                refreshStatusQuiet()
            }
        }
        aclReceiver = receiver
        runCatching {
            val context = getApplication<Application>()
            val filter = BluetoothAclReceiver.createIntentFilter()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }

    private fun unregisterAcl() {
        val receiver = aclReceiver ?: return
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        aclReceiver = null
    }

    private fun registerAdapterState() {
        val receiver = BluetoothAdapterStateReceiver { _, label ->
            viewModelScope.launch {
                logLocal("BluetoothRadio", "state=$label")
            }
        }
        adapterStateReceiver = receiver
        runCatching {
            val context = getApplication<Application>()
            val filter = BluetoothAdapterStateReceiver.createIntentFilter()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }

    private fun unregisterAdapterState() {
        val receiver = adapterStateReceiver ?: return
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        adapterStateReceiver = null
    }

    private fun refreshStatusQuiet() {
        val snap = app.bluetoothInventory.snapshot()
        val previous = _uiState.value.snapshot?.activeSummary
        _uiState.update { it.copy(snapshot = snap) }
        if (previous != null && previous != snap.activeSummary) {
            viewModelScope.launch {
                logLocal("Bluetooth", "Status: ${snap.activeSummary}")
            }
        }
    }

    private fun logLocal(category: String, message: String) {
        viewModelScope.launch {
            app.localLogRepository.logLocal(category, message)
        }
    }

    override fun onCleared() {
        app.headsetButtonNotifier.onButton = null
        app.headsetButtonNotifier.onActiveSessionsChanged = null
        if (!stopInProgress) {
            HeadsetMonitorService.stop(getApplication())
        }
        unregisterAcl()
        unregisterAdapterState()
        super.onCleared()
    }

    companion object {
        private const val STATUS_POLL_MS = 2000L
    }
}
