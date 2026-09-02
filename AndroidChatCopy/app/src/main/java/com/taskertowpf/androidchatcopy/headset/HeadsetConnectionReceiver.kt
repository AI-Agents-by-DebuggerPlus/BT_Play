package com.taskertowpf.androidchatcopy.headset

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.taskertowpf.androidchatcopy.SpeechService

class HeadsetConnectionReceiver(
    private val speechService: SpeechService,
    private val targetDeviceNameContains: String,
    private val onConnectionEvent: (String) -> Unit = {},
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getBluetoothDeviceExtra()
        if (!HeadsetConnectionHelper.matchesTarget(device, targetDeviceNameContains)) {
            return
        }

        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }

        Log.i(TAG, "ACL event: connected=$connected device=${device?.address}")

        val phrase = HeadsetConnectionHelper.connectionPhrase(targetDeviceNameContains, connected)
        speechService.speakRussian(phrase)
        onConnectionEvent(phrase)
        Log.i(TAG, "Headset ACL: $phrase")
    }

    private fun Intent.getBluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "HeadsetConnectionReceiver"

        fun createIntentFilter(): IntentFilter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
    }
}
