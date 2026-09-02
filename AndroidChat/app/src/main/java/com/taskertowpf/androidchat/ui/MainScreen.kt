package com.taskertowpf.androidchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeOff
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import com.taskertowpf.androidchat.R
import com.taskertowpf.androidchat.MainViewModel
import com.taskertowpf.androidchat.MainUiState
import com.taskertowpf.androidchat.MessageDisplayFormatter
import com.taskertowpf.androidchat.data.AppSettings
import com.taskertowpf.androidchat.data.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val hrtState by viewModel.hrt.state.collectAsState()
    val isMainChat = !state.showSettings && !state.showFiles && !state.showLogs &&
        !state.showTests && !state.showLesson
    val isHrtTab = isMainChat && state.mainTab == 1
    val fabBottomPadding = if (isMainChat && !isHrtTab) 88.dp else 24.dp

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.showSettings -> {
                SettingsScreen(
                    settings = state.settings,
                    onSettingsChange = viewModel::updateSettings,
                    onSave = viewModel::saveSettings,
                    onCancel = viewModel::closeSettings,
                    onOpenTests = viewModel::openTests,
                )
            }
            state.showLesson -> {
                LessonGeneratorScreen(
                    phase = state.lessonPhase,
                    statusText = state.lessonStatus,
                    topicDraft = state.lessonTopic,
                    confirmQuestion = state.lessonConfirmQuestion,
                    screens = state.lessonScreens,
                    screenIndex = state.lessonScreenIndex,
                    listening = state.lessonListening,
                    isBusy = state.isBusy,
                    fullscreen = state.lessonFullscreen,
                    enFontSp = state.lessonEnFontSp,
                    ruFontSp = state.lessonRuFontSp,
                    savedLessons = state.lessonSavedList,
                    onPlayAction = viewModel::onLessonBtPlay,
                    onClarifyTopic = viewModel::onLessonClarifyTopic,
                    onPrev = viewModel::lessonPrevPage,
                    onNext = viewModel::lessonNextPage,
                    onRestart = viewModel::restartLessonSession,
                    onClose = viewModel::closeLesson,
                    onToggleFullscreen = viewModel::toggleLessonFullscreen,
                    onSelectSavedLesson = viewModel::selectSavedLesson,
                )
            }
            state.showTests -> {
                TestsScreen(
                    settings = state.settings,
                    voices = state.ttsVoices,
                    ttsStatusText = state.ttsVoicesStatus,
                    selectedTab = state.testsSelectedTab,
                    onSelectTab = viewModel::selectTestsTab,
                    sttListening = state.sttTestListening,
                    sttStatusText = state.sttTestStatus,
                    sttLastText = state.sttTestLastText,
                    sttEventLog = state.sttTestEventLog,
                    btPressCount = state.btPlayPressCount,
                    btLastEventLabel = state.btPlayLastLabel,
                    btLastEventAt = state.btPlayLastAt,
                    btEventLog = state.btPlayEventLog,
                    onRefreshTts = viewModel::refreshTtsVoices,
                    onInstallVoices = viewModel::installAdditionalTtsVoices,
                    onSelectVoice = viewModel::selectTtsVoice,
                    onPreviewVoice = viewModel::previewTtsVoice,
                    onStopTts = viewModel::stopTtsPreview,
                    onStartStt = viewModel::startSttTest,
                    onCancelStt = viewModel::cancelSttTest,
                    onClearSttLog = viewModel::clearSttTestLog,
                    onResetBt = viewModel::resetBtPlayCounter,
                    onSimulateBt = viewModel::simulateBtPlay,
                    onClose = viewModel::closeTests,
                )
            }
            state.showFiles -> {
                FileTransferScreen(
                    settings = state.settings,
                    outgoingFiles = state.backupFiles,
                    incomingFiles = state.incomingFiles,
                    pickedFileName = state.pickedOutgoingFileName,
                    statusText = state.fileTransferStatus.ifBlank { state.statusText },
                    fileReceiveLog = state.fileReceiveLog,
                    isBusy = state.isBusy,
                    onSettingsChange = viewModel::updateSettings,
                    onRefresh = viewModel::refreshFileLists,
                    onRetryDownload = viewModel::retryDownloadFromChat,
                    onSendSelected = viewModel::sendBackupFile,
                    onSendAll = viewModel::sendAllBackupFiles,
                    onPickOutgoingFile = viewModel::pickOutgoingFile,
                    onSendPickedFile = viewModel::sendPickedOutgoingFile,
                    onSaveFolder = viewModel::saveFileFolder,
                    onPickOutgoingFolder = viewModel::pickOutgoingFolder,
                    onPickIncomingFolder = viewModel::pickIncomingFolder,
                    onRequestStorage = viewModel::requestStoragePermissions,
                    onOpenFile = viewModel::openFile,
                    onDeleteFile = viewModel::deleteFile,
                    onClose = viewModel::closeFiles,
                )
            }
            state.showLogs -> {
                LogScreenHost(viewModel)
            }
            else -> {
                MainChatContent(viewModel = viewModel, state = state)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
                .navigationBarsPadding()
                .zIndex(10f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (!state.showGemini && !hrtState.screenshotVisible) {
                FloatingActionButton(onClick = viewModel::toggleGemini) {
                    Icon(Icons.Default.SmartToy, contentDescription = "OpenRouter помощник")
                }
            }
        }

        hrtState.screenshotBytes?.takeIf { hrtState.screenshotVisible }?.let { bytes ->
            HrtScreenshotOverlay(
                bytes = bytes,
                label = hrtState.screenshotLabel,
                onTap = viewModel.hrt::tapScreenshot,
                onDismiss = viewModel.hrt::dismissScreenshot,
            )
        }

        if (state.showGemini) {
            GeminiOverlayPanel(
                messages = state.geminiMessages,
                inputText = state.geminiInput,
                statusText = state.geminiStatus,
                isBusy = state.isBusy,
                onInputChange = viewModel::updateGeminiInput,
                onSend = viewModel::sendGeminiMessage,
                onClear = viewModel::clearGeminiChat,
                onDismiss = viewModel::closeGemini,
            )
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainChatContent(
    viewModel: MainViewModel,
    state: MainUiState,
) {
    val hrtState by viewModel.hrt.state.collectAsState()
    val listState = rememberLazyListState()
    var inputFieldValue by remember {
        mutableStateOf(TextFieldValue(state.inputText))
    }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var recipientMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun createCameraUri(): Uri {
        val file = File(context.cacheDir, "photo_capture_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        android.util.Log.i(
            "CameraPhoto",
            "TakePicture success=$success uri=$uri",
        )
        if (success && uri != null) {
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            android.util.Log.i("CameraPhoto", "Captured file size=$size")
            if (size <= 0L) {
                viewModel.onCameraCaptureFailed("Камера не сохранила фото (пустой файл)")
                runCatching { context.contentResolver.delete(uri, null, null) }
            } else {
                viewModel.sendCameraPhoto(uri)
            }
        } else {
            viewModel.onCameraCaptureFailed(
                if (!success) "Съёмка отменена или камера не записала файл" else "Нет URI фото",
            )
            if (uri != null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createCameraUri()
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.chat_camera_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun launchCameraForSend() {
        if (state.isBusy) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = createCameraUri()
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.inputText) {
        if (state.inputText != inputFieldValue.text) {
            inputFieldValue = TextFieldValue(
                text = state.inputText,
                selection = TextRange(state.inputText.length),
            )
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text(stringResource(R.string.clear_chat_confirm_title)) },
            text = { Text(stringResource(R.string.clear_chat_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearChatConfirm = false
                        viewModel.clearChat()
                    },
                ) {
                    Text(stringResource(R.string.clear_chat_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) {
                    Text(stringResource(R.string.clear_chat_confirm_no))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Column {
                        Text("AndroidChat")
                        Text(
                            text = state.connectionStatus,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = state.realtimeStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    if (state.mainTab == 0) {
                        IconButton(onClick = viewModel::stopChatTts) {
                            Icon(
                                Icons.Default.VolumeOff,
                                contentDescription = stringResource(R.string.chat_stop_tts),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.menu_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_refresh)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.refreshMessages()
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_clear_chat)) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showClearChatConfirm = true
                                },
                                enabled = !state.isBusy,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_hrt)) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.selectMainTab(1)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_files)) },
                                leadingIcon = {
                                    Icon(Icons.Default.AttachFile, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openFiles()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_logs)) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openLogs()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_tests)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Science, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openTests()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_lesson)) },
                                leadingIcon = {
                                    Icon(Icons.Default.MenuBook, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openLesson()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_stop)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.stopApp()
                                },
                                enabled = !state.isBusy,
                            )
                        }
                    }
                },
            )
            TabRow(selectedTabIndex = state.mainTab) {
                Tab(
                    selected = state.mainTab == 0,
                    onClick = { viewModel.selectMainTab(0) },
                    text = { Text(stringResource(R.string.main_tab_chat)) },
                )
                Tab(
                    selected = state.mainTab == 1,
                    onClick = { viewModel.selectMainTab(1) },
                    text = { Text(stringResource(R.string.main_tab_hrt)) },
                )
            }
            }
        },
        bottomBar = {
            if (state.mainTab != 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { launchCameraForSend() },
                    enabled = !state.isBusy,
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.chat_camera_ocr),
                    )
                }
                OutlinedTextField(
                    value = inputFieldValue,
                    onValueChange = { value ->
                        inputFieldValue = value
                        viewModel.updateInput(value.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.isShiftPressed) {
                                val selection = inputFieldValue.selection
                                val start = selection.min
                                val end = selection.max
                                val text = inputFieldValue.text
                                val newText = text.substring(0, start) + "\n" + text.substring(end)
                                val cursor = start + 1
                                inputFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(cursor),
                                )
                                viewModel.updateInput(newText)
                                return@onPreviewKeyEvent true
                            }
                            if (!state.isBusy && inputFieldValue.text.isNotBlank()) {
                                viewModel.sendMessage()
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!state.isBusy && inputFieldValue.text.isNotBlank()) {
                                viewModel.sendMessage()
                            }
                        },
                    ),
                )
                ChatRecipientDropdown(
                    settings = state.settings,
                    expanded = recipientMenuExpanded,
                    onExpandedChange = { recipientMenuExpanded = it },
                    onSelect = viewModel::selectRecipient,
                )
                IconButton(
                    onClick = viewModel::sendMessage,
                    enabled = !state.isBusy && inputFieldValue.text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
            }
        },
    ) { padding ->
        if (state.mainTab == 1) {
            HrtDashboard(
                state = hrtState,
                onRefresh = viewModel.hrt::sendRefresh,
                onScreenshot = viewModel.hrt::sendScreenshot,
                onRepeat = viewModel.hrt::sendRepeat,
                onPullSnapshot = { viewModel.hrt.pullSnapshot(includeScreenshot = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.statusText.isNotBlank()) {
                Text(
                    text = state.statusText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
            }
        }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val displayText = MessageDisplayFormatter.toDisplayText(message.content)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message.routeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = displayText,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onOpenTests: () -> Unit = {},
) {
    var enFontDraft by remember(settings.lessonEnFontSp) {
        mutableStateOf(formatFontSp(settings.lessonEnFontSp))
    }
    var ruFontDraft by remember(settings.lessonRuFontSp) {
        mutableStateOf(formatFontSp(settings.lessonRuFontSp))
    }

    fun commitFontSizes(): AppSettings {
        val en = parseFontSp(enFontDraft, settings.lessonEnFontSp)
        val ru = parseFontSp(ruFontDraft, settings.lessonRuFontSp)
        enFontDraft = formatFontSp(en)
        ruFontDraft = formatFontSp(ru)
        return settings.copy(lessonEnFontSp = en, lessonRuFontSp = ru)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Text("Настройки Supabase")

        OutlinedTextField(
            value = settings.supabaseUrl,
            onValueChange = { onSettingsChange(settings.copy(supabaseUrl = it)) },
            label = { Text("Supabase URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.supabaseAnonKey,
            onValueChange = { onSettingsChange(settings.copy(supabaseAnonKey = it)) },
            label = { Text("Anon Key") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.senderName,
            onValueChange = { onSettingsChange(settings.copy(senderName = it)) },
            label = { Text("sender_name") },
            modifier = Modifier.fillMaxWidth(),
        )

        RecipientSettingsSection(
            settings = settings,
            onSettingsChange = onSettingsChange,
        )

        OutlinedTextField(
            value = settings.incomingRecipientName,
            onValueChange = { onSettingsChange(settings.copy(incomingRecipientName = it)) },
            label = { Text("Принимать входящие для recipient_name") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Обычно «Android» — как шлёт WpfChat/Hermes") },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.enableIncomingTts,
                onCheckedChange = { onSettingsChange(settings.copy(enableIncomingTts = it)) },
            )
            Text(stringResource(R.string.settings_enable_incoming_tts))
        }

        OutlinedTextField(
            value = settings.ttsPauseSeconds.toString(),
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(2)
                val value = digits.toIntOrNull()?.coerceIn(0, 30) ?: 0
                onSettingsChange(settings.copy(ttsPauseSeconds = value))
            },
            label = { Text(stringResource(R.string.settings_tts_pause_seconds)) },
            supportingText = { Text(stringResource(R.string.settings_tts_pause_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = settings.enableIncomingTts,
        )

        Text(
            text = stringResource(R.string.settings_lesson_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        LessonFontSpField(
            label = stringResource(R.string.settings_lesson_en_font),
            draft = enFontDraft,
            onDraftChange = { enFontDraft = it },
            committedValue = settings.lessonEnFontSp,
            onCommit = { onSettingsChange(settings.copy(lessonEnFontSp = it)) },
        )
        LessonFontSpField(
            label = stringResource(R.string.settings_lesson_ru_font),
            draft = ruFontDraft,
            onDraftChange = { ruFontDraft = it },
            committedValue = settings.lessonRuFontSp,
            onCommit = { onSettingsChange(settings.copy(lessonRuFontSp = it)) },
        )
        OutlinedTextField(
            value = settings.lessonHeadsetDeviceName,
            onValueChange = { onSettingsChange(settings.copy(lessonHeadsetDeviceName = it)) },
            label = { Text(stringResource(R.string.settings_lesson_headset)) },
            supportingText = { Text(stringResource(R.string.settings_lesson_headset_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(
            text = stringResource(R.string.settings_tts_engine),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = settings.ttsEngine.equals("openai", ignoreCase = true),
                onClick = { onSettingsChange(settings.copy(ttsEngine = "openai")) },
                label = { Text(stringResource(R.string.settings_tts_engine_openai)) },
                enabled = settings.enableIncomingTts,
            )
            FilterChip(
                selected = settings.ttsEngine.equals("google", ignoreCase = true),
                onClick = { onSettingsChange(settings.copy(ttsEngine = "google")) },
                label = { Text(stringResource(R.string.settings_tts_engine_google)) },
                enabled = settings.enableIncomingTts,
            )
        }

        if (settings.ttsEngine.equals("openai", ignoreCase = true)) {
            OutlinedTextField(
                value = settings.openAiApiKey,
                onValueChange = { onSettingsChange(settings.copy(openAiApiKey = it)) },
                label = { Text(stringResource(R.string.settings_openai_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = settings.enableIncomingTts,
            )
            OutlinedTextField(
                value = settings.openAiTtsModel,
                onValueChange = { onSettingsChange(settings.copy(openAiTtsModel = it)) },
                label = { Text(stringResource(R.string.settings_openai_tts_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = settings.enableIncomingTts,
            )
        }

        OutlinedTextField(
            value = settings.voiceInputLocale,
            onValueChange = { onSettingsChange(settings.copy(voiceInputLocale = it)) },
            label = { Text(stringResource(R.string.settings_voice_input_locale)) },
            supportingText = { Text(stringResource(R.string.settings_voice_input_locale_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedButton(
            onClick = onOpenTests,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Science, contentDescription = null)
            Text(
                text = stringResource(R.string.settings_open_tests),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Text(
            text = "Engine: ${settings.ttsEngine}\n" +
                "EN voice: ${settings.ttsEnglishVoiceName.ifBlank { "(default)" }}\n" +
                "RU voice: ${settings.ttsRussianVoiceName.ifBlank { "(default)" }}",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.enableHeadsetToChat,
                onCheckedChange = { onSettingsChange(settings.copy(enableHeadsetToChat = it)) },
            )
            Text("Кнопки гарнитуры (кроме Play) → чат")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.enableSupabasePoll,
                onCheckedChange = { onSettingsChange(settings.copy(enableSupabasePoll = it)) },
            )
            Text(stringResource(R.string.settings_enable_supabase_poll))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.skipDuplicateLogsToSupabase,
                onCheckedChange = { onSettingsChange(settings.copy(skipDuplicateLogsToSupabase = it)) },
            )
            Text(stringResource(R.string.settings_skip_duplicate_logs))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.enableNativeHeadsetCapture,
                onCheckedChange = { onSettingsChange(settings.copy(enableNativeHeadsetCapture = it)) },
            )
            Text("Захват Play на гарнитуре (BT Play → STT / pause / next)")
        }

        OutlinedTextField(
            value = settings.fileOutgoingFolder,
            onValueChange = {
                if (settings.fileOutgoingTreeUri.isBlank()) {
                    onSettingsChange(settings.copy(fileOutgoingFolder = it))
                }
            },
            readOnly = settings.fileOutgoingTreeUri.isNotBlank(),
            label = { Text("Исходящие (Outcoming)") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        OutlinedTextField(
            value = settings.fileIncomingFolder,
            onValueChange = {
                if (settings.fileIncomingTreeUri.isBlank()) {
                    onSettingsChange(settings.copy(fileIncomingFolder = it))
                }
            },
            readOnly = settings.fileIncomingTreeUri.isNotBlank(),
            label = { Text("Входящие (Incoming)") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        OutlinedTextField(
            value = settings.openRouterApiKey,
            onValueChange = { onSettingsChange(settings.copy(openRouterApiKey = it)) },
            label = { Text("OpenRouter API key") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.openRouterModel,
            onValueChange = { onSettingsChange(settings.copy(openRouterModel = it)) },
            label = { Text("OpenRouter model") },
            supportingText = { Text("Напр. google/gemini-2.5-flash") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSettingsChange(commitFontSizes())
                onSave()
            }) { Text("Сохранить") }
            Button(onClick = onCancel) { Text("Отмена") }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRecipientDropdown(
    settings: AppSettings,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val recipients = settings.resolvedRecipientNames()
    val current = settings.recipientName.trim().ifBlank {
        recipients.firstOrNull().orEmpty()
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.widthIn(min = 96.dp, max = 148.dp),
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.chat_recipient_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            recipients.forEach { name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(name)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipientSettingsSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val recipients = settings.resolvedRecipientNames()
    var expanded by remember { mutableStateOf(false) }
    var newRecipient by remember { mutableStateOf("") }

    Text(stringResource(R.string.settings_recipients_title))

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = settings.recipientName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_current_recipient)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            recipients.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSettingsChange(settings.copy(recipientName = name))
                        expanded = false
                    },
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newRecipient,
            onValueChange = { newRecipient = it },
            label = { Text(stringResource(R.string.settings_new_recipient)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedButton(
            onClick = {
                val name = newRecipient.trim()
                if (name.isEmpty()) {
                    return@OutlinedButton
                }
                val updated = (recipients + name).distinct()
                onSettingsChange(
                    settings.copy(
                        recipientNames = updated,
                        recipientName = name,
                    ),
                )
                newRecipient = ""
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.settings_add_recipient))
        }
    }

    recipients.forEach { name ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = {
                    if (recipients.size <= 1) {
                        return@IconButton
                    }
                    val updated = recipients.filterNot { it.equals(name, ignoreCase = true) }
                    val nextCurrent = if (settings.recipientName.equals(name, ignoreCase = true)) {
                        updated.first()
                    } else {
                        settings.recipientName
                    }
                    onSettingsChange(
                        settings.copy(
                            recipientNames = updated,
                            recipientName = nextCurrent,
                        ),
                    )
                },
                enabled = recipients.size > 1,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.settings_remove_recipient),
                )
            }
        }
    }
}

private val FONT_SP_INPUT_REGEX = Regex("^[0-9]*[.,]?[0-9]*$")

@Composable
private fun LessonFontSpField(
    label: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    committedValue: Float,
    onCommit: (Float) -> Unit,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            if (raw.isEmpty() || raw.matches(FONT_SP_INPUT_REGEX)) {
                onDraftChange(raw)
            }
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    val parsed = parseFontSp(draft, committedValue)
                    onDraftChange(formatFontSp(parsed))
                    if (parsed != committedValue) onCommit(parsed)
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            autoCorrectEnabled = false,
        ),
    )
}

private fun formatFontSp(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

private fun parseFontSp(raw: String, fallback: Float): Float {
    val cleaned = raw.replace(',', '.').trim()
    if (cleaned.isEmpty()) return fallback
    return cleaned.toFloatOrNull()?.coerceIn(10f, 96f) ?: fallback
}
