package com.taskertowpf.androidchat

import com.taskertowpf.androidchat.bridge.HeadsetPlayHandler
import com.taskertowpf.androidchat.data.BackupFolderStorage
import com.taskertowpf.androidchat.data.ChatRepository
import com.taskertowpf.androidchat.data.LocalLogRepository
import com.taskertowpf.androidchat.data.OpenRouterService
import com.taskertowpf.androidchat.data.PhotoOcrService
import com.taskertowpf.androidchat.data.SettingsRepository
import com.taskertowpf.androidchat.data.SupabaseRepository
import com.taskertowpf.androidchat.headset.HeadsetButtonNotifier
import com.taskertowpf.androidchat.headset.HeadsetConnectionMonitor
import com.taskertowpf.androidchat.lesson.LessonStorageRepository
import com.taskertowpf.androidchat.voice.VoiceInputService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndroidChatApp : android.app.Application() {
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var supabaseRepository: SupabaseRepository
        private set

    lateinit var chatRepository: ChatRepository
        private set
    lateinit var localLogRepository: LocalLogRepository
        private set

    lateinit var backupFolderStorage: BackupFolderStorage
        private set

    lateinit var openRouterService: OpenRouterService
        private set

    lateinit var photoOcrService: PhotoOcrService
        private set

    lateinit var lessonStorageRepository: LessonStorageRepository
        private set

    lateinit var speechService: SpeechService
        private set

    lateinit var voiceInputService: VoiceInputService
        private set

    lateinit var headsetButtonNotifier: HeadsetButtonNotifier
        private set

    lateinit var headsetPlayHandler: HeadsetPlayHandler
        private set

    @Volatile
    private var recordAudioPermissionRequester: ((Boolean) -> Unit)? = null

    /**
     * @return true если запрос отправлен в Activity.
     */
    fun requestRecordAudioPermission(onResult: (Boolean) -> Unit): Boolean {
        val requester = recordAudioPermissionRequester
        if (requester == null) {
            return false
        }
        pendingRecordAudioCallback = onResult
        requester.invoke(true)
        return true
    }

    @Volatile
    private var pendingRecordAudioCallback: ((Boolean) -> Unit)? = null

    fun bindRecordAudioPermissionRequester(request: () -> Unit) {
        recordAudioPermissionRequester = { _ -> request() }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        val cb = pendingRecordAudioCallback
        pendingRecordAudioCallback = null
        cb?.invoke(granted)
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        supabaseRepository = SupabaseRepository()
        localLogRepository = LocalLogRepository()
        backupFolderStorage = BackupFolderStorage(this)
        openRouterService = OpenRouterService()
        photoOcrService = PhotoOcrService(this, openRouterService)
        lessonStorageRepository = LessonStorageRepository(this)
        chatRepository = ChatRepository(localLogRepository, backupFolderStorage)
        speechService = SpeechService(this)
        voiceInputService = VoiceInputService(this)
        headsetButtonNotifier = HeadsetButtonNotifier(this)
        headsetPlayHandler = HeadsetPlayHandler(this)
        HeadsetConnectionMonitor.ensureStarted(
            context = this,
            speechService = speechService,
        )
        appScope.launch {
            val settings = settingsRepository.load()
            if (settings.supabaseUrl.isNotBlank() && settings.enableHeadsetToChat) {
                chatRepository.ensureConnectedForRelay(settings, appScope)
            }
        }
    }

    fun shutdownForStop() {
        voiceInputService.cancel()
        appJob.cancel()
    }
}
