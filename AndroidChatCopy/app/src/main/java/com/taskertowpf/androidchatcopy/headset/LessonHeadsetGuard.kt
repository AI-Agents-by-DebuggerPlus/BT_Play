package com.taskertowpf.androidchatcopy.headset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * В режиме урока следит за подключением целевой гарнитуры (Pixel Buds Pro 2).
 * При отключении — цикл BT off/on для переподключения.
 */
class LessonHeadsetGuard(
    private val context: Context,
    private val bluetoothToggle: BluetoothToggleHelper = BluetoothToggleHelper(context),
) {
    suspend fun ensureConnected(
        deviceNameHint: String = HeadsetConnectionConstants.DEFAULT_DEVICE_NAME_HINT,
        onStatus: (String) -> Unit = {},
    ): Boolean {
        if (deviceNameHint.isBlank()) return true
        if (HeadsetConnectionHelper.isTargetHeadsetConnected(context, deviceNameHint)) {
            Log.i(TAG, "ensureConnected: '$deviceNameHint' уже подключена, BT cycle НЕ требуется")
            return true
        }
        Log.w(TAG, "ensureConnected: '$deviceNameHint' НЕ подключена → инициирую cycleBluetooth()")
        onStatus("Гарнитура не подключена — переподключение BT…")
        when (val result = bluetoothToggle.cycleBluetooth()) {
            is BluetoothToggleHelper.CycleResult.Success -> {
                Log.i(TAG, "cycleBluetooth() успешно, жду $RECONNECT_SETTLE_MS мс перед проверкой")
                delay(RECONNECT_SETTLE_MS)
                val ok = HeadsetConnectionHelper.isTargetHeadsetConnected(context, deviceNameHint)
                onStatus(
                    if (ok) "$deviceNameHint подключены" else "$deviceNameHint всё ещё не видны",
                )
                Log.i(TAG, "BT cycle done, connected=$ok")
                return ok
            }
            is BluetoothToggleHelper.CycleResult.Failed -> {
                onStatus("BT: ${result.reason}")
                Log.w(TAG, "BT cycle failed: ${result.reason}")
                return false
            }
        }
    }

    companion object {
        private const val TAG = "LessonHeadsetGuard"
        private const val RECONNECT_SETTLE_MS = 4_000L
    }
}
