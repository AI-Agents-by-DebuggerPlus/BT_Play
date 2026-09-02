package com.taskertowpf.androidchatbttest.headset

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.taskertowpf.androidchatbttest.AndroidChatBtTestApp
import com.taskertowpf.androidchatbttest.InterceptMonitorActivity
import com.taskertowpf.androidchatbttest.MainActivity
import com.taskertowpf.androidchatbttest.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * FGS + MediaSession (baseline AndroidChat: STATE_PAUSED, без AudioFocus и MediaButtonReceiver).
 * Диагностика ActiveSessions + Watchdog для BT Play тестов.
 */
class HeadsetMonitorService : Service() {
    private val instanceId = System.identityHashCode(this).toString(16)
    private var mediaSession: MediaSessionCompat? = null
    private var playbackStateCode: Int = PlaybackStateCompat.STATE_NONE
    private var lastHeartbeatFingerprint: String? = null
    private var lastKeyEventCount = 0
    private val keyEventCount = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            dumpDiagnostics(reason = "heartbeat", verbose = false)
            val current = keyEventCount.get()
            if (current == lastKeyEventCount) {
                logDiag(
                    "Watchdog: ни одного media-button события с прошлого heartbeat " +
                        "(всего с старта: $current)",
                )
            }
            lastKeyEventCount = current
            handler.removeCallbacks(this)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val n = createCount.incrementAndGet()
        logDiag(
            "Service onCreate #$n instance=$instanceId sdk=${Build.VERSION.SDK_INT} " +
                "(AndroidChat baseline: PAUSED, no AudioFocus)",
        )
        createNotificationChannel()
        attachMediaSession(reason = "onCreate")
        startForegroundWithNotification()
        startActiveSessionsWatch()
        dumpDiagnostics(reason = "onCreate", verbose = true)
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        logDiag(
            "onStartCommand action=${action ?: "(null)"} " +
                "hasExtraKey=${intent?.hasExtra(Intent.EXTRA_KEY_EVENT) == true}",
        )
        when (action) {
            ACTION_REASSERT -> reassertSession(reason = "intent-reassert")
            ACTION_DIAGNOSE -> dumpDiagnostics(reason = "intent-diagnose", verbose = true)
            else -> {
                if (mediaSession?.isActive != true) {
                    attachMediaSession(reason = "onStartCommand-inactive")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        logDiag("Service onDestroy instance=$instanceId")
        handler.removeCallbacks(heartbeatRunnable)
        stopActiveSessionsWatch()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachMediaSession(reason: String) {
        if (mediaSession != null) {
            logDiag("attachMediaSession skip (already exists) reason=$reason")
            return
        }
        val notifier = HeadsetButtonNotifier.get(this)
        val session = MediaSessionCompat(this, SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        logDiag("Callback onPlay()")
                        notifier.notifyButton("MEDIA_PLAY", source = "callback-onPlay")
                    }

                    override fun onPause() {
                        logDiag("Callback onPause()")
                        notifier.notifyButton("MEDIA_PAUSE", source = "callback-onPause")
                    }

                    override fun onSkipToNext() {
                        logDiag("Callback onSkipToNext()")
                        notifier.notifyButton("MEDIA_NEXT", source = "callback-onNext")
                    }

                    override fun onSkipToPrevious() {
                        logDiag("Callback onSkipToPrevious()")
                        notifier.notifyButton("MEDIA_PREVIOUS", source = "callback-onPrev")
                    }

                    override fun onStop() {
                        logDiag("Callback onStop()")
                        notifier.notifyButton("MEDIA_STOP", source = "callback-onStop")
                    }

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent?): Boolean {
                        val event = extractKeyEvent(mediaButtonIntent)
                        if (event == null) {
                            logDiag("onMediaButtonEvent: no KeyEvent, defer to super")
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                        val known = HeadsetButtonNames.fromKeyCode(event.keyCode)
                        logDiag(
                            "onMediaButtonEvent keyCode=${event.keyCode} " +
                                "label=${known ?: "UNKNOWN"} action=${event.action} " +
                                "repeat=${event.repeatCount} source=${event.source}",
                        )
                        if (known != null &&
                            event.action == KeyEvent.ACTION_DOWN &&
                            event.repeatCount == 0
                        ) {
                            keyEventCount.incrementAndGet()
                            notifier.notifyButton(known, source = "mediaButtonEvent")
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                },
            )
            setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_PAUSED))
            isActive = true
        }
        mediaSession = session
        playbackStateCode = PlaybackStateCompat.STATE_PAUSED
        logDiag("MediaSession attached reason=$reason active=${session.isActive} playback=PAUSED")
    }

    private fun reassertSession(reason: String) {
        logDiag("reassertSession reason=$reason")
        val session = mediaSession ?: run {
            attachMediaSession(reason = "reassert-create")
            mediaSession
        } ?: return
        session.isActive = true
        session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_PAUSED))
        playbackStateCode = PlaybackStateCompat.STATE_PAUSED
        startForegroundWithNotification()
        dumpDiagnostics(reason = "after-$reason", verbose = true)
    }

    private fun buildPlaybackState(state: Int): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(state, 0, 0f)
            .build()

    private fun dumpDiagnostics(reason: String, verbose: Boolean) {
        val session = mediaSession
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val musicActive = am.isMusicActive
        val mode = am.mode
        val stateName = playbackStateName(playbackStateCode)

        val line =
            "DIAG[$reason] sessionExists=${session != null} active=${session?.isActive} " +
                "playback=$stateName musicActive=$musicActive " +
                "audioMode=$mode recordAudio=$recordAudioGranted " +
                "notifEnabled=$notifEnabled postNotif=$postNotifGranted"
        if (reason == "heartbeat") {
            if (line == lastHeartbeatFingerprint) {
                return
            }
            lastHeartbeatFingerprint = line
        }
        logDiag(line)
        if (verbose) {
            logDiag(
                "DIAG[$reason] HINT=первый в ActiveSessions получает кнопку; " +
                    "остановите AndroidChatCopy / YouTube / другие плееры",
            )
            publishActiveSessions()
            val app = application as? AndroidChatBtTestApp
            val snap = app?.bluetoothInventory?.snapshot()
            if (snap != null) {
                val playing = snap.devices.filter { it.a2dpPlaying || it.hfpAudio }
                logDiag(
                    "DIAG[$reason] BT adapterEnabled=${snap.adapterEnabled} " +
                        "active=${snap.activeSummary} a2dpOrHfpAudioDevices=${playing.size}",
                )
            }
        }
    }

    private fun publishActiveSessions() {
        val rows = ActiveSessionsHelper.snapshot(this)
        if (rows.isEmpty()) {
            logDiag(
                "ActiveSessions: пусто или недоступно — включите Notification Access " +
                    "для AndroidChatBtTest",
            )
            HeadsetButtonNotifier.get(this).publishActiveSessions(emptyList())
            return
        }
        logDiag(
            "ActiveSessions (порядок = приоритет): " +
                rows.joinToString { "${it.packageName}(${it.playbackState})" },
        )
        HeadsetButtonNotifier.get(this).publishActiveSessions(rows)
    }

    private fun startActiveSessionsWatch() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, NoOpNotificationListener::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val rows = ActiveSessionsHelper.mapControllers(this, controllers.orEmpty())
            logDiag(
                "ActiveSessionsChanged: ${rows.joinToString { it.packageName }} " +
                    "top=${rows.firstOrNull()?.packageName ?: "—"}",
            )
            HeadsetButtonNotifier.get(this).publishActiveSessions(rows)
        }
        activeSessionsListener = listener
        runCatching {
            msm.addOnActiveSessionsChangedListener(listener, component)
            publishActiveSessions()
        }.onFailure { error ->
            logDiag(
                "addOnActiveSessionsChangedListener failed: ${error.message} — " +
                    "нужен Notification Access",
            )
        }
    }

    private fun stopActiveSessionsWatch() {
        val listener = activeSessionsListener ?: return
        runCatching {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(listener)
        }
        activeSessionsListener = null
    }

    private fun playbackStateName(code: Int): String = when (code) {
        PlaybackStateCompat.STATE_NONE -> "NONE"
        PlaybackStateCompat.STATE_STOPPED -> "STOPPED"
        PlaybackStateCompat.STATE_PAUSED -> "PAUSED"
        PlaybackStateCompat.STATE_PLAYING -> "PLAYING"
        PlaybackStateCompat.STATE_BUFFERING -> "BUFFERING"
        else -> "code=$code"
    }

    private fun extractKeyEvent(intent: Intent?): KeyEvent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Кнопки гарнитуры",
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "BT Play тест (AndroidChat baseline)"
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val monitorIntent = Intent(this, InterceptMonitorActivity::class.java)
        val monitorPending = PendingIntent.getActivity(
            this,
            1,
            monitorIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.headset_monitor_title))
            .setContentText(getString(R.string.headset_monitor_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .addAction(0, "Перехват", monitorPending)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        logDiag("Foreground notification posted (simple, no MediaStyle)")
    }

    private fun logDiag(message: String) {
        val tagged = "[$instanceId] $message"
        Log.i(TAG, tagged)
        val app = applicationContext as? AndroidChatBtTestApp ?: return
        scope.launch {
            app.localLogRepository.logLocal("HeadsetDiag", tagged)
        }
    }

    companion object {
        private const val TAG = "HeadsetMonitorService"
        private const val SESSION_TAG = "AndroidChatBtTestHeadset"
        private const val CHANNEL_ID = "androidchatbttest_headset"
        private const val NOTIFICATION_ID = 44
        private const val HEARTBEAT_MS = 20_000L
        private const val ACTION_REASSERT = "com.taskertowpf.androidchatbttest.action.REASSERT"
        private const val ACTION_DIAGNOSE = "com.taskertowpf.androidchatbttest.action.DIAGNOSE"
        private val createCount = AtomicInteger(0)

        fun start(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadsetMonitorService::class.java))
        }

        fun reassert(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java).apply {
                action = ACTION_REASSERT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun diagnose(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java).apply {
                action = ACTION_DIAGNOSE
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
