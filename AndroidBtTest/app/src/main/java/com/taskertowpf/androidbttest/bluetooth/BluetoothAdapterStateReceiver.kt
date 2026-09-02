package com.taskertowpf.androidbttest.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

class BluetoothAdapterStateReceiver(
    private val onStateChanged: (state: Int, label: String) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        val label = stateLabel(state)
        onStateChanged(state, label)
        Log.i(TAG, "Adapter state → $label ($state)")
    }

    companion object {
        private const val TAG = "BtAdapterStateReceiver"

        fun createIntentFilter(): IntentFilter =
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        fun stateLabel(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            else -> "STATE_UNKNOWN($state)"
        }
    }
}
