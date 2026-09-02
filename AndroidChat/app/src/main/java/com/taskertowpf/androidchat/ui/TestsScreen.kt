package com.taskertowpf.androidchat.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskertowpf.androidchat.R
import com.taskertowpf.androidchat.TtsVoiceInfo
import com.taskertowpf.androidchat.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsScreen(
    settings: AppSettings,
    voices: List<TtsVoiceInfo>,
    ttsStatusText: String,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    sttListening: Boolean,
    sttStatusText: String,
    sttLastText: String,
    sttEventLog: List<String>,
    btPressCount: Int,
    btLastEventLabel: String,
    btLastEventAt: String,
    btEventLog: List<String>,
    onRefreshTts: () -> Unit,
    onInstallVoices: () -> Unit,
    onSelectVoice: (TtsVoiceInfo) -> Unit,
    onPreviewVoice: (TtsVoiceInfo) -> Unit,
    onStopTts: () -> Unit,
    onStartStt: () -> Unit,
    onCancelStt: () -> Unit,
    onClearSttLog: () -> Unit,
    onResetBt: () -> Unit,
    onSimulateBt: () -> Unit,
    onClose: () -> Unit,
) {
    val tabs = listOf(
        stringResource(R.string.tests_tab_tts),
        stringResource(R.string.tests_tab_stt),
        stringResource(R.string.tests_tab_bt_play),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(stringResource(R.string.tests_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    when (selectedTab) {
                        0 -> {
                            IconButton(onClick = onStopTts) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.voice_test_stop))
                            }
                            IconButton(onClick = onRefreshTts) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.voice_test_refresh))
                            }
                        }
                        1 -> {
                            IconButton(onClick = onClearSttLog) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.stt_test_clear))
                            }
                        }
                        2 -> {
                            IconButton(onClick = onResetBt) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.bt_play_test_reset))
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { onSelectTab(index) },
                        text = { Text(title) },
                    )
                }
            }
            when (selectedTab) {
                0 -> TtsTestSection(
                    settings = settings,
                    voices = voices,
                    statusText = ttsStatusText,
                    onInstallVoices = onInstallVoices,
                    onSelect = onSelectVoice,
                    onPreview = onPreviewVoice,
                )
                1 -> SttTestSection(
                    locale = settings.voiceInputLocale.ifBlank { "ru-RU" },
                    listening = sttListening,
                    statusText = sttStatusText,
                    lastText = sttLastText,
                    eventLog = sttEventLog,
                    onStart = onStartStt,
                    onCancel = onCancelStt,
                )
                else -> BtPlayTestSection(
                    pressCount = btPressCount,
                    lastEventLabel = btLastEventLabel,
                    lastEventAt = btLastEventAt,
                    nativeCaptureOn = settings.enableNativeHeadsetCapture,
                    eventLog = btEventLog,
                    onSimulate = onSimulateBt,
                )
            }
        }
    }
}

@Composable
private fun TtsTestSection(
    settings: AppSettings,
    voices: List<TtsVoiceInfo>,
    statusText: String,
    onInstallVoices: () -> Unit,
    onSelect: (TtsVoiceInfo) -> Unit,
    onPreview: (TtsVoiceInfo) -> Unit,
) {
    val useOpenAi = settings.ttsEngine.equals("openai", ignoreCase = true)
    val enVoices = voices.filter { it.language == "en" }
    val ruVoices = voices.filter { it.language == "ru" }
    val selectedEn = enVoices.firstOrNull { it.name == settings.ttsEnglishVoiceName }
    val selectedRu = ruVoices.firstOrNull { it.name == settings.ttsRussianVoiceName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (useOpenAi) {
                stringResource(R.string.voice_test_hint_openai)
            } else {
                stringResource(R.string.voice_test_hint)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!useOpenAi) {
            Text(
                text = stringResource(R.string.voice_test_install_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onInstallVoices,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_test_install))
            }
        }

        TestsVoiceDropdown(
            label = stringResource(R.string.voice_test_section_en),
            voices = enVoices,
            selected = selectedEn,
            emptyLabel = stringResource(R.string.voice_test_system_default),
            onSelect = onSelect,
            onPreview = onPreview,
        )
        TestsVoiceDropdown(
            label = stringResource(R.string.voice_test_section_ru),
            voices = ruVoices,
            selected = selectedRu,
            emptyLabel = stringResource(R.string.voice_test_system_default),
            onSelect = onSelect,
            onPreview = onPreview,
        )
    }
}

@Composable
private fun SttTestSection(
    locale: String,
    listening: Boolean,
    statusText: String,
    lastText: String,
    eventLog: List<String>,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stt_test_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.stt_test_locale, locale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = statusText.ifBlank { stringResource(R.string.stt_test_idle) },
            style = MaterialTheme.typography.titleMedium,
            color = if (listening) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )
        Text(
            text = lastText.ifBlank { "—" },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onStart,
                enabled = !listening,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text(
                    text = stringResource(R.string.stt_test_start),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = listening,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.MicOff, contentDescription = null)
                Text(
                    text = stringResource(R.string.stt_test_cancel),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.stt_test_log_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (eventLog.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stt_test_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(eventLog) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.bt_play_test_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (nativeCaptureOn) {
                stringResource(R.string.bt_play_test_capture_on)
            } else {
                stringResource(R.string.bt_play_test_capture_off)
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
        Text(
            text = stringResource(R.string.bt_play_test_count_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.bt_play_test_last_event,
                lastEventLabel.ifBlank { "—" },
                lastEventAt.ifBlank { "—" },
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onSimulate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(
                text = stringResource(R.string.bt_play_test_simulate),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.bt_play_test_log_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (eventLog.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.bt_play_test_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(eventLog) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestsVoiceDropdown(
    label: String,
    voices: List<TtsVoiceInfo>,
    selected: TtsVoiceInfo?,
    emptyLabel: String,
    onSelect: (TtsVoiceInfo) -> Unit,
    onPreview: (TtsVoiceInfo) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val fieldValue = selected?.displayLabel ?: emptyLabel

    Text(text = label, style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (voices.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(emptyLabel) },
                        onClick = { expanded = false },
                    )
                } else {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.displayLabel) },
                            onClick = {
                                onSelect(voice)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        IconButton(
            onClick = { selected?.let(onPreview) },
            enabled = selected != null,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.voice_test_play))
        }
    }
}
