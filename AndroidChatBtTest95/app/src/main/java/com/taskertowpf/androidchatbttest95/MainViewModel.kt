package com.taskertowpf.androidchatbttest95

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskertowpf.androidchatbttest95.data.AppSettings
import com.taskertowpf.androidchatbttest95.data.ChatMessage
import com.taskertowpf.androidchatbttest95.data.FileManagerHelper
import com.taskertowpf.androidchatbttest95.data.AssistantChatTurn
import com.taskertowpf.androidchatbttest95.data.LocalLogEntry
import com.taskertowpf.androidchatbttest95.data.FileFolderKind
import com.taskertowpf.androidchatbttest95.data.FileMessageFormat
import com.taskertowpf.androidchatbttest95.bridge.HeadsetPlayHandler
import com.taskertowpf.androidchatbttest95.data.OpenAiTtsClient
import com.taskertowpf.androidchatbttest95.headset.HeadsetConnectionConstants
import com.taskertowpf.androidchatbttest95.headset.HeadsetConnectionMonitor
import com.taskertowpf.androidchatbttest95.headset.HeadsetMonitorService
import com.taskertowpf.androidchatbttest95.headset.LessonHeadsetGuard
import com.taskertowpf.androidchatbttest95.lesson.LessonGeneratorPrompt
import com.taskertowpf.androidchatbttest95.lesson.LessonLayout
import com.taskertowpf.androidchatbttest95.lesson.LessonMarkdownParser
import com.taskertowpf.androidchatbttest95.lesson.LessonPager
import com.taskertowpf.androidchatbttest95.lesson.LessonScreen as LessonPage
import com.taskertowpf.androidchatbttest95.lesson.LessonSessionPhase
import com.taskertowpf.androidchatbttest95.lesson.SavedLessonMeta
import com.taskertowpf.androidchatbttest95.hrt.HrtController
import com.taskertowpf.androidchatbttest95.voice.VoiceInputResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val connectionStatus: String = "Не подключено",
    val realtimeStatus: String = "WebSocket: отключено",
    val statusText: String = "Готово",
    val isBusy: Boolean = false,
    val showSettings: Boolean = false,
    val showLogs: Boolean = false,
    val showTests: Boolean = false,
    /** 0=TTS, 1=STT, 2=BT Play */
    val testsSelectedTab: Int = 0,
    val showLesson: Boolean = false,
    val lessonPhase: LessonSessionPhase = LessonSessionPhase.Idle,
    val lessonStatus: String = "",
    val lessonTopic: String = "",
    val lessonConfirmQuestion: String = "",
    val lessonScreens: List<LessonPage> = emptyList(),
    val lessonScreenIndex: Int = 0,
    val lessonListening: Boolean = false,
    val lessonFullscreen: Boolean = false,
    val lessonSavedList: List<SavedLessonMeta> = emptyList(),
    val lessonEnFontSp: Float = 18f,
    val lessonRuFontSp: Float = 13f,
    val btPlayPressCount: Int = 0,
    val btPlayLastLabel: String = "",
    val btPlayLastAt: String = "",
    val btPlayEventLog: List<String> = emptyList(),
    val sttTestListening: Boolean = false,
    val sttTestStatus: String = "",
    val sttTestLastText: String = "",
    val sttTestEventLog: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val logEntries: List<LocalLogEntry> = emptyList(),
    val ttsVoices: List<TtsVoiceInfo> = emptyList(),
    val ttsVoicesStatus: String = "",
    val inputText: String = "",
    val showFiles: Boolean = false,
    val backupFiles: List<com.taskertowpf.androidchatbttest95.data.LocalFileItem> = emptyList(),
    val incomingFiles: List<com.taskertowpf.androidchatbttest95.data.LocalFileItem> = emptyList(),
    val fileTransferStatus: String = "",
    val fileReceiveLog: List<String> = emptyList(),
    val showGemini: Boolean = false,
    val geminiMessages: List<AssistantChatTurn> = emptyList(),
    val geminiInput: String = "",
    val geminiStatus: String = "",
    val pickedOutgoingFileUri: String = "",
    val pickedOutgoingFileName: String = "",
    /** 0 = чат, 1 = HRT (мобильный Remote Terminal). */
    val mainTab: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndroidChatApp

    private var launchFolderPicker: (() -> Unit)? = null
    private var launchOutgoingFilePicker: ((String?) -> Unit)? = null
    private var requestReadStorage: (() -> Unit)? = null
    private var requestAllFilesAccess: (() -> Unit)? = null
    private var launchInstallTtsData: (() -> Unit)? = null
    private var pendingFolderPick: FileFolderKind? = null
    private var exitAppCallback: (() -> Unit)? = null
    private var stopInProgress = false
    private var headsetGuardJob: Job? = null
    private val lessonHeadsetGuard = LessonHeadsetGuard(app)

    var onLessonLandscapeMode: ((Boolean) -> Unit)? = null
    var onLessonFullscreenMode: ((Boolean) -> Unit)? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val hrt = HrtController(
        chatRepository = app.chatRepository,
        scope = viewModelScope,
        logLocal = { category, message -> logLocal(category, message) },
        settings = { _uiState.value.settings },
    )

    init {
        app.chatRepository.onHrtMessage = { message -> hrt.onMessage(message) }
        app.chatRepository.onNewIncomingMessage = { message ->
            if (_uiState.value.showTests || _uiState.value.showLesson) {
                logLocal("TTS", "Пропуск озвучки — открыт экран тестов/урока")
            } else {
                val settings = _uiState.value.settings
                val plan = MessageDisplayFormatter.toSpeakPlan(
                    message.content,
                    pauseSeconds = settings.ttsPauseSeconds,
                )
                if (plan.isNotEmpty()) {
                    app.speechService.applyTtsSettings(settings)
                    val fromEnglishLearning = message.senderName.equals(
                        HeadsetPlayHandler.RECIPIENT_ENGLISH_LEARNING,
                        ignoreCase = true,
                    )
                    app.speechService.speakPlan(plan, markAsContent = fromEnglishLearning)
                    val summary = plan.joinToString(" | ") { step ->
                        when (step) {
                            is SpeakStep.Pause -> "pause:${step.millis}ms"
                            is SpeakStep.Speak -> "${step.lang}:${step.text.take(32)}"
                        }
                    }
                    logLocal("TTS", "Speak plan from ${message.senderName}: $summary")
                }
            }
        }
        viewModelScope.launch {
            val settings = app.settingsRepository.load()
            _uiState.update { it.copy(settings = settings) }
            syncActiveSettings(settings)
            app.chatRepository.messages.collect { messages ->
                _uiState.update { state -> state.copy(messages = messages) }
            }
        }
        viewModelScope.launch {
            app.chatRepository.realtimeStatus.collect { wsStatus ->
                _uiState.update { state -> state.copy(realtimeStatus = wsStatus) }
            }
        }
        viewModelScope.launch {
            app.chatRepository.fileTransferStatus.collect { status ->
                _uiState.update { state -> state.copy(fileTransferStatus = status) }
            }
        }
        viewModelScope.launch {
            app.chatRepository.fileReceiveLog.collect { log ->
                _uiState.update { state -> state.copy(fileReceiveLog = log) }
                if (log.isNotEmpty()) {
                    refreshFileLists()
                }
            }
        }
        viewModelScope.launch {
            app.localLogRepository.entries.collect { entries ->
                _uiState.update { state -> state.copy(logEntries = entries) }
            }
        }
        app.headsetButtonNotifier.onBtPlayDetected = { label ->
            recordBtPlayEvent(label)
        }
        app.headsetButtonNotifier.isolatedBtPlayHandler = {
            onIsolatedBtPlay()
        }
        viewModelScope.launch {
            delay(800)
            val settings = _uiState.value.settings
            app.speechService.applyTtsSettings(settings)
            val engine = if (app.speechService.usesOpenAi()) "OpenAI" else "Google"
            app.speechService.speak("is ready")
            logLocal("App", "Application started — ${AppBuildInfo.versionLabel}; TTS=$engine")
        }
        connect()
    }

    override fun onCleared() {
        app.headsetButtonNotifier.onBtPlayDetected = null
        app.headsetButtonNotifier.isolatedBtPlayHandler = null
        app.headsetButtonNotifier.btPlayTestIsolation = false
        if (!stopInProgress) {
            HeadsetMonitorService.stop(getApplication())
            HeadsetConnectionMonitor.stop(getApplication())
        }
        super.onCleared()
    }

    fun bindAppExit(onExit: () -> Unit) {
        exitAppCallback = onExit
    }

    fun bindLessonActivity(
        onLandscape: (Boolean) -> Unit,
        onFullscreen: (Boolean) -> Unit,
    ) {
        onLessonLandscapeMode = onLandscape
        onLessonFullscreenMode = onFullscreen
    }

    fun toggleLessonFullscreen() {
        val next = !_uiState.value.lessonFullscreen
        _uiState.update { it.copy(lessonFullscreen = next) }
        onLessonFullscreenMode?.invoke(next)
    }

    fun selectSavedLesson(topicKey: String) {
        if (topicKey.isBlank()) return
        viewModelScope.launch {
            val lesson = app.lessonStorageRepository.load(topicKey) ?: return@launch
            applyLessonFromMarkdown(lesson.topic, lesson.markdown, fromCache = true)
        }
    }

    fun refreshSavedLessonsList() {
        viewModelScope.launch {
            val list = app.lessonStorageRepository.listMeta()
            _uiState.update { it.copy(lessonSavedList = list) }
        }
    }

    fun stopApp() {
        if (stopInProgress) {
            return
        }
        stopInProgress = true
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Остановка AndroidChatBtTest95…") }
            logLocal("App", "Stop requested")
            suspendCancellableCoroutine { continuation ->
                app.speechService.speakAndThen("will be stopped.") {
                    continuation.resume(Unit)
                }
            }
            shutdownBackgroundWork()
            exitAppCallback?.invoke()
        }
    }

    private suspend fun shutdownBackgroundWork() {
        runCatching { app.chatRepository.disconnect() }
        runCatching { app.supabaseRepository.disconnect() }
        HeadsetMonitorService.stop(getApplication())
        HeadsetConnectionMonitor.stop(getApplication())
        app.shutdownForStop()
        app.speechService.shutdown()
    }


    fun openSettings() {
        leaveIsolatedScreens()
        _uiState.update {
            it.copy(showSettings = true, showLogs = false, showFiles = false, showTests = false, showLesson = false)
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun selectMainTab(index: Int) {
        val tab = index.coerceIn(0, 1)
        _uiState.update { it.copy(mainTab = tab) }
        if (tab == 1) {
            hrt.openAndBootstrap()
            logLocal("HRT", "Tab opened")
        }
    }

    fun openFiles() {
        leaveIsolatedScreens()
        ensureStorageAccess()
        refreshBackupFiles()
        _uiState.update {
            it.copy(showFiles = true, showSettings = false, showLogs = false, showTests = false, showLesson = false)
        }
    }

    fun toggleGemini() {
        _uiState.update { state ->
            val opening = !state.showGemini
            state.copy(
                showGemini = opening,
                geminiStatus = when {
                    opening && state.settings.resolvedOpenRouterApiKey().isBlank() ->
                        "Укажите OpenRouter API key в настройках"
                    !opening -> ""
                    else -> state.geminiStatus
                },
            )
        }
    }

    fun closeGemini() {
        _uiState.update { it.copy(showGemini = false) }
    }

    fun updateGeminiInput(text: String) {
        _uiState.update { it.copy(geminiInput = text) }
    }

    fun clearGeminiChat() {
        _uiState.update { it.copy(geminiMessages = emptyList(), geminiStatus = "Диалог очищен") }
        logLocal("OpenRouter", "Chat cleared")
    }

    fun sendGeminiMessage() {
        val text = _uiState.value.geminiInput.trim()
        if (text.isBlank()) {
            return
        }

        val settings = _uiState.value.settings
        if (settings.resolvedOpenRouterApiKey().isBlank()) {
            _uiState.update { it.copy(geminiStatus = "OpenRouter API key не задан") }
            return
        }

        viewModelScope.launch {
            val history = _uiState.value.geminiMessages
            _uiState.update {
                it.copy(
                    isBusy = true,
                    geminiInput = "",
                    geminiMessages = history + AssistantChatTurn(text, isUser = true),
                    geminiStatus = "OpenRouter думает…",
                )
            }

            val result = app.openRouterService.generateReply(
                apiKey = settings.resolvedOpenRouterApiKey(),
                model = settings.resolvedOpenRouterModel(),
                history = history,
                userMessage = text,
            )

            result.fold(
                onSuccess = { reply ->
                    logLocal("OpenRouter", "Reply: ${reply.take(120)}")
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            geminiMessages = state.geminiMessages + AssistantChatTurn(reply, isUser = false),
                            geminiStatus = "",
                        )
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Ошибка OpenRouter"
                    logLocal("OpenRouter", message)
                    _uiState.update {
                        it.copy(isBusy = false, geminiStatus = message)
                    }
                },
            )
        }
    }

    private fun syncActiveSettings(settings: AppSettings = _uiState.value.settings) {
        app.chatRepository.updateActiveSettings(settings)
        app.speechService.applyTtsSettings(settings)
    }

    fun bindStorageLaunchers(
        pickFolder: () -> Unit,
        pickOutgoingFile: (initialTreeUri: String?) -> Unit,
        requestReadStorage: () -> Unit,
        requestAllFilesAccess: () -> Unit,
        installTtsData: () -> Unit = {},
    ) {
        launchFolderPicker = pickFolder
        launchOutgoingFilePicker = pickOutgoingFile
        this.requestReadStorage = requestReadStorage
        this.requestAllFilesAccess = requestAllFilesAccess
        launchInstallTtsData = installTtsData
    }

    fun installAdditionalTtsVoices() {
        _uiState.update {
            it.copy(ttsVoicesStatus = "Открываю установку голосов Google TTS…")
        }
        logLocal("TTS", "Launch Google TTS voice install")
        val launcher = launchInstallTtsData
        if (launcher == null) {
            _uiState.update {
                it.copy(ttsVoicesStatus = "Не удалось открыть установщик голосов")
            }
            return
        }
        launcher.invoke()
    }

    fun onTtsInstallFinished() {
        _uiState.update {
            it.copy(ttsVoicesStatus = "Обновление списка голосов…")
        }
        logLocal("TTS", "TTS install activity finished — refreshing voices")
        refreshTtsVoices()
    }


    fun pickOutgoingFile() {
        val treeUri = _uiState.value.settings.fileOutgoingTreeUri.trim().ifBlank { null }
        launchOutgoingFilePicker?.invoke(treeUri)
    }

    fun onOutgoingFilePicked(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val path = uri.toString()
            val name = app.backupFolderStorage.resolveFileName(path).orEmpty()
            _uiState.update {
                it.copy(
                    pickedOutgoingFileUri = path,
                    pickedOutgoingFileName = name.ifBlank { path.substringAfterLast('/') },
                    fileTransferStatus = "Выбран: ${name.ifBlank { "файл" }}",
                )
            }
            logLocal("FileTransfer", "Outgoing file picked: $name")
        }
    }

    fun sendPickedOutgoingFile() {
        val uri = _uiState.value.pickedOutgoingFileUri
        if (uri.isBlank()) {
            _uiState.update { it.copy(fileTransferStatus = "Сначала выберите файл") }
            return
        }
        viewModelScope.launch {
            val name = _uiState.value.pickedOutgoingFileName.ifBlank { "файл" }
            _uiState.update { it.copy(isBusy = true, fileTransferStatus = "Отправка $name…") }
            val settings = _uiState.value.settings
            val result = app.chatRepository.sendFile(settings, uri)
            val status = result.fold(
                { "Отправлено: $name" },
                { ex ->
                    logLocal("FileTransfer", ex.message ?: "Ошибка отправки")
                    ex.message ?: "Ошибка отправки"
                },
            )
            _uiState.update {
                it.copy(
                    isBusy = false,
                    fileTransferStatus = status,
                    pickedOutgoingFileUri = if (result.isSuccess) "" else uri,
                    pickedOutgoingFileName = if (result.isSuccess) "" else it.pickedOutgoingFileName,
                )
            }
        }
    }

    fun pickOutgoingFolder() {
        pendingFolderPick = FileFolderKind.Outgoing
        launchFolderPicker?.invoke()
    }

    fun pickIncomingFolder() {
        pendingFolderPick = FileFolderKind.Incoming
        launchFolderPicker?.invoke()
    }

    fun requestStoragePermissions() {
        val settings = _uiState.value.settings
        when {
            settings.fileOutgoingTreeUri.isNotBlank() && settings.fileIncomingTreeUri.isNotBlank() -> {
                _uiState.update { it.copy(statusText = "Папки уже выбраны через системный диалог") }
            }
            app.backupFolderStorage.needsAllFilesAccess(settings) -> {
                requestAllFilesAccess?.invoke()
                logLocal("FileTransfer", "Opened all-files access settings")
            }
            app.backupFolderStorage.needsLegacyStoragePermission(settings) -> {
                requestReadStorage?.invoke()
            }
            else -> pickIncomingFolder()
        }
    }

    fun onBackupFolderPicked(uri: android.net.Uri) {
        viewModelScope.launch {
            val kind = pendingFolderPick ?: FileFolderKind.Incoming
            pendingFolderPick = null

            val treeUri = app.backupFolderStorage.persistTreeUri(uri)
            val displayName = app.backupFolderStorage.folderDisplayName(treeUri)
            val settings = when (kind) {
                FileFolderKind.Outgoing -> _uiState.value.settings.copy(
                    fileOutgoingTreeUri = treeUri,
                    fileOutgoingFolder = displayName.ifBlank { treeUri },
                )
                FileFolderKind.Incoming -> _uiState.value.settings.copy(
                    fileIncomingTreeUri = treeUri,
                    fileIncomingFolder = displayName.ifBlank { treeUri },
                )
            }
            app.settingsRepository.save(settings)
            syncActiveSettings(settings)
            val label = if (kind == FileFolderKind.Outgoing) "исходящих" else "входящих"
            _uiState.update {
                it.copy(
                    settings = settings,
                    statusText = "Папка $label выбрана: $displayName",
                )
            }
            logLocal("FileTransfer", "${kind.name} folder selected: $displayName")
            refreshBackupFiles()
            if (kind == FileFolderKind.Incoming) {
                app.chatRepository.retryDownloadIncomingFiles()
            }
        }
    }

    fun onReadStoragePermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                logLocal("FileTransfer", "Storage read permission granted")
                refreshBackupFiles()
                app.chatRepository.retryDownloadIncomingFiles()
                _uiState.update { it.copy(statusText = "Разрешение на чтение выдано") }
            } else {
                logLocal("FileTransfer", "Storage read permission denied")
                _uiState.update { it.copy(statusText = "Нет разрешения. Выберите папку входящих через системный диалог.") }
                pickIncomingFolder()
            }
        }
    }

    fun onStorageAccessMaybeGranted() {
        if (!_uiState.value.showFiles) {
            return
        }
        val settings = _uiState.value.settings
        if (app.backupFolderStorage.hasWriteAccess(settings, FileFolderKind.Incoming)) {
            refreshBackupFiles()
        }
    }

    private fun ensureStorageAccess() {
        val settings = _uiState.value.settings
        val incomingOk = app.backupFolderStorage.hasWriteAccess(settings, FileFolderKind.Incoming)
        val outgoingOk = app.backupFolderStorage.hasReadAccess(settings)
        if (incomingOk && outgoingOk) {
            return
        }
        viewModelScope.launch {
            logLocal("FileTransfer", "Storage access required — pick outgoing/incoming folders")
        }
        _uiState.update {
            it.copy(
                fileTransferStatus = "Выберите папки: исходящие (отправка) и входящие (приём из чата)",
            )
        }
    }

    fun retryDownloadFromChat() {
        viewModelScope.launch {
            syncActiveSettings()
            _uiState.update { it.copy(isBusy = true, statusText = "Повтор загрузки неудачных файлов…") }
            app.chatRepository.retryDownloadIncomingFiles()
            refreshBackupFiles()
            _uiState.update { it.copy(isBusy = false, statusText = "Повтор загрузки завершён") }
        }
    }

    fun closeFiles() {
        _uiState.update { it.copy(showFiles = false) }
    }

    fun refreshBackupFiles() {
        refreshFileLists()
    }

    fun refreshFileLists() {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                backupFiles = app.chatRepository.listBackupFiles(settings),
                incomingFiles = app.chatRepository.listIncomingFiles(settings),
            )
        }
    }

    fun deleteFile(file: com.taskertowpf.androidchatbttest95.data.LocalFileItem, isOutgoing: Boolean) {
        val result = FileManagerHelper.deleteFile(getApplication(), file.fullPath)
        result.fold(
            onSuccess = {
                logLocal("FileTransfer", "Deleted: ${file.fileName}")
                _uiState.update {
                    it.copy(
                        fileTransferStatus = "Удалено: ${file.fileName}",
                    )
                }
                refreshFileLists()
            },
            onFailure = { error ->
                val message = error.message ?: "Не удалось удалить"
                logLocal("FileTransfer", message)
                _uiState.update { it.copy(fileTransferStatus = message) }
            },
        )
    }

    fun openFile(file: com.taskertowpf.androidchatbttest95.data.LocalFileItem) {
        val result = FileManagerHelper.openFile(getApplication(), file.fullPath)
        result.fold(
            onSuccess = {
                logLocal("FileTransfer", "Opened: ${file.fileName}")
            },
            onFailure = { error ->
                val message = error.message ?: "Не удалось открыть"
                logLocal("FileTransfer", message)
                _uiState.update { it.copy(fileTransferStatus = message) }
            },
        )
    }

    fun saveFileFolder() {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            app.settingsRepository.save(settings)
            syncActiveSettings(settings)
            logLocal("FileTransfer", "File folders saved")
            refreshBackupFiles()
            _uiState.update { it.copy(statusText = "Папки файлов сохранены") }
        }
    }

    fun sendBackupFile(file: com.taskertowpf.androidchatbttest95.data.LocalFileItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Отправка ${file.fileName}…") }
            val settings = _uiState.value.settings
            val result = app.chatRepository.sendFile(settings, file.fullPath)
            val status = result.fold(
                { "Отправлено: ${file.fileName}" },
                { ex ->
                    logLocal("FileTransfer", ex.message ?: "Ошибка отправки")
                    ex.message ?: "Ошибка отправки"
                },
            )
            _uiState.update { it.copy(isBusy = false, statusText = status) }
        }
    }


    fun sendAllBackupFiles() {
        val files = _uiState.value.backupFiles
        if (files.isEmpty()) {
            _uiState.update { it.copy(statusText = "Нет файлов") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val settings = _uiState.value.settings
            var sent = 0
            var failed = 0
            for (file in files) {
                val result = app.chatRepository.sendFile(settings, file.fullPath)
                if (result.isSuccess) {
                    sent++
                } else {
                    failed++
                }
            }
            logLocal("FileTransfer", "Sent $sent, failed $failed")
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusText = "Отправлено: $sent, ошибок: $failed",
                )
            }
        }
    }

    fun openLogs() {
        leaveIsolatedScreens()
        _uiState.update {
            it.copy(showLogs = true, showSettings = false, showFiles = false, showTests = false, showLesson = false)
        }
    }

    fun closeLogs() {
        _uiState.update { it.copy(showLogs = false) }
    }

    private fun leaveIsolatedScreens() {
        if (!_uiState.value.showTests && !_uiState.value.showLesson) {
            return
        }
        app.voiceInputService.cancel()
        app.speechService.stopSpeaking()
        _uiState.update {
            it.copy(
                showTests = false,
                showLesson = false,
                sttTestListening = false,
                lessonListening = false,
                lessonPhase = LessonSessionPhase.Idle,
            )
        }
        exitHeadsetIsolation()
        refreshIsolatedBtPlayHandler()
    }

    fun openTests() {
        closeLessonInternal()
        enterHeadsetIsolation()
        _uiState.update {
            it.copy(
                showTests = true,
                showLesson = false,
                showSettings = false,
                showFiles = false,
                showLogs = false,
                testsSelectedTab = 1,
                ttsVoicesStatus = "Загрузка голосов…",
                sttTestListening = false,
                sttTestStatus = "",
            )
        }
        refreshIsolatedBtPlayHandler()
        refreshTtsVoices()
        logLocal("App", "Tests screen opened — isolation ON")
    }

    fun closeTests() {
        _uiState.update {
            it.copy(
                showTests = false,
                sttTestListening = false,
            )
        }
        exitHeadsetIsolation()
        refreshIsolatedBtPlayHandler()
        logLocal("App", "Tests screen closed — isolation OFF")
    }

    fun selectTestsTab(index: Int) {
        _uiState.update { it.copy(testsSelectedTab = index.coerceIn(0, 2)) }
        refreshIsolatedBtPlayHandler()
    }

    fun openLesson() {
        if (_uiState.value.showTests) {
            _uiState.update { it.copy(showTests = false, sttTestListening = false) }
        }
        enterHeadsetIsolation()
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                showLesson = true,
                showTests = false,
                showSettings = false,
                showFiles = false,
                showLogs = false,
                showGemini = false,
                lessonPhase = LessonSessionPhase.Idle,
                lessonStatus = "Нажмите Play и скажите тему урока",
                lessonTopic = "",
                lessonConfirmQuestion = "",
                lessonScreens = emptyList(),
                lessonScreenIndex = 0,
                lessonListening = false,
                lessonFullscreen = false,
                lessonEnFontSp = settings.lessonEnFontSp,
                lessonRuFontSp = settings.lessonRuFontSp,
            )
        }
        onLessonLandscapeMode?.invoke(true)
        refreshIsolatedBtPlayHandler()
        refreshSavedLessonsList()
        startLessonHeadsetGuard()
        logLocal("Lesson", "Lesson generator opened")
    }

    fun closeLesson() {
        if (_uiState.value.lessonFullscreen) {
            onLessonFullscreenMode?.invoke(false)
        }
        onLessonLandscapeMode?.invoke(false)
        stopLessonHeadsetGuard()
        closeLessonInternal()
        exitHeadsetIsolation()
        refreshIsolatedBtPlayHandler()
        logLocal("Lesson", "Lesson generator closed")
    }

    private fun closeLessonInternal() {
        app.voiceInputService.cancel()
        app.speechService.stopSpeaking()
        _uiState.update {
            it.copy(
                showLesson = false,
                lessonListening = false,
                lessonPhase = LessonSessionPhase.Idle,
            )
        }
    }

    fun restartLessonSession() {
        app.voiceInputService.cancel()
        app.speechService.stopSpeaking()
        _uiState.update {
            it.copy(
                lessonPhase = LessonSessionPhase.Idle,
                lessonStatus = "Нажмите Play и скажите тему урока",
                lessonTopic = "",
                lessonConfirmQuestion = "",
                lessonScreens = emptyList(),
                lessonScreenIndex = 0,
                lessonListening = false,
            )
        }
        logLocal("Lesson", "Session restarted")
    }

    fun lessonPrevPage() {
        val before = _uiState.value.lessonScreenIndex
        _uiState.update { state ->
            if (state.lessonScreens.isEmpty() || state.lessonScreenIndex <= 0) state
            else state.copy(lessonScreenIndex = state.lessonScreenIndex - 1)
        }
        if (_uiState.value.lessonScreenIndex != before) {
            speakCurrentLessonPage()
        }
    }

    fun lessonNextPage() {
        val before = _uiState.value.lessonScreenIndex
        _uiState.update { state ->
            if (state.lessonScreens.isEmpty() || state.lessonScreenIndex >= state.lessonScreens.lastIndex) state
            else state.copy(lessonScreenIndex = state.lessonScreenIndex + 1)
        }
        if (_uiState.value.lessonScreenIndex != before) {
            speakCurrentLessonPage()
        }
    }

    private fun speakCurrentLessonPage() {
        val state = _uiState.value
        val page = state.lessonScreens.getOrNull(state.lessonScreenIndex) ?: return
        val settings = state.settings
        val pauseMs = (settings.ttsPauseSeconds * 1000).toLong().coerceAtLeast(500L)
        val plan = page.cards.flatMap { card ->
            buildList {
                if (card.en.isNotBlank()) {
                    add(SpeakStep.Speak("en", card.en))
                    if (card.ru.isNotBlank()) {
                        add(SpeakStep.Pause(pauseMs))
                        add(SpeakStep.Speak("ru", card.ru))
                        add(SpeakStep.Pause(pauseMs))
                        add(SpeakStep.Speak("en", card.en))
                        add(SpeakStep.Pause(pauseMs))
                        add(SpeakStep.Speak("en", card.en))
                    }
                }
                add(SpeakStep.Pause(pauseMs))
            }
        }
        if (plan.isEmpty()) return
        app.speechService.applyTtsSettings(settings)
        app.speechService.speakPlan(plan, markAsContent = true)
    }

    private fun onIsolatedBtPlay() {
        val state = _uiState.value
        when {
            state.showLesson -> onLessonBtPlay()
            state.showTests && state.testsSelectedTab == 1 -> {
                if (state.sttTestListening) cancelSttTest() else startSttTest()
            }
        }
    }

    private fun refreshIsolatedBtPlayHandler() {
        val state = _uiState.value
        app.headsetButtonNotifier.isolatedBtPlayHandler = when {
            state.showLesson -> ({ onLessonBtPlay() })
            state.showTests && state.testsSelectedTab == 1 -> ({
                if (_uiState.value.sttTestListening) cancelSttTest() else startSttTest()
            })
            else -> null
        }
    }

    fun onLessonBtPlay() {
        val state = _uiState.value
        if (!state.showLesson) return
        when (state.lessonPhase) {
            LessonSessionPhase.Generating -> return
            LessonSessionPhase.ListeningRequest,
            LessonSessionPhase.ListeningConfirm,
            LessonSessionPhase.ListeningBrowseNav,
            -> {
                app.voiceInputService.cancel()
                return
            }
            LessonSessionPhase.AwaitingConfirm -> {
                if (app.speechService.isPlaybackActive()) {
                    _uiState.update {
                        it.copy(lessonStatus = "Дождитесь окончания вопроса")
                    }
                    return
                }
                startLessonConfirmListening()
            }
            LessonSessionPhase.Browsing -> {
                val speech = app.speechService
                when {
                    speech.isContentPlaybackPaused() -> {
                        _uiState.update { it.copy(lessonStatus = "Continue…") }
                        speech.speakCue("Continue") {
                            when (speech.handleContentBtPlay()) {
                                ContentBtPlayResult.RESUMED -> {
                                    _uiState.update { it.copy(lessonStatus = "Продолжение TTS") }
                                }
                                else -> Unit
                            }
                        }
                    }
                    speech.isContentPlaybackActive() -> {
                        when (speech.handleContentBtPlay()) {
                            ContentBtPlayResult.PAUSED -> {
                                _uiState.update { it.copy(lessonStatus = "Пауза TTS") }
                                speech.speakCue("Pause")
                            }
                            else -> Unit
                        }
                    }
                    else -> {
                        startLessonBrowseNavListening()
                    }
                }
            }
            LessonSessionPhase.Idle -> {
                startLessonTopicListening()
            }
        }
    }

    /** Уточнение темы голосом (кнопка на экране). */
    fun onLessonClarifyTopic() {
        if (!_uiState.value.showLesson) return
        when (_uiState.value.lessonPhase) {
            LessonSessionPhase.AwaitingConfirm, LessonSessionPhase.ListeningConfirm -> {
                startLessonTopicListening()
            }
            else -> Unit
        }
    }

    private fun confirmLessonGeneration() {
        val topic = _uiState.value.lessonTopic.trim()
        if (topic.isBlank()) {
            _uiState.update {
                it.copy(
                    lessonPhase = LessonSessionPhase.Idle,
                    lessonStatus = "Сначала назовите тему (Play → голос)",
                )
            }
            return
        }
        logLocal("Lesson", "Confirmed (empty voice): $topic")
        viewModelScope.launch {
            val cached = app.lessonStorageRepository.findByTopic(topic)
            if (cached != null) {
                logLocal("Lesson", "Using saved lesson: ${cached.topic}")
                applyLessonFromMarkdown(cached.topic, cached.markdown, fromCache = true)
                return@launch
            }
            generateLesson(topic)
        }
    }

    private fun onLessonFinished() {
        app.speechService.stopSpeaking()
        _uiState.update {
            it.copy(
                lessonPhase = LessonSessionPhase.Idle,
                lessonStatus = "Уроки закончились. Назовите следующую тему.",
                lessonScreens = emptyList(),
                lessonScreenIndex = 0,
                lessonConfirmQuestion = "",
                lessonTopic = "",
                lessonListening = false,
            )
        }
        logLocal("Lesson", "Lesson finished — waiting for next topic")
        val settings = _uiState.value.settings
        app.speechService.applyTtsSettings(settings)
        app.speechService.speakRussian("Уроки закончились. Назовите следующую тему.")
    }

    private fun buildLessonScreens(markdown: String): List<LessonPage> {
        val doc = LessonMarkdownParser.parse(markdown)
        val state = _uiState.value
        val cfg = LessonLayout.pagerConfig(
            enFontSp = state.lessonEnFontSp,
            ruFontSp = state.lessonRuFontSp,
            landscape = true,
        )
        return LessonPager.build(doc, cfg)
    }

    private fun applyLessonFromMarkdown(topic: String, markdown: String, fromCache: Boolean) {
        val screens = buildLessonScreens(markdown)
        if (screens.isEmpty()) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    lessonPhase = LessonSessionPhase.Idle,
                    lessonStatus = "Пустой урок. Повторите запрос.",
                )
            }
            logLocal("Lesson", "Empty screens from markdown")
            return
        }
        _uiState.update {
            it.copy(
                isBusy = false,
                lessonTopic = topic,
                lessonPhase = LessonSessionPhase.Browsing,
                lessonScreens = screens,
                lessonScreenIndex = 0,
                lessonConfirmQuestion = "",
                lessonStatus = if (fromCache) {
                    "Сохранённый урок: ${screens.size} экр."
                } else {
                    "Урок готов: ${screens.size} экр."
                },
            )
        }
        logLocal("Lesson", "Ready screens=${screens.size} cached=$fromCache")
        val settings = _uiState.value.settings
        app.speechService.applyTtsSettings(settings)
        app.speechService.speakRussianAndThen(if (fromCache) "Сохранённый урок" else "Урок готов") {
            speakCurrentLessonPage()
        }
    }

    private fun startLessonTopicListening() {
        startLessonVoiceListening(
            phase = LessonSessionPhase.ListeningRequest,
            status = "Слушаю тему…",
        )
    }

    private fun startLessonConfirmListening() {
        startLessonVoiceListening(
            phase = LessonSessionPhase.ListeningConfirm,
            status = "Пустое = подтвердить, иначе новая тема",
        )
    }

    private fun startLessonBrowseNavListening() {
        startLessonVoiceListening(
            phase = LessonSessionPhase.ListeningBrowseNav,
            status = "Пустое = Play → дальше",
            stopTts = false,
        )
    }

    private fun startLessonVoiceListening(
        phase: LessonSessionPhase,
        status: String,
        stopTts: Boolean = true,
    ) {
        val state = _uiState.value
        if (!state.showLesson) return
        viewModelScope.launch {
            ensureLessonHeadset()
            if (!_uiState.value.showLesson) return@launch
            if (stopTts) {
                app.speechService.stopSpeaking()
            }
            val locale = state.settings.voiceInputLocale.ifBlank { "ru-RU" }
            _uiState.update {
                it.copy(
                    lessonPhase = phase,
                    lessonListening = true,
                    lessonStatus = status,
                )
            }
            logLocal("Lesson", "STT start phase=$phase locale=$locale")
            app.voiceInputService.startListening(locale) { result ->
                handleLessonSpeechResult(result, phase)
            }
        }
    }

    private fun startLessonHeadsetGuard() {
        headsetGuardJob?.cancel()
        headsetGuardJob = viewModelScope.launch {
            ensureLessonHeadset()
            while (_uiState.value.showLesson) {
                delay(30_000)
                if (!_uiState.value.showLesson) break
                ensureLessonHeadset()
            }
        }
    }

    private fun stopLessonHeadsetGuard() {
        headsetGuardJob?.cancel()
        headsetGuardJob = null
    }

    private suspend fun ensureLessonHeadset() {
        val hint = _uiState.value.settings.lessonHeadsetDeviceName
            .ifBlank { HeadsetConnectionConstants.DEFAULT_DEVICE_NAME_HINT }
        lessonHeadsetGuard.ensureConnected(hint) { status ->
            logLocal("Headset", status)
            if (_uiState.value.showLesson && _uiState.value.lessonPhase != LessonSessionPhase.Browsing) {
                _uiState.update { it.copy(lessonStatus = status) }
            }
        }
    }

    private fun handleLessonSpeechResult(result: VoiceInputResult, phase: LessonSessionPhase) {
        when (result) {
            is VoiceInputResult.Success -> {
                val text = result.text.trim()
                _uiState.update { it.copy(lessonListening = false) }
                if (text.isBlank()) {
                    onLessonEmptyVoice(phase)
                } else if (phase == LessonSessionPhase.ListeningBrowseNav) {
                    // Непустой голос при навигации — не next, остаёмся на странице.
                    _uiState.update {
                        it.copy(
                            lessonPhase = LessonSessionPhase.Browsing,
                            lessonStatus = "Сказано: ${text.take(80)}",
                        )
                    }
                } else {
                    onLessonTopicHeard(text)
                }
            }
            is VoiceInputResult.Empty -> {
                _uiState.update { it.copy(lessonListening = false) }
                onLessonEmptyVoice(phase)
            }
            is VoiceInputResult.Cancelled -> {
                _uiState.update {
                    it.copy(
                        lessonListening = false,
                        lessonPhase = when {
                            it.lessonScreens.isNotEmpty() -> LessonSessionPhase.Browsing
                            it.lessonConfirmQuestion.isNotBlank() -> LessonSessionPhase.AwaitingConfirm
                            else -> LessonSessionPhase.Idle
                        },
                        lessonStatus = "Отменено. Нажмите Play.",
                    )
                }
            }
            is VoiceInputResult.Error -> {
                _uiState.update { it.copy(lessonListening = false) }
                val emptyConfirm = phase == LessonSessionPhase.ListeningConfirm &&
                    result.errorCode in EMPTY_CONFIRM_STT_ERRORS
                val emptyBrowse = phase == LessonSessionPhase.ListeningBrowseNav &&
                    result.errorCode in EMPTY_CONFIRM_STT_ERRORS
                when {
                    emptyConfirm -> {
                        logLocal("Lesson", "Empty confirm via STT: ${result.message}")
                        confirmLessonGeneration()
                    }
                    emptyBrowse -> {
                        logLocal("Lesson", "Empty browse via STT: ${result.message}")
                        onLessonEmptyBrowsePlay()
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                lessonPhase = when {
                                    it.lessonScreens.isNotEmpty() -> LessonSessionPhase.Browsing
                                    it.lessonConfirmQuestion.isNotBlank() -> LessonSessionPhase.AwaitingConfirm
                                    else -> LessonSessionPhase.Idle
                                },
                                lessonStatus = result.message,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onLessonEmptyVoice(phase: LessonSessionPhase) {
        when (phase) {
            LessonSessionPhase.ListeningConfirm -> confirmLessonGeneration()
            LessonSessionPhase.ListeningBrowseNav -> onLessonEmptyBrowsePlay()
            else -> {
                _uiState.update {
                    it.copy(
                        lessonPhase = LessonSessionPhase.Idle,
                        lessonStatus = "Не расслышал. Нажмите Play ещё раз.",
                    )
                }
            }
        }
    }

    /** Пустой голос в режиме просмотра → «Play» и следующая страница. */
    private fun onLessonEmptyBrowsePlay() {
        _uiState.update {
            it.copy(
                lessonPhase = LessonSessionPhase.Browsing,
                lessonStatus = "Play",
            )
        }
        app.speechService.speakCue("Play") {
            val state = _uiState.value
            if (!state.showLesson || state.lessonScreens.isEmpty()) return@speakCue
            val idx = state.lessonScreenIndex
            val last = state.lessonScreens.lastIndex
            if (idx < last) {
                lessonNextPage()
            } else {
                onLessonFinished()
            }
        }
    }

    private fun onLessonTopicHeard(text: String) {
        val question = "Вы хотите, чтобы я сгенерировал уроки по теме «$text»?"
        _uiState.update {
            it.copy(
                lessonTopic = text,
                lessonConfirmQuestion = question,
                lessonPhase = LessonSessionPhase.AwaitingConfirm,
                lessonStatus = "Слушаю подтверждение после вопроса…",
            )
        }
        logLocal("Lesson", "Topic: $text")
        viewModelScope.launch {
            // Дать аудиомаршруту переключиться после BT SCO (STT).
            delay(400)
            if (_uiState.value.lessonPhase != LessonSessionPhase.AwaitingConfirm) return@launch
            if (!_uiState.value.showLesson) return@launch
            app.speechService.applyTtsSettings(_uiState.value.settings)
            app.speechService.speakRussianAndThen(question) {
                if (_uiState.value.showLesson &&
                    _uiState.value.lessonPhase == LessonSessionPhase.AwaitingConfirm
                ) {
                    startLessonConfirmListening()
                }
            }
        }
    }

    private fun generateLesson(topic: String) {
        val settings = _uiState.value.settings
        if (settings.resolvedOpenRouterApiKey().isBlank()) {
            _uiState.update {
                it.copy(
                    lessonPhase = LessonSessionPhase.Idle,
                    lessonStatus = "Укажите OpenRouter API key в настройках",
                )
            }
            return
        }
        viewModelScope.launch {
            app.speechService.stopSpeaking()
            _uiState.update {
                it.copy(
                    lessonPhase = LessonSessionPhase.Generating,
                    lessonStatus = "Генерация урока…",
                    lessonConfirmQuestion = "",
                    isBusy = true,
                )
            }
            logLocal("Lesson", "Generating topic=$topic")
            val result = app.openRouterService.generateReply(
                apiKey = settings.resolvedOpenRouterApiKey(),
                model = settings.resolvedOpenRouterModel(),
                history = emptyList(),
                userMessage = LessonGeneratorPrompt.userRequest(topic),
                systemInstruction = LessonGeneratorPrompt.systemInstruction(),
            )
            result.fold(
                onSuccess = { markdown ->
                    viewModelScope.launch {
                        app.lessonStorageRepository.save(topic, markdown)
                        refreshSavedLessonsList()
                        applyLessonFromMarkdown(topic, markdown, fromCache = false)
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Ошибка генерации"
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            lessonPhase = LessonSessionPhase.Idle,
                            lessonStatus = message,
                        )
                    }
                    logLocal("Lesson", message)
                },
            )
        }
    }

    private fun enterHeadsetIsolation() {
        app.speechService.stopSpeaking()
        app.voiceInputService.cancel()
        app.headsetButtonNotifier.btPlayTestIsolation = true
        if (_uiState.value.settings.enableNativeHeadsetCapture) {
            HeadsetMonitorService.start(getApplication())
        }
    }

    private fun exitHeadsetIsolation() {
        if (_uiState.value.showTests || _uiState.value.showLesson) {
            return
        }
        app.headsetButtonNotifier.btPlayTestIsolation = false
        app.headsetButtonNotifier.isolatedBtPlayHandler = null
        app.voiceInputService.cancel()
        app.speechService.stopSpeaking()
    }

    fun startSttTest() {
        if (!_uiState.value.showTests) {
            return
        }
        app.speechService.stopSpeaking()
        val locale = _uiState.value.settings.voiceInputLocale.ifBlank { "ru-RU" }
        _uiState.update {
            it.copy(
                sttTestListening = true,
                sttTestStatus = "Слушаю ($locale)…",
            )
        }
        logLocal("Voice", "STT test start locale=$locale")
        app.voiceInputService.startListening(locale) { result ->
            val at = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val (status, lastText, logLine) = when (result) {
                is VoiceInputResult.Success ->
                    Triple("OK", result.text, "$at  OK  «${result.text}»")
                is VoiceInputResult.Empty ->
                    Triple("Пусто (нет речи)", "", "$at  EMPTY")
                is VoiceInputResult.Cancelled ->
                    Triple("Отменено", _uiState.value.sttTestLastText, "$at  CANCELLED")
                is VoiceInputResult.Error ->
                    Triple(result.message, "", "$at  ERR  ${result.message}")
            }
            _uiState.update { state ->
                state.copy(
                    sttTestListening = false,
                    sttTestStatus = status,
                    sttTestLastText = lastText.ifBlank { state.sttTestLastText },
                    sttTestEventLog = (listOf(logLine) + state.sttTestEventLog).take(40),
                )
            }
            logLocal("Voice", "STT test: $logLine")
        }
    }

    fun cancelSttTest() {
        app.voiceInputService.cancel()
        _uiState.update {
            it.copy(sttTestListening = false, sttTestStatus = "Отменено")
        }
    }

    fun clearSttTestLog() {
        _uiState.update {
            it.copy(
                sttTestEventLog = emptyList(),
                sttTestLastText = "",
                sttTestStatus = "",
            )
        }
    }

    fun resetBtPlayCounter() {
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

    fun simulateBtPlay() {
        app.headsetButtonNotifier.notifyButton("MEDIA_PLAY", source = "ui-simulate")
    }

    private fun recordBtPlayEvent(label: String) {
        val at = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _uiState.update { state ->
            val line = "$at  $label  (#${state.btPlayPressCount + 1})"
            state.copy(
                btPlayPressCount = state.btPlayPressCount + 1,
                btPlayLastLabel = label,
                btPlayLastAt = at,
                btPlayEventLog = (listOf(line) + state.btPlayEventLog).take(40),
            )
        }
    }

    fun refreshTtsVoices() {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            app.speechService.applyTtsSettings(settings)
            val useOpenAi = settings.ttsEngine.equals("openai", ignoreCase = true)
            val voices = if (useOpenAi) {
                if (settings.openAiApiKey.isBlank()) {
                    _uiState.update {
                        it.copy(
                            ttsVoices = emptyList(),
                            ttsVoicesStatus = "Укажите OpenAI API key в настройках",
                        )
                    }
                    logLocal("TTS", "OpenAI key missing")
                    return@launch
                }
                app.speechService.listOpenAiVoices()
            } else {
                var loaded = emptyList<TtsVoiceInfo>()
                repeat(10) { attempt ->
                    loaded = withContext(Dispatchers.Default) {
                        app.speechService.listGoogleVoices()
                    }
                    if (loaded.isNotEmpty()) {
                        return@repeat
                    }
                    delay(300L * (attempt + 1))
                }
                loaded
            }
            val status = when {
                voices.isNotEmpty() && useOpenAi ->
                    "OpenAI TTS: ${OpenAiTtsClient.VOICES.size} голосов (en/ru)"
                voices.isNotEmpty() -> "Google TTS: ${voices.size} (en/ru)"
                useOpenAi -> "OpenAI: нет голосов"
                app.speechService.isReady() -> "Голоса en/ru не найдены (установите Google TTS)"
                else -> "Движок TTS ещё не готов"
            }
            _uiState.update {
                it.copy(ttsVoices = voices, ttsVoicesStatus = status)
            }
            logLocal("TTS", status)
        }
    }

    fun previewTtsVoice(voice: TtsVoiceInfo) {
        val settings = _uiState.value.settings
        app.speechService.applyTtsSettings(settings)
        val sample = if (voice.language == "ru") {
            SpeechService.SAMPLE_RU
        } else {
            SpeechService.SAMPLE_EN
        }
        app.speechService.speakWithVoice(voice.name, sample, voice.language)
        logLocal("TTS", "Preview ${voice.displayLabel}")
    }

    fun selectTtsVoice(voice: TtsVoiceInfo) {
        val settings = _uiState.value.settings
        val updated = if (voice.language == "ru") {
            settings.copy(ttsRussianVoiceName = voice.name)
        } else {
            settings.copy(ttsEnglishVoiceName = voice.name)
        }
        _uiState.update { it.copy(settings = updated) }
        app.speechService.applyTtsSettings(updated)
        previewTtsVoice(voice)
        logLocal("TTS", "Selected ${voice.language} voice=${voice.name} (${voice.provider})")
    }

    fun stopTtsPreview() {
        app.speechService.stopSpeaking()
    }

    /** Остановить озвучку входящих на экране чата. */
    fun stopChatTts() {
        val wasPlaying = app.speechService.isPlaybackActive()
        app.speechService.stopSpeaking()
        if (wasPlaying) {
            logLocal("TTS", "Stopped by chat button")
            _uiState.update { it.copy(statusText = "Озвучка остановлена") }
        }
    }

    fun updateSettings(settings: AppSettings) {
        _uiState.update { it.copy(settings = settings) }
        // Чтобы гарнитура/OCR видели «Кому» сразу, даже до «Сохранить».
        syncActiveSettings(settings)
    }

    /** Смена получателя с главного экрана — сразу в UI, затем на диск. */
    fun selectRecipient(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = _uiState.value.settings
        if (current.recipientName.equals(trimmed, ignoreCase = true)) return
        val updated = current.copy(recipientName = trimmed).withNormalizedRecipients()
        // Синхронно: иначе Send может уйти со старым recipient до завершения launch.
        _uiState.update { it.copy(settings = updated) }
        syncActiveSettings(updated)
        viewModelScope.launch {
            app.settingsRepository.save(updated, commit = true)
            logLocal("Settings", "Recipient → ${updated.recipientName}")
            android.util.Log.i("AndroidChatBtTest95", "Recipient → ${updated.recipientName}")
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Камера → JPEG в Storage `chat-files` + INSERT `type=file` / `kind=photo`.
     * OCR на телефоне не выполняется — анализ делает получатель (Hermes).
     */
    fun onCameraCaptureFailed(reason: String) {
        logLocal("Photo", reason)
        _uiState.update { it.copy(isBusy = false, statusText = reason) }
    }

    fun sendCameraPhoto(uri: android.net.Uri) {
        android.util.Log.i("CameraPhoto", "sendCameraPhoto uri=$uri")
        viewModelScope.launch {
            val settings = _uiState.value.settings
            val recipient = settings.recipientName.trim().ifBlank { "WpfChat" }
            _uiState.update {
                it.copy(isBusy = true, statusText = "Отправка фото → $recipient…")
            }
            logLocal("Photo", "Upload start → $recipient uri=$uri")
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    app.photoOcrService.compressToJpeg(uri)
                        ?: error("Не удалось прочитать фото")
                }
                val name = "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                android.util.Log.i("CameraPhoto", "jpegBytes=${jpeg.size} name=$name recipient=$recipient")
                val result = app.chatRepository.sendFileBytes(
                    settings = settings,
                    fileName = name,
                    bytes = jpeg,
                    mime = "image/jpeg",
                    kind = FileMessageFormat.KIND_PHOTO,
                )
                if (result.isSuccess) {
                    logLocal("Photo", "Sent $name (${jpeg.size} B) → $recipient")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusText = "Фото отправлено → $recipient",
                        )
                    }
                } else {
                    val message = result.exceptionOrNull()?.message ?: "Ошибка отправки фото"
                    logLocal("Photo", message)
                    _uiState.update { it.copy(isBusy = false, statusText = message) }
                }
            } catch (error: Throwable) {
                val message = error.message ?: "Ошибка отправки фото"
                android.util.Log.e("CameraPhoto", message, error)
                logLocal("Photo", message)
                _uiState.update { it.copy(isBusy = false, statusText = message) }
            } finally {
                app.photoOcrService.deleteQuietly(uri)
            }
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            app.settingsRepository.save(settings, commit = true)
            syncActiveSettings(settings)
            logLocal("Settings", "Settings saved; recipient=${settings.recipientName}")
            _uiState.update {
                it.copy(
                    showSettings = false,
                    statusText = "Настройки сохранены · Кому: ${settings.recipientName}",
                    lessonEnFontSp = settings.lessonEnFontSp,
                    lessonRuFontSp = settings.lessonRuFontSp,
                )
            }
            connect()
            updateHeadsetMonitor()
        }
    }

    private fun updateHeadsetMonitor() {
        if (_uiState.value.settings.enableNativeHeadsetCapture) {
            HeadsetMonitorService.start(getApplication())
            logLocal("Headset", "Native capture service started")
        } else {
            HeadsetMonitorService.stop(getApplication())
        }
    }

    fun connect() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, connectionStatus = "Подключение…") }
            val settings = _uiState.value.settings
            val result = app.chatRepository.connect(settings, viewModelScope)
            val status = if (result.isSuccess) {
                if (settings.enableSupabasePoll) {
                    logLocal("Supabase", "Connected, WebSocket realtime started")
                    "WebSocket realtime"
                } else {
                    logLocal("Supabase", "Connected, WebSocket poll disabled in settings")
                    "Подключено · poll выкл."
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Ошибка подключения"
                logLocal("Supabase", error)
                error
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    connectionStatus = if (result.isSuccess) "Подключено" else "Ошибка",
                    statusText = status,
                )
            }
            if (result.isSuccess) {
                updateHeadsetMonitor()
                if (_uiState.value.mainTab == 1) {
                    hrt.pullSnapshot(includeScreenshot = false, reason = "connect")
                }
            }
        }
    }

    fun refreshMessages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Обновление…") }
            val result = app.chatRepository.refreshHistory()
            val status = result.fold(
                { count ->
                    logLocal("Chat", "History refreshed ($count messages)")
                    "Загружено сообщений: $count"
                },
                { ex ->
                    val message = ex.message ?: "Ошибка обновления"
                    logLocal("Chat", message)
                    message
                },
            )
            _uiState.update {
                it.copy(isBusy = false, statusText = status)
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Очистка чата…") }
            val settings = _uiState.value.settings
            if (!app.chatRepository.isReady()) {
                val connectResult = app.chatRepository.connect(settings, viewModelScope)
                if (connectResult.isFailure) {
                    val message = connectResult.exceptionOrNull()?.message ?: "Нет подключения к Supabase"
                    logLocal("Chat", message)
                    _uiState.update { it.copy(isBusy = false, statusText = message) }
                    return@launch
                }
            }

            val result = app.chatRepository.clearAllMessages()
            val status = result.fold(
                { count ->
                    logLocal("Chat", "Chat cleared by user ($count messages deleted)")
                    "Чат очищен ($count)"
                },
                { ex ->
                    val message = ex.message ?: "Ошибка очистки чата"
                    logLocal("Chat", message)
                    message
                },
            )
            _uiState.update { it.copy(isBusy = false, statusText = status) }
        }
    }

    fun sendMessage() {
        val snapshot = _uiState.value
        val text = snapshot.inputText
        if (text.isBlank()) {
            return
        }
        // Фиксируем получателя в момент нажатия Send (не перечитывать позже).
        val settings = snapshot.settings

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Отправка → ${settings.recipientName}…") }
            android.util.Log.i(
                "AndroidChatBtTest95",
                "Send → recipient=${settings.recipientName} len=${text.length}",
            )
            val result = app.chatRepository.sendMessage(settings, text)
            if (result.isSuccess) {
                logLocal("Chat", "Sent → ${settings.recipientName}: $text")
            } else {
                logLocal("Chat", "Send error: ${result.exceptionOrNull()?.message}")
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    inputText = if (result.isSuccess) "" else text,
                    statusText = result.exceptionOrNull()?.message
                        ?: "Отправлено → ${settings.recipientName}",
                )
            }
        }
    }

    fun sendLogsToWpf() {
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
                if (entry.shouldSkipManualSupabaseUpload(settings.skipDuplicateLogsToSupabase)) {
                    skipped++
                    continue
                }
                val message = "${entry.message} (${entry.status})"
                val result = app.supabaseRepository.sendLogMessage(
                    settings = settings,
                    category = entry.category,
                    message = message,
                    senderName = "AndroidChatBtTest95",
                )
                if (result.isSuccess) {
                    sent++
                } else {
                    failed++
                }
            }

            logLocal("Logs", "Sent to WpfChat: $sent ok, $failed failed, $skipped skipped")
            val statusText = buildString {
                append("Логи: отправлено $sent")
                if (skipped > 0) {
                    append(", пропущено $skipped")
                }
                if (failed > 0) {
                    append(", ошибок $failed")
                }
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusText = statusText,
                )
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            app.localLogRepository.clear()
            logLocal("Logs", "Local log cleared")
            _uiState.update { it.copy(statusText = "Локальный лог очищен") }
        }
    }



    private fun logLocal(category: String, message: String) {
        viewModelScope.launch {
            app.localLogRepository.logLocal(category, message)
        }
    }

    companion object {
        private val EMPTY_CONFIRM_STT_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
    }
}
