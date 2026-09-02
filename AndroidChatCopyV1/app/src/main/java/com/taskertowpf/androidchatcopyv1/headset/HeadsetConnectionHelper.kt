package com.taskertowpf.androidchatcopyv1.headset

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.taskertowpf.androidchatcopyv1.SpeechService

object HeadsetConnectionHelper {

    fun announceCurrentConnectionState(
        context: Context,
        speechService: SpeechService,
        targetDeviceNameContains: String,
    ): Boolean {
        val connected = isTargetHeadsetConnected(context, targetDeviceNameContains)
        val phrase = if (connected) {
            "$targetDeviceNameContains подключены"
        } else {
            "$targetDeviceNameContains отключены"
        }
        speechService.speakRussian(phrase)
        return connected
    }

    fun isTargetHeadsetConnected(context: Context, targetDeviceNameContains: String): Boolean {
        if (!BluetoothPermissionHelper.hasConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted — cannot read headset state")
            return false
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false

        val profiles = intArrayOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
        )

        return profiles.any { profile ->
            connectedDevicesForProfile(bluetoothManager, profile).any { device ->
                matchesTarget(device, targetDeviceNameContains)
            }
        }
    }

    private fun connectedDevicesForProfile(
        bluetoothManager: BluetoothManager,
        profile: Int,
    ): List<BluetoothDevice> =
        try {
            bluetoothManager.getConnectedDevices(profile)
        } catch (error: SecurityException) {
            Log.w(TAG, "getConnectedDevices denied for profile=$profile", error)
            emptyList()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Profile $profile unavailable", error)
            emptyList()
        }

    fun matchesTarget(device: BluetoothDevice?, targetDeviceNameContains: String): Boolean {
        if (device == null || targetDeviceNameContains.isBlank()) {
            return false
        }
        val name = try {
            device.name
        } catch (error: SecurityException) {
            Log.w(TAG, "device.name denied", error)
            return false
        }
        return name?.contains(targetDeviceNameContains, ignoreCase = true) == true
    }

    fun connectionPhrase(targetDeviceNameContains: String, connected: Boolean): String =
        if (connected) {
            "$targetDeviceNameContains подключены"
        } else {
            "$targetDeviceNameContains отключены"
        }

    private const val TAG = "HeadsetConnectionHelper"
}
