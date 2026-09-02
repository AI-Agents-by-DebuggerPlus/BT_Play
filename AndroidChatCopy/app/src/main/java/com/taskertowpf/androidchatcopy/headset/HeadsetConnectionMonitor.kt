package com.taskertowpf.androidchatcopy.headset

import android.content.Context
import android.util.Log
import com.taskertowpf.androidchatcopy.SpeechService

/**
 * Dynamic ACL receiver — must not be registered in manifest (Android 8+ restrictions).
 */
object HeadsetConnectionMonitor {
    private val lock = Any()
    private var receiver: HeadsetConnectionReceiver? = null
    private var registrationCount = 0

    fun ensureStarted(
        context: Context,
        speechService: SpeechService,
        targetDeviceNameContains: String = HeadsetConnectionConstants.DEFAULT_DEVICE_NAME_HINT,
        onConnectionEvent: (String) -> Unit = {},
    ) {
        synchronized(lock) {
            if (receiver != null) {
                registrationCount++
                return
            }
            val appContext = context.applicationContext
            receiver = HeadsetConnectionReceiver(
                speechService = speechService,
                targetDeviceNameContains = targetDeviceNameContains,
                onConnectionEvent = onConnectionEvent,
            )
            appContext.registerReceiver(receiver, HeadsetConnectionReceiver.createIntentFilter())
            registrationCount = 1
            Log.i(TAG, "Headset ACL receiver registered")
        }
    }

    fun stop(context: Context) {
        synchronized(lock) {
            if (receiver == null) {
                return
            }
            registrationCount--
            if (registrationCount > 0) {
                return
            }
            runCatching {
                context.applicationContext.unregisterReceiver(receiver)
            }.onFailure { error ->
                Log.w(TAG, "unregisterReceiver failed", error)
            }
            receiver = null
            registrationCount = 0
            Log.i(TAG, "Headset ACL receiver unregistered")
        }
    }

    private const val TAG = "HeadsetConnectionMonitor"
}
