package com.taskertowpf.androidchatbttest95.headset

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
import com.taskertowpf.androidchatbttest95.AndroidChatApp
import com.taskertowpf.androidchatbttest95.MainActivity
import com.taskertowpf.androidchatbttest95.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service + MediaSession для приёма media-кнопок Bluetooth-гарнитуры.
 */
class HeadsetMonitorService : Service() {
    private var mediaSession: MediaSessionCompat? = null
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        attachMediaSession()
        val app = application as AndroidChatApp
        HeadsetConnectionMonitor.ensureStarted(
            context = this,
            speechService = app.speechService,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaSession?.isActive != true) {
            attachMediaSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        HeadsetConnectionMonitor.stop(this)
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
        val session = MediaSessionCompat(this, "AndroidChatBtTest95Headset").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        notifier.notifyButton("MEDIA_PLAY", source = "hardware-callback-onPlay")
                    }

                    override fun onPause() {
                        notifier.notifyButton("MEDIA_PAUSE", source = "hardware-callback-onPause")
                    }

                    override fun onSkipToNext() {
                        notifier.notifyButton("MEDIA_NEXT", source = "hardware-callback-onNext")
                    }

                    override fun onSkipToPrevious() {
                        notifier.notifyButton("MEDIA_PREVIOUS", source = "hardware-callback-onPrev")
                    }

                    override fun onStop() {
                        notifier.notifyButton("MEDIA_STOP", source = "hardware-callback-onStop")
                    }

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent?): Boolean {
                        val event = extractKeyEvent(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                        val label = HeadsetButtonNames.fromKeyCode(event.keyCode)
                        if (label != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            notifier.notifyButton(label, source = "hardware-mediaButtonEvent")
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
        Log.i(TAG, "attachMediaSession: MediaSession активирована (${System.currentTimeMillis()})")
        logScope.launch {
            val app = application as? AndroidChatApp ?: return@launch
            app.localLogRepository.logLocal(
                "Headset",
                "attachMediaSession: MediaSession активирована",
            )
        }
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
            description = "Мониторинг media-кнопок Bluetooth для отправки в WpfChat"
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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.headset_monitor_title))
            .setContentText(getString(R.string.headset_monitor_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
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

    companion object {
        private const val TAG = "HeadsetMonitorService"
        private const val CHANNEL_ID = "androidchatbttest95_headset"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadsetMonitorService::class.java))
        }
    }
}
