package com.taskertowpf.androidchat.headset

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay

class BluetoothToggleHelper(private val context: Context) {

    fun isEnabled(): Boolean? {
        val adapter = adapter() ?: return null
        return try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            Log.w(TAG, "isEnabled denied", error)
            null
        }
    }

    fun disableBluetooth(): PowerResult = setEnabled(enabled = false)

    fun enableBluetooth(): PowerResult = setEnabled(enabled = true)

    suspend fun cycleBluetooth(offDelayMs: Long = 1500L): CycleResult {
        when (val off = disableBluetooth()) {
            is PowerResult.Failed -> return CycleResult.Failed("выключение: ${off.reason}")
            is PowerResult.Changed -> Unit
            is PowerResult.Unchanged -> Unit
        }
        delay(offDelayMs)
        return when (val on = enableBluetooth()) {
            is PowerResult.Failed -> CycleResult.Failed("включение: ${on.reason}")
            is PowerResult.Changed -> CycleResult.Success
            is PowerResult.Unchanged -> CycleResult.Success
        }
    }

    @Suppress("DEPRECATION")
    private fun setEnabled(enabled: Boolean): PowerResult {
        val adapter = adapter() ?: return PowerResult.Failed("Bluetooth недоступен")

        return try {
            val currentlyEnabled = adapter.isEnabled
            if (currentlyEnabled == enabled) {
                return PowerResult.Unchanged(enabled = enabled)
            }

            val ok = if (enabled) {
                adapter.enable()
            } else {
                adapter.disable()
            }

            if (ok) {
                PowerResult.Changed(enabled = enabled)
            } else if (enabled) {
                requestEnableViaSystemDialog()
            } else {
                PowerResult.Failed("Система не разрешила выключить Bluetooth")
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "setEnabled($enabled) denied", error)
            PowerResult.Failed("Нет разрешения BLUETOOTH_CONNECT")
        }
    }

    private fun requestEnableViaSystemDialog(): PowerResult {
        return try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            PowerResult.Changed(enabled = true)
        } catch (error: Exception) {
            Log.w(TAG, "ACTION_REQUEST_ENABLE failed", error)
            PowerResult.Failed("Не удалось включить Bluetooth")
        }
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    sealed interface PowerResult {
        data class Changed(val enabled: Boolean) : PowerResult
        data class Unchanged(val enabled: Boolean) : PowerResult
        data class Failed(val reason: String) : PowerResult
    }

    sealed interface CycleResult {
        data object Success : CycleResult
        data class Failed(val reason: String) : CycleResult
    }

    companion object {
        private const val TAG = "BluetoothToggleHelper"
    }
}
