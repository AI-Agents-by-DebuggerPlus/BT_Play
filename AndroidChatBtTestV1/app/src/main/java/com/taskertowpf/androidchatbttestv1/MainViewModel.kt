package com.taskertowpf.androidchatbttestv1

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothAclReceiver
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothAdapterStateReceiver
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothSnapshot
import com.taskertowpf.androidchatbttestv1.data.AppSettings
import com.taskertowpf.androidchatbttestv1.data.LocalLogEntry
import com.taskertowpf.androidchatbttestv1.headset.HeadsetButtonNames
import com.taskertowpf.androidchatbttestv1.headset.HeadsetMonitorService
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
    val senderDraft: String = "",
    val recipientDraft: String = "",
    val nativeCaptureOn: Boolean = true,
    val btPlayPressCount: Int = 0,
    val btPlayLastLabel: String = "",
    val btPlayLastAt: String = "",
    val btPlayEventLog: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndroidChatBtTestV1App
    private var aclReceiver: BluetoothAclReceiver? = null
    private var adapterStateReceiver: BluetoothAdapterStateReceiver? = null
    private var stopInProgress = false
    private var exitAppCallback: (() -> Unit)? = null
    private var lastAclKey: String? = null
    private var lastAclAtMs: Long = 0L

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
        HeadsetMonitorService.start(getApplication())
        viewModelScope.launch {
            delay(800)
            val settings = app.settingsRepository.load()
            _uiState.update { it.copy(settings = settings) }
            app.speechService.speak("ready")
            logLocal("App", "Application started — ${AppBuildInfo.versionLabel}")
            HeadsetMonitorService.ensureRunning(getApplication())
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
        val tab = index.coerceIn(0, 2)
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == 1) {
            reassertHeadsetCapture(speakCue = false)
        }
    }

    fun openInterceptMonitor() {
        val intent = Intent(getApplication(), InterceptMonitorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun reassertHeadsetCapture(speakCue: Boolean = true) {
        HeadsetMonitorService.ensureRunning(getApplication())
        _uiState.update {
            it.copy(
                nativeCaptureOn = true,
                statusText = "MediaSession ensureRunning (baseline)",
            )
        }
        if (speakCue) {
            app.speechService.speak("Session ready")
        }
    }

    fun diagnoseHeadsetCapture() {
        HeadsetMonitorService.ensureRunning(getApplication())
        _uiState.update {
            it.copy(statusText = "Baseline: ActiveSessions diagnose отключён")
        }
    }

    fun openNotificationAccessSettings() {
        _uiState.update {
            it.copy(
                statusText = "Baseline V1: Notification Access не нужен для BT Play",
            )
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        if (granted) {
            app.bluetoothInventory.bindProfiles()
        }
        scanBluetooth(speakResult = false)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            HeadsetMonitorService.ensureRunning(getApplication())
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        if (granted) {
            HeadsetMonitorService.ensureRunning(getApplication())
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
            val connectedCount = snap.devices.count { it.connected }
            logLocal(
                "Bluetooth",
                "Scan: connected=$connectedCount / ${snap.devices.size}; ${snap.activeSummary}",
            )
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
        val settings = app.settingsRepository.load()
        _uiState.update {
            it.copy(
                settings = settings,
                showSettings = true,
                supabaseUrlDraft = settings.supabaseUrl,
                supabaseAnonDraft = settings.supabaseAnonKey,
                senderDraft = settings.senderName,
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

    fun updateSenderDraft(value: String) {
        _uiState.update { it.copy(senderDraft = value) }
    }

    fun updateRecipientDraft(value: String) {
        _uiState.update { it.copy(recipientDraft = value) }
    }

    fun saveSettings() {
        val next = _uiState.value.settings.copy(
            supabaseUrl = _uiState.value.supabaseUrlDraft.trim(),
            supabaseAnonKey = _uiState.value.supabaseAnonDraft.trim(),
            senderName = _uiState.value.senderDraft.trim().ifBlank { "AndroidChatBtTestV1" },
            recipientName = _uiState.value.recipientDraft.trim().ifBlank { "WpfChat" },
        )
        app.settingsRepository.save(next)
        _uiState.update { it.copy(settings = next, showSettings = false, statusText = "Настройки сохранены") }
        logLocal("Settings", "Saved; sender=${next.senderName} recipient=${next.recipientName}")
    }

    fun sendLogsToSupabase() {
        viewModelScope.launch {
            val entries = _uiState.value.logEntries
            if (entries.isEmpty()) {
                _uiState.update { it.copy(statusText = "Нет логов для отправки") }
                return@launch
            }
            val settings = _uiState.value.settings
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                _uiState.update {
                    it.copy(statusText = "Supabase не настроен — откройте Настройки (⚙)")
                }
                logLocal("Supabase", "Upload skipped: URL or anon key is empty")
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, statusText = "Отправка логов…") }
            var sent = 0
            var failed = 0
            var skipped = 0
            withContext(Dispatchers.IO) {
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
                        app.localLogRepository.markSent { it.id == entry.id }
                    } else {
                        failed++
                        logLocal("Supabase", "Send failed: ${result.exceptionOrNull()?.message}")
                    }
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
            _uiState.update { it.copy(isBusy = true, statusText = "Остановка AndroidChatBtTestV1…") }
            logLocal("App", "Stop requested")
            suspendCancellableCoroutine { continuation ->
                app.speechService.speakAndThen("will be stopped.") {
                    continuation.resume(Unit)
                }
            }
            app.headsetButtonNotifier.onButton = null
            app.headsetButtonNotifier.onActiveSessionRowsChanged = null
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
            val key = "${if (connected) "up" else "down"}:$address"
            val now = System.currentTimeMillis()
            if (key == lastAclKey && now - lastAclAtMs < ACL_DEBOUNCE_MS) {
                return@BluetoothAclReceiver
            }
            lastAclKey = key
            lastAclAtMs = now

            val phrase = if (connected) {
                "$name подключены"
            } else {
                "$name отключены"
            }
            app.speechService.speakRussian(phrase)
            viewModelScope.launch {
                logLocal(
                    "Bluetooth",
                    "ACL ${if (connected) "connected" else "disconnected"}: $name",
                )
                if (connected) {
                    HeadsetMonitorService.ensureRunning(getApplication())
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
        val receiver = BluetoothAdapterStateReceiver { _, _ ->
            // Adapter flips are noisy; UI poll already refreshes device list.
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
        _uiState.update { it.copy(snapshot = snap) }
    }

    private fun logLocal(category: String, message: String) {
        viewModelScope.launch {
            app.localLogRepository.logLocal(category, message)
        }
    }

    override fun onCleared() {
        app.headsetButtonNotifier.onButton = null
        app.headsetButtonNotifier.onActiveSessionRowsChanged = null
        if (!stopInProgress) {
            HeadsetMonitorService.stop(getApplication())
        }
        unregisterAcl()
        unregisterAdapterState()
        super.onCleared()
    }

    companion object {
        private const val STATUS_POLL_MS = 2000L
        private const val ACL_DEBOUNCE_MS = 2500L
    }
}
