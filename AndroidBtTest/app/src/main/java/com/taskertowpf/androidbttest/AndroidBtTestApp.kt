package com.taskertowpf.androidbttest

import android.app.Application
import com.taskertowpf.androidbttest.bluetooth.BluetoothInventory
import com.taskertowpf.androidbttest.data.LocalLogRepository
import com.taskertowpf.androidbttest.data.SettingsRepository
import com.taskertowpf.androidbttest.data.SupabaseRepository
import com.taskertowpf.androidbttest.headset.HeadsetButtonNotifier

class AndroidBtTestApp : Application() {
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
