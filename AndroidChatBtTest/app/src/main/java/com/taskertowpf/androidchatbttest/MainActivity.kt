package com.taskertowpf.androidchatbttest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import kotlin.system.exitProcess
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskertowpf.androidchatbttest.ui.MainScreen

class MainActivity : ComponentActivity() {
    private var viewModelRef: MainViewModel? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val bt = result[Manifest.permission.BLUETOOTH_CONNECT]
        if (bt != null) {
            viewModelRef?.onBluetoothPermissionResult(bt)
        }
        if (bt == false) {
            Toast.makeText(this, "Нужен Nearby devices / Bluetooth", Toast.LENGTH_LONG).show()
        }
        val notif = result[Manifest.permission.POST_NOTIFICATIONS]
        if (notif != null) {
            viewModelRef?.onNotificationPermissionResult(notif)
        }
        val mic = result[Manifest.permission.RECORD_AUDIO]
        if (mic != null) {
            viewModelRef?.onRecordAudioPermissionResult(mic)
        }
        if (mic == false) {
            Toast.makeText(this, "Нужен доступ к микрофону (как в AndroidChat)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    LaunchedEffect(vm) {
                        viewModelRef = vm
                        vm.bindAppExit {
                            finishAffinity()
                            Process.killProcess(Process.myPid())
                            exitProcess(0)
                        }
                        requestRuntimePermissions()
                    }
                    MainScreen(vm)
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }
}
