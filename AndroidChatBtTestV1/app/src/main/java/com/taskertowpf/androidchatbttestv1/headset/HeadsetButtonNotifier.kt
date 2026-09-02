package com.taskertowpf.androidchatbttestv1.headset

import android.content.Context
import android.util.Log
import com.taskertowpf.androidchatbttestv1.AndroidChatBtTestV1App
import com.taskertowpf.androidchatbttestv1.data.ActiveMediaSessionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HeadsetButtonNotifier(
    private val app: AndroidChatBtTestV1App,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastSentKey: String? = null
    private var lastSentAtMs: Long = 0L

    @Volatile
    var onButton: ((label: String, source: String) -> Unit)? = null

    @Volatile
    var onActiveSessionsChanged: ((owners: List<String>) -> Unit)? = null

    @Volatile
    var onActiveSessionRowsChanged: ((rows: List<ActiveMediaSessionRow>) -> Unit)? = null

    fun notifyButton(buttonLabel: String, source: String = "native") {
        val label = HeadsetButtonNames.normalize(buttonLabel)
        val now = System.currentTimeMillis()
        val debounceKey = if (HeadsetButtonNames.isBtPlayLabel(label)) BT_PLAY_KEY else label

        scope.launch {
            mutex.withLock {
                if (debounceKey == lastSentKey && now - lastSentAtMs < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounced: $label ($source)")
                    return@launch
                }
                lastSentKey = debounceKey
                lastSentAtMs = now
            }
            Log.i(TAG, "Button $label via $source")
            app.localLogRepository.logLocal("Headset", "$label via $source")
            withContext(Dispatchers.Main) {
                onButton?.invoke(label, source)
            }
        }
    }

    fun publishActiveSessions(rows: List<ActiveMediaSessionRow>) {
        val owners = rows.map { it.packageName }
        scope.launch {
            withContext(Dispatchers.Main) {
                onActiveSessionsChanged?.invoke(owners)
                onActiveSessionRowsChanged?.invoke(rows)
            }
        }
    }

    companion object {
        private const val TAG = "HeadsetButtonNotifier"
        private const val DEBOUNCE_MS = 500L
        private const val BT_PLAY_KEY = "BT_PLAY"

        fun get(context: Context): HeadsetButtonNotifier {
            val app = context.applicationContext as AndroidChatBtTestV1App
            return app.headsetButtonNotifier
        }
    }
}
