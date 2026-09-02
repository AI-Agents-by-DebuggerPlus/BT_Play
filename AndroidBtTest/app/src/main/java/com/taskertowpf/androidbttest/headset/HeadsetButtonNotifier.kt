package com.taskertowpf.androidbttest.headset

import android.content.Context
import android.util.Log
import com.taskertowpf.androidbttest.AndroidBtTestApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HeadsetButtonNotifier(
    private val app: AndroidBtTestApp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastSentKey: String? = null
    private var lastSentAtMs: Long = 0L

    @Volatile
    var onButton: ((label: String, source: String) -> Unit)? = null

    @Volatile
    var onActiveSessionsChanged: ((owners: List<String>) -> Unit)? = null

    fun notifyButton(buttonLabel: String, source: String = "native") {
        val label = HeadsetButtonNames.normalize(buttonLabel)
        val now = System.currentTimeMillis()
        val debounceKey = if (HeadsetButtonNames.isBtPlayLabel(label)) BT_PLAY_KEY else label

        scope.launch {
            mutex.withLock {
                if (debounceKey == lastSentKey && now - lastSentAtMs < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounced: $label ($source)")
                    app.localLogRepository.logLocal(
                        "HeadsetDiag",
                        "Debounced $label via $source (within ${DEBOUNCE_MS}ms)",
                    )
                    return@launch
                }
                lastSentKey = debounceKey
                lastSentAtMs = now
            }
            Log.i(TAG, "Button $label via $source")
            app.localLogRepository.logLocal("Headset", "$label via $source")
            app.localLogRepository.logLocal(
                "HeadsetDiag",
                "Accepted $label via $source → UI callback",
            )
            withContext(Dispatchers.Main) {
                onButton?.invoke(label, source)
            }
        }
    }

    companion object {
        private const val TAG = "HeadsetButtonNotifier"
        private const val DEBOUNCE_MS = 500L
        private const val BT_PLAY_KEY = "BT_PLAY"

        fun get(context: Context): HeadsetButtonNotifier {
            val app = context.applicationContext as AndroidBtTestApp
            return app.headsetButtonNotifier
        }
    }
}
