package com.taskertowpf.androidchatbttestv1.headset

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.taskertowpf.androidchatbttestv1.AndroidChatBtTestV1App
import com.taskertowpf.androidchatbttestv1.InterceptMonitorActivity
import com.taskertowpf.androidchatbttestv1.MainActivity
import com.taskertowpf.androidchatbttestv1.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Baseline A/B: FGS + MediaSessionCompat как в AndroidChatCopy.
 * Без MediaSessionManager / ActiveSessions / NotificationListener / watchdog / reassert / AudioFocus.
 */
class HeadsetMonitorService : Service() {
    private val instanceId = System.identityHashCode(this).toString(16)
    private var mediaSession: MediaSessionCompat? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        logDiag("Service onCreate instance=$instanceId sdk=${Build.VERSION.SDK_INT}")
        createNotificationChannel()
        startForegroundWithNotification()
        attachMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaSession?.isActive != true) {
            attachMediaSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachMediaSession() {
        if (mediaSession != null) {
            Log.d(TAG, "attachMediaSession: уже существует, пропуск")
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
                            "onMediaButtonEvent " +
                                "keyCode=${event.keyCode} " +
                                "label=${known ?: "UNKNOWN"} " +
                                "action=${event.action} " +
                                "repeat=${event.repeatCount}",
                        )

                        if (
                            known != null &&
                            event.action == KeyEvent.ACTION_DOWN &&
                            event.repeatCount == 0
                        ) {
                            notifier.notifyButton(known, source = "mediaButtonEvent")
                            return true
                        }

                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                },
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP,
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
                    .build(),
            )
            isActive = true
        }

        mediaSession = session
        logDiag("MediaSession attached active=${session.isActive}")
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
            description = "BT Play baseline V1 (AndroidChatCopy-like)"
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
    }

    private fun logDiag(message: String) {
        val tagged = "[$instanceId] $message"
        Log.i(TAG, tagged)
        // В UI-лог только callback / media-button (без шума attach/onCreate).
        if (!message.startsWith("Callback ") && !message.startsWith("onMediaButtonEvent")) {
            return
        }
        val app = applicationContext as? AndroidChatBtTestV1App ?: return
        scope.launch {
            app.localLogRepository.logLocal("Headset", tagged.substringAfter("] ").ifBlank { tagged })
        }
    }

    companion object {
        private const val TAG = "HeadsetMonitorService"
        private const val SESSION_TAG = "AndroidChatBtTestV1Headset"
        private const val CHANNEL_ID = "androidchatbttestv1_headset"
        private const val NOTIFICATION_ID = 45

        fun start(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadsetMonitorService::class.java))
        }

        /** Baseline: только гарантирует запуск FGS + MediaSession (без reassert/diagnose). */
        fun ensureRunning(context: Context) {
            start(context)
        }
    }
}
