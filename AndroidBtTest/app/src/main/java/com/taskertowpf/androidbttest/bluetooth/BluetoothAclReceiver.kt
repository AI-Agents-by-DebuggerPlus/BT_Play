package com.taskertowpf.androidbttest.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

class BluetoothAclReceiver(
    private val onEvent: (connected: Boolean, name: String, address: String) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = intent.bluetoothDevice()
        val name = runCatching { device?.name }.getOrNull().orEmpty().ifBlank { "(без имени)" }
        val address = runCatching { device?.address }.getOrNull().orEmpty()
        onEvent(connected, name, address)
        Log.i(TAG, "ACL ${if (connected) "connected" else "disconnected"}: $name $address")
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "BluetoothAclReceiver"

        fun createIntentFilter(): IntentFilter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
    }
}
