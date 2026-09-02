package com.taskertowpf.androidchatbttest.bluetooth

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

data class BluetoothDeviceRow(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val hfpConnected: Boolean,
    val a2dpConnected: Boolean,
    val leAudioConnected: Boolean,
    val audioRouted: Boolean,
    val hfpAudio: Boolean,
    val a2dpPlaying: Boolean,
    val majorClass: String,
    val source: String,
) {
    val connected: Boolean
        get() = hfpConnected || a2dpConnected || leAudioConnected || audioRouted

    val roleLabel: String = buildList {
        if (hfpConnected) add("HFP")
        if (a2dpConnected) add("A2DP")
        if (leAudioConnected) add("LE Audio")
        if (audioRouted && !hfpConnected && !a2dpConnected && !leAudioConnected) add("audio-route")
        if (!connected && bonded) add("bonded")
    }.joinToString(" + ").ifBlank { "—" }
}

data class BluetoothSnapshot(
    val adapterEnabled: Boolean,
    val adapterName: String,
    val permissionGranted: Boolean,
    val profilesReady: Boolean,
    val a2dpOn: Boolean,
    val scoOn: Boolean,
    val scoAvailable: Boolean,
    val audioMode: String,
    val outputDevices: List<String>,
    val devices: List<BluetoothDeviceRow>,
    val activeSummary: String,
    val scannedAtMillis: Long = System.currentTimeMillis(),
)

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

class BluetoothInventory(private val appContext: Context) {
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null

    @Volatile
    private var leAudioProxy: BluetoothProfile? = null

    fun bindProfiles() {
        val bt = adapter ?: return
        runCatching {
            bt.getProfileProxy(appContext, profileListener, BluetoothProfile.HEADSET)
            bt.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bt.getProfileProxy(appContext, profileListener, BluetoothProfile.LE_AUDIO)
            }
        }.onFailure { error ->
            Log.w(TAG, "getProfileProxy failed: ${error.message}")
        }
    }

    fun unbindProfiles() {
        val bt = adapter ?: return
        runCatching { headsetProxy?.let { bt.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        runCatching { a2dpProxy?.let { bt.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { leAudioProxy?.let { bt.closeProfileProxy(BluetoothProfile.LE_AUDIO, it) } }
        }
        headsetProxy = null
        a2dpProxy = null
        leAudioProxy = null
    }

    fun waitForProfiles(timeoutMs: Long = 1800L) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (headsetProxy != null || a2dpProxy != null || leAudioProxy != null) {
                return
            }
            Thread.sleep(50)
        }
    }

    fun snapshot(): BluetoothSnapshot {
        val permission = BluetoothPermissionHelper.hasConnectPermission(appContext)
        val bt = adapter
        val enabled = bt?.isEnabled == true
        val adapterName = if (permission) {
            runCatching { bt?.name }.getOrNull().orEmpty().ifBlank { "(без имени)" }
        } else {
            "(нет BLUETOOTH_CONNECT)"
        }

        val am = appContext.getSystemService(AudioManager::class.java)
        val a2dpOn = am?.isBluetoothA2dpOn == true
        val scoOn = am?.isBluetoothScoOn == true
        val scoAvailable = am?.isBluetoothScoAvailableOffCall == true
        val audioMode = when (am?.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> am?.mode?.toString() ?: "?"
        }
        val outputInfos = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val outputs = outputInfos.map { deviceLabel(it) }
        val btAudioOutputs = outputInfos.filter { isBluetoothAudioOutput(it.type) }
        val profilesReady = headsetProxy != null || a2dpProxy != null || leAudioProxy != null

        if (!permission || bt == null || !enabled) {
            val summary = when {
                !permission -> "Нет разрешения BLUETOOTH_CONNECT"
                bt == null -> "Bluetooth adapter недоступен"
                else -> "Bluetooth выключен"
            }
            return BluetoothSnapshot(
                adapterEnabled = enabled,
                adapterName = adapterName,
                permissionGranted = permission,
                profilesReady = profilesReady,
                a2dpOn = a2dpOn,
                scoOn = scoOn,
                scoAvailable = scoAvailable,
                audioMode = audioMode,
                outputDevices = outputs,
                devices = emptyList(),
                activeSummary = summary,
            )
        }

        val bonded = runCatching { bt.bondedDevices }.getOrDefault(emptySet())
        val hfpConnected = proxyConnected(headsetProxy)
        val a2dpConnected = proxyConnected(a2dpProxy)
        val leConnected = proxyConnected(leAudioProxy)
        val all = LinkedHashMap<String, BluetoothDevice>()
        fun putDevice(device: BluetoothDevice?) {
            val address = runCatching { device?.address }.getOrNull().orEmpty()
            if (device != null && address.isNotBlank()) {
                all[address] = device
            }
        }
        bonded.forEach(::putDevice)
        hfpConnected.forEach(::putDevice)
        a2dpConnected.forEach(::putDevice)
        leConnected.forEach(::putDevice)

        val headset = headsetProxy
        val a2dp = a2dpProxy
        val rows = all.values.map { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "(без имени)" }
            val address = runCatching { device.address }.getOrNull().orEmpty()
            val hfp = hfpConnected.any { it.address == address }
            val a2dpOk = a2dpConnected.any { it.address == address }
            val leOk = leConnected.any { it.address == address }
            val routed = btAudioOutputs.any { matchesAudioProduct(it, name) }
            val hfpAudio = hfp && headset?.isAudioConnected(device) == true
            val playing = a2dpOk && a2dp?.isA2dpPlaying(device) == true
            BluetoothDeviceRow(
                address = address,
                name = name,
                bonded = bonded.any { it.address == address },
                hfpConnected = hfp,
                a2dpConnected = a2dpOk,
                leAudioConnected = leOk,
                audioRouted = routed,
                hfpAudio = hfpAudio,
                a2dpPlaying = playing,
                majorClass = deviceMajorClass(device),
                source = "adapter",
            )
        }.toMutableList()

        btAudioOutputs.forEach { info ->
            val product = audioProductName(info)
            val already = rows.any { matchesName(it.name, product) || it.audioRouted }
            if (!already && product.isNotBlank()) {
                rows += BluetoothDeviceRow(
                    address = "audio:${info.id}",
                    name = product,
                    bonded = false,
                    hfpConnected = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    a2dpConnected = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    leAudioConnected = info.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    audioRouted = true,
                    hfpAudio = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    a2dpPlaying = false,
                    majorClass = "audio",
                    source = "audio-route",
                )
            }
        }

        val sorted = rows.sortedWith(
            compareByDescending<BluetoothDeviceRow> { it.connected }
                .thenByDescending { it.hfpAudio || it.a2dpPlaying }
                .thenBy { it.name.lowercase() },
        )

        val active = sorted.firstOrNull { it.connected }
        val summary = if (active == null) {
            buildString {
                append("Нет подключённых BT-устройств")
                if (!profilesReady) append(" (HFP/A2DP proxy ещё не готов)")
                if (a2dpOn) append(" · AudioManager A2DP ON")
                if (btAudioOutputs.isNotEmpty()) {
                    append(" · выходы: ${btAudioOutputs.joinToString { deviceLabel(it) }}")
                }
            }
        } else {
            buildString {
                append(active.name)
                append(" · ")
                append(active.roleLabel)
                append(" · ")
                append(
                    when {
                        active.hfpAudio -> "SCO/HFP audio активен"
                        active.a2dpPlaying -> "A2DP играет"
                        active.audioRouted -> "аудио-маршрут на гарнитуру"
                        active.leAudioConnected -> "LE Audio подключён"
                        a2dpOn && active.a2dpConnected -> "A2DP подключён"
                        else -> "подключено"
                    },
                )
                if (scoOn) append(" · SCO ON")
            }
        }

        return BluetoothSnapshot(
            adapterEnabled = true,
            adapterName = adapterName,
            permissionGranted = true,
            profilesReady = profilesReady,
            a2dpOn = a2dpOn,
            scoOn = scoOn,
            scoAvailable = scoAvailable,
            audioMode = audioMode,
            outputDevices = outputs,
            devices = sorted,
            activeSummary = summary,
        )
    }

    private fun proxyConnected(proxy: BluetoothProfile?): List<BluetoothDevice> {
        if (proxy == null) {
            return emptyList()
        }
        return try {
            proxy.connectedDevices
        } catch (error: SecurityException) {
            Log.w(TAG, "proxy.connectedDevices denied", error)
            emptyList()
        }
    }

    private fun deviceMajorClass(device: BluetoothDevice): String {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        return when (major) {
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> "phone"
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "computer"
            android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "wearable"
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "peripheral"
            else -> "other"
        }
    }

    private fun isBluetoothAudioOutput(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER

    private fun audioProductName(info: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.productName?.toString().orEmpty().trim()
        } else {
            ""
        }

    private fun matchesAudioProduct(info: AudioDeviceInfo, deviceName: String): Boolean {
        val product = audioProductName(info)
        return matchesName(deviceName, product)
    }

    private fun matchesName(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) {
            return false
        }
        return left.contains(right, ignoreCase = true) || right.contains(left, ignoreCase = true)
    }

    private fun deviceLabel(info: AudioDeviceInfo): String {
        val type = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble-speaker"
            else -> "type-${info.type}"
        }
        val name = audioProductName(info)
        return if (name.isBlank()) type else "$type ($name)"
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    profile == BluetoothProfile.LE_AUDIO
                ) {
                    leAudioProxy = proxy
                }
            }
            Log.i(TAG, "Profile connected: $profile")
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    profile == BluetoothProfile.LE_AUDIO
                ) {
                    leAudioProxy = null
                }
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothInventory"
    }
}
