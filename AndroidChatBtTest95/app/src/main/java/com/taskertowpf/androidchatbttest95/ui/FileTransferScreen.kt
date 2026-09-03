package com.taskertowpf.androidchatbttest95.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taskertowpf.androidchatbttest95.data.AppSettings
import com.taskertowpf.androidchatbttest95.data.LocalFileItem

private enum class FileManagerTab {
    Outgoing,
    Incoming,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(
    settings: AppSettings,
    outgoingFiles: List<LocalFileItem>,
    incomingFiles: List<LocalFileItem>,
    pickedFileName: String,
    statusText: String,
    fileReceiveLog: List<String>,
    isBusy: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    onRefresh: () -> Unit,
    onRetryDownload: () -> Unit,
    onSendSelected: (LocalFileItem) -> Unit,
    onSendAll: () -> Unit,
    onPickOutgoingFile: () -> Unit,
    onSendPickedFile: () -> Unit,
    onSaveFolder: () -> Unit,
    onPickOutgoingFolder: () -> Unit,
    onPickIncomingFolder: () -> Unit,
    onRequestStorage: () -> Unit,
    onOpenFile: (LocalFileItem) -> Unit,
    onDeleteFile: (LocalFileItem, isOutgoing: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tab = if (selectedTab == 0) FileManagerTab.Outgoing else FileManagerTab.Incoming
    val activeFiles = if (tab == FileManagerTab.Outgoing) outgoingFiles else incomingFiles

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Файловый менеджер") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Outcoming — отправка. Incoming — приём из WpfChat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                FolderSection(
                    title = "Исходящие (Outcoming)",
                    folder = settings.fileOutgoingFolder,
                    isSaf = settings.fileOutgoingTreeUri.isNotBlank(),
                    readOnly = settings.fileOutgoingTreeUri.isNotBlank(),
                    onFolderChange = { onSettingsChange(settings.copy(fileOutgoingFolder = it)) },
                    onPickFolder = onPickOutgoingFolder,
                    isBusy = isBusy,
                )
            }

            item {
                FolderSection(
                    title = "Входящие (Incoming)",
                    folder = settings.fileIncomingFolder,
                    isSaf = settings.fileIncomingTreeUri.isNotBlank(),
                    readOnly = settings.fileIncomingTreeUri.isNotBlank(),
                    onFolderChange = { onSettingsChange(settings.copy(fileIncomingFolder = it)) },
                    onPickFolder = onPickIncomingFolder,
                    isBusy = isBusy,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestStorage, enabled = !isBusy) { Text("Разрешения") }
                    OutlinedButton(onClick = onSaveFolder, enabled = !isBusy) { Text("Сохранить") }
                    OutlinedButton(onClick = onRefresh, enabled = !isBusy) { Text("Обновить") }
                    if (tab == FileManagerTab.Incoming) {
                        OutlinedButton(onClick = onRetryDownload, enabled = !isBusy) { Text("Из чата") }
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = tab == FileManagerTab.Outgoing,
                        onClick = { selectedTab = 0 },
                        text = { Text("Исходящие (${outgoingFiles.size})") },
                    )
                    Tab(
                        selected = tab == FileManagerTab.Incoming,
                        onClick = { selectedTab = 1 },
                        text = { Text("Входящие (${incomingFiles.size})") },
                    )
                }
            }

            if (tab == FileManagerTab.Outgoing) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPickOutgoingFile,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Выбрать файл")
                        }
                        Button(
                            onClick = onSendAll,
                            enabled = !isBusy && outgoingFiles.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Отправить все")
                        }
                    }
                }

                item {
                    if (pickedFileName.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Выбран: $pickedFileName",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = onSendPickedFile, enabled = !isBusy) {
                                Text("Отправить")
                            }
                        }
                    }
                }
            }

            if (activeFiles.isEmpty()) {
                item {
                    Text(
                        text = if (tab == FileManagerTab.Outgoing) {
                            "В Outcoming пока нет файлов."
                        } else {
                            "В Incoming пока нет файлов."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(activeFiles, key = { it.fullPath }) { file ->
                    FileManagerRow(
                        file = file,
                        isBusy = isBusy,
                        isOutgoing = tab == FileManagerTab.Outgoing,
                        onSend = { onSendSelected(file) },
                        onOpenFile = { onOpenFile(file) },
                        onDelete = { onDeleteFile(file, tab == FileManagerTab.Outgoing) },
                    )
                }
            }

            if (statusText.isNotBlank()) {
                item {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (tab == FileManagerTab.Incoming && fileReceiveLog.isNotEmpty()) {
                item {
                    Text(
                        text = "Журнал загрузок",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        fileReceiveLog.takeLast(5).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileManagerRow(
    file: LocalFileItem,
    isBusy: Boolean,
    isOutgoing: Boolean,
    onSend: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = file.displayLabel, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenFile, enabled = !isBusy) {
                Text("Открыть")
            }
            OutlinedButton(onClick = onDelete, enabled = !isBusy) {
                Text("Удалить")
            }
            if (isOutgoing) {
                Button(onClick = onSend, enabled = !isBusy) {
                    Text("Отправить")
                }
            }
        }
    }
}

@Composable
private fun FolderSection(
    title: String,
    folder: String,
    isSaf: Boolean,
    readOnly: Boolean,
    onFolderChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    isBusy: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (isSaf) "$title (SAF)" else title,
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = folder,
            onValueChange = { if (!readOnly) onFolderChange(it) },
            readOnly = readOnly,
            label = { Text("Путь") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedButton(onClick = onPickFolder, enabled = !isBusy) {
            Text("Выбрать папку")
        }
    }
}
