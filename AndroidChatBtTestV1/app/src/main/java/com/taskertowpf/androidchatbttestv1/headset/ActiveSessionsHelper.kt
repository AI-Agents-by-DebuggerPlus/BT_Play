package com.taskertowpf.androidchatbttestv1.headset

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.support.v4.media.session.PlaybackStateCompat
import com.taskertowpf.androidchatbttestv1.data.ActiveMediaSessionRow

object ActiveSessionsHelper {
    private const val OWN_PACKAGE = "com.taskertowpf.androidchatbttestv1"

    private val knownCompetitors = mapOf(
        "com.taskertowpf.androidchatcopy" to "AndroidChatCopy — часто перехватывает BT Play",
        "com.taskertowpf.androidchat" to "AndroidChat — рабочий эталон MediaSession",
        "com.taskertowpf.androidbttest" to "AndroidBtTest — старый тестовый sandbox",
        "com.google.android.youtube" to "YouTube — активная сессия забирает кнопку",
        "com.google.android.googlequicksearchbox" to "Google Assistant / media",
        "com.englishtutor" to "English Tutor",
        "com.music.player.mp3player.white" to "MP3-плеер",
    )

    fun snapshot(context: Context): List<ActiveMediaSessionRow> {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, NoOpNotificationListener::class.java)
        val controllers = runCatching { msm.getActiveSessions(component) }.getOrElse { emptyList() }
        return mapControllers(context, controllers)
    }

    fun mapControllers(context: Context, controllers: List<MediaController>): List<ActiveMediaSessionRow> {
        val pm = context.packageManager
        return controllers.mapIndexed { index, controller ->
            val pkg = controller.packageName.orEmpty()
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val state = controller.playbackState
            val stateName = playbackStateName(state?.state ?: PlaybackStateCompat.STATE_NONE)
            val competitorNote = knownCompetitors[pkg]
            ActiveMediaSessionRow(
                rank = index + 1,
                packageName = pkg,
                appLabel = label,
                playbackState = stateName,
                receivesButton = index == 0,
                isSelf = pkg == OWN_PACKAGE,
                isKnownCompetitor = competitorNote != null,
                competitorNote = competitorNote,
            )
        }
    }

    private fun playbackStateName(code: Int): String = when (code) {
        PlaybackStateCompat.STATE_NONE -> "NONE"
        PlaybackStateCompat.STATE_STOPPED -> "STOPPED"
        PlaybackStateCompat.STATE_PAUSED -> "PAUSED"
        PlaybackStateCompat.STATE_PLAYING -> "PLAYING"
        PlaybackStateCompat.STATE_BUFFERING -> "BUFFERING"
        else -> "code=$code"
    }
}
