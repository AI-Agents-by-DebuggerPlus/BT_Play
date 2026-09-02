package com.taskertowpf.androidchatbttest

import android.app.Application
import com.taskertowpf.androidchatbttest.bluetooth.BluetoothInventory
import com.taskertowpf.androidchatbttest.data.LocalLogRepository
import com.taskertowpf.androidchatbttest.data.SettingsRepository
import com.taskertowpf.androidchatbttest.data.SupabaseRepository
import com.taskertowpf.androidchatbttest.headset.HeadsetButtonNotifier

class AndroidChatBtTestApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var localLogRepository: LocalLogRepository
        private set
    lateinit var supabaseRepository: SupabaseRepository
        private set
    lateinit var speechService: SpeechService
        private set
    lateinit var bluetoothInventory: BluetoothInventory
        private set
    lateinit var headsetButtonNotifier: HeadsetButtonNotifier
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        localLogRepository = LocalLogRepository()
        supabaseRepository = SupabaseRepository()
        speechService = SpeechService(this)
        bluetoothInventory = BluetoothInventory(this)
        bluetoothInventory.bindProfiles()
        headsetButtonNotifier = HeadsetButtonNotifier(this)
    }
}
