package com.taskertowpf.androidchatbttestv1.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taskertowpf.androidchatbttestv1.InterceptMonitorViewModel
import com.taskertowpf.androidchatbttestv1.data.ActiveMediaSessionRow
import com.taskertowpf.androidchatbttestv1.data.ActiveSessionHistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptMonitorScreen(
    viewModel: InterceptMonitorViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Монитор перехвата BT") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
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
                    text = state.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::openNotificationAccessSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Notification Access")
                    }
                    OutlinedButton(
                        onClick = viewModel::reassertSelf,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reassert")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Первое приложение в списке получает BT Play/Pause. " +
                    "Если AndroidChatCopy запущен — он часто перехватывает кнопку, " +
                    "даже когда AndroidChatBtTest стоит выше в списке (см. логи).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryCard(
                isOwnTop = state.isOwnTop,
                topPackage = state.topPackage,
                sessionCount = state.sessions.size,
            )
            if (state.notificationAccessHint.isNotBlank()) {
                Text(
                    text = state.notificationAccessHint,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Активные MediaSession", style = MaterialTheme.typography.titleMedium)
            if (state.sessions.isEmpty()) {
                Text(
                    text = "Нет данных — включите Notification Access и нажмите Обновить",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                state.sessions.forEach { row ->
                    SessionCard(
                        row = row,
                        onOpenAppSettings = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${row.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                    )
                }
            }
            Text("История смены лидера", style = MaterialTheme.typography.titleMedium)
            if (state.history.isEmpty()) {
                Text(
                    text = "Пока без смен",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.history.forEach { entry ->
                    HistoryLine(entry)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryCard(
    isOwnTop: Boolean,
    topPackage: String,
    sessionCount: Int,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isOwnTop) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (isOwnTop) {
                    "✓ AndroidChatBtTest получает кнопку"
                } else {
                    "✗ Кнопку получает другое приложение"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Лидер: ${topPackage.ifBlank { "—" }}")
            Text("Всего сессий: $sessionCount", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SessionCard(
    row: ActiveMediaSessionRow,
    onOpenAppSettings: () -> Unit,
) {
    val bg = when {
        row.receivesButton && row.isSelf -> MaterialTheme.colorScheme.primaryContainer
        row.receivesButton -> MaterialTheme.colorScheme.errorContainer
        row.isSelf -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "#${row.rank} ${row.appLabel}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = row.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text("Playback: ${row.playbackState}")
            if (row.receivesButton) {
                Text(
                    text = "← получает BT-кнопку сейчас",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (row.competitorNote != null) {
                Text(
                    text = row.competitorNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!row.isSelf) {
                OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Настройки приложения (Force Stop)")
                }
            }
        }
    }
}

@Composable
private fun HistoryLine(entry: ActiveSessionHistoryEntry) {
    val time = InterceptMonitorViewModel.formatTime(entry.atMillis)
    Text(
        text = "[$time] топ=${entry.topPackage}\n  ${entry.summary}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(8.dp),
    )
}
