package com.taskertowpf.androidchatbttestv1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskertowpf.androidchatbttestv1.AppBuildInfo
import com.taskertowpf.androidchatbttestv1.MainViewModel
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothDeviceRow
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothSnapshot
import com.taskertowpf.androidchatbttestv1.data.LocalLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    if (state.showSettings) {
        SettingsScreen(viewModel)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("AndroidChatBtTest ${AppBuildInfo.versionLabel}") },
                actions = {
                    IconButton(onClick = viewModel::openInterceptMonitor) {
                        Icon(Icons.Default.Monitor, contentDescription = "Монитор перехвата")
                    }
                    IconButton(onClick = viewModel::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                    IconButton(onClick = viewModel::stopApp) {
                        Icon(Icons.Default.Close, contentDescription = "Выход")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.statusText.ifBlank { "Готово" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.scanBluetooth(speakResult = true) },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Сканировать BT")
                    }
                    Button(
                        onClick = viewModel::sendLogsToSupabase,
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Логи → сервер")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Устройства") },
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("Play") },
                )
                Tab(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text("Логи") },
                )
            }
            when (state.selectedTab) {
                1 -> BtPlayTestSection(
                    pressCount = state.btPlayPressCount,
                    lastEventLabel = state.btPlayLastLabel,
                    lastEventAt = state.btPlayLastAt,
                    nativeCaptureOn = state.nativeCaptureOn,
                    eventLog = state.btPlayEventLog,
                    onSimulate = viewModel::simulatePlay,
                    onReset = viewModel::resetPlayCounter,
                    onReassert = { viewModel.reassertHeadsetCapture(speakCue = true) },
                    onDiagnose = viewModel::diagnoseHeadsetCapture,
                    onOpenNotificationAccessSettings = viewModel::openNotificationAccessSettings,
                    onOpenInterceptMonitor = viewModel::openInterceptMonitor,
                )
                2 -> LogsSection(
                    entries = state.logEntries,
                    onClear = viewModel::clearLogs,
                )
                else -> DevicesSection(snapshot = state.snapshot)
            }
        }
    }
}

@Composable
private fun DevicesSection(snapshot: BluetoothSnapshot?) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            ActiveStatusCard(snapshot?.activeSummary.orEmpty(), snapshot)
        }
        items(snapshot?.devices.orEmpty(), key = { it.address.ifBlank { it.name } }) { row ->
            DeviceCard(row)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BtPlayTestSection(
    pressCount: Int,
    lastEventLabel: String,
    lastEventAt: String,
    nativeCaptureOn: Boolean,
    eventLog: List<String>,
    onSimulate: () -> Unit,
    onReset: () -> Unit,
    onReassert: () -> Unit,
    onDiagnose: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenInterceptMonitor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Сначала включите Notification Access. Откройте «Монитор перехвата» " +
                "(иконка вверху) — там видно, кто забирает BT Play (AndroidChatCopy, YouTube…). " +
                "Watchdog в логах покажет, доходят ли media-button события.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (nativeCaptureOn) {
                "Native capture: ON (MediaSession)"
            } else {
                "Native capture: OFF"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (nativeCaptureOn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = pressCount.toString(),
            fontSize = 72.sp,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("нажатий BT Play", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Последнее: ${lastEventLabel.ifBlank { "—" }} · ${lastEventAt.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onOpenInterceptMonitor, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Monitor, contentDescription = null)
            Text("Монитор перехвата (отдельное окно)", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onReassert, modifier = Modifier.fillMaxWidth()) {
            Text("Reassert session")
        }
        OutlinedButton(onClick = onDiagnose, modifier = Modifier.fillMaxWidth()) {
            Text("Диагностика → логи")
        }
        OutlinedButton(
            onClick = onOpenNotificationAccessSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Включить доступ к уведомлениям (для точной диагностики)")
        }
        OutlinedButton(onClick = onSimulate, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("Симулировать Play", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Сбросить счётчик")
        }
        Text(
            text = "Журнал событий",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        if (eventLog.isEmpty()) {
            Text(
                text = "Пока нет событий",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            eventLog.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun LogsSection(
    entries: List<LocalLogEntry>,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("Очистить локальные логи")
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                LogLine(entry)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ActiveStatusCard(
    summary: String,
    snapshot: BluetoothSnapshot?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Активное устройство", style = MaterialTheme.typography.titleMedium)
            Text(summary.ifBlank { "Ещё не сканировали" })
            if (snapshot != null) {
                Text(
                    text = "Адаптер: ${snapshot.adapterName} · ${if (snapshot.adapterEnabled) "ON" else "OFF"} · proxy=${if (snapshot.profilesReady) "OK" else "wait"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Audio: mode=${snapshot.audioMode} A2DP=${snapshot.a2dpOn} SCO=${snapshot.scoOn}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (snapshot.outputDevices.isNotEmpty()) {
                    Text(
                        text = "Выходы: ${snapshot.outputDevices.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(row: BluetoothDeviceRow) {
    val bg = if (row.connected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall)
            Text(row.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                "класс=${row.majorClass} · ${row.roleLabel} · " +
                    if (row.connected) "подключено" else "не подключено",
            )
            Text(
                "HFP audio=${row.hfpAudio} · A2DP playing=${row.a2dpPlaying} · route=${row.audioRouted}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LogLine(entry: LocalLogEntry) {
    val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        .format(Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault()))
    Text(
        text = "[$time] [${entry.category}] ${entry.message} (${entry.status})",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Настройки Supabase") },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeSettings) {
                        Icon(Icons.Default.Close, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("content = [LOG:{category}] …; sender_name и recipient_name — ниже")
            OutlinedTextField(
                value = state.supabaseUrlDraft,
                onValueChange = viewModel::updateUrlDraft,
                label = { Text("Supabase URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.supabaseAnonDraft,
                onValueChange = viewModel::updateAnonDraft,
                label = { Text("Anon key") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.senderDraft,
                onValueChange = viewModel::updateSenderDraft,
                label = { Text("sender_name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.recipientDraft,
                onValueChange = viewModel::updateRecipientDraft,
                label = { Text("recipient_name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
        }
    }
}
