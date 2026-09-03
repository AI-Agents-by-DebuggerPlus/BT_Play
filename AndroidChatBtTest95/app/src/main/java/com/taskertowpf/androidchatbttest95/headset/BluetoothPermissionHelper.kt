package com.taskertowpf.androidchatbttest95.headset

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothPermissionHelper {
    fun needsConnectPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun hasConnectPermission(context: Context): Boolean {
        if (!needsConnectPermission()) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
