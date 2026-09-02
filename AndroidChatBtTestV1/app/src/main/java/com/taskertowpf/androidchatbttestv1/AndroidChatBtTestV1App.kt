package com.taskertowpf.androidchatbttestv1

import android.app.Application
import com.taskertowpf.androidchatbttestv1.bluetooth.BluetoothInventory
import com.taskertowpf.androidchatbttestv1.data.LocalLogRepository
import com.taskertowpf.androidchatbttestv1.data.SettingsRepository
import com.taskertowpf.androidchatbttestv1.data.SupabaseRepository
import com.taskertowpf.androidchatbttestv1.headset.HeadsetButtonNotifier

class AndroidChatBtTestV1App : Application() {
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
