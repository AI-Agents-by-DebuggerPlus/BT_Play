package com.taskertowpf.androidchatcopy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Process
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.system.exitProcess
import android.speech.tts.TextToSpeech
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskertowpf.androidchatcopy.ui.MainScreen
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private var viewModelRef: MainViewModel? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { viewModelRef?.onBackupFolderPicked(it) }
    }

    private val outgoingFilePickerLauncher = registerForActivityResult(
        OpenDocumentWithInitialUri(),
    ) { uri ->
        uri?.let { viewModelRef?.onOutgoingFilePicked(it) }
    }

    private val readStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModelRef?.onReadStoragePermissionResult(granted)
    }

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        (application as AndroidChatApp).onRecordAudioPermissionResult(granted)
        if (!granted) {
            Toast.makeText(this, "Нужен доступ к микрофону для голоса", Toast.LENGTH_LONG).show()
        }
    }

    private val installTtsDataLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModelRef?.onTtsInstallFinished()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    LaunchedEffect(vm) {
                        viewModelRef = vm
                        (application as AndroidChatApp).bindRecordAudioPermissionRequester {
                            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        vm.bindLessonActivity(
                            onLandscape = { lessonActive ->
                                requestedOrientation = if (lessonActive) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            },
                            onFullscreen = { fullscreen ->
                                val controller = window.insetsController
                                if (fullscreen) {
                                    controller?.hide(WindowInsets.Type.systemBars())
                                    controller?.systemBarsBehavior =
                                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                } else {
                                    controller?.show(WindowInsets.Type.systemBars())
                                }
                            },
                        )
                        vm.bindAppExit {
                            finishAffinity()
                            Process.killProcess(Process.myPid())
                            exitProcess(0)
                        }
                        vm.bindStorageLaunchers(
                            pickFolder = { folderPickerLauncher.launch(null) },
                            pickOutgoingFile = { initialTreeUri ->
                                outgoingFilePickerLauncher.launch(
                                    arrayOf("*/*") to initialTreeUri?.let(Uri::parse),
                                )
                            },
                            requestReadStorage = {
                                readStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            },
                            requestAllFilesAccess = { openAllFilesAccessSettings() },
                            installTtsData = { launchGoogleTtsVoiceInstall() },
                        )
                    }
                    MainScreen(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModelRef?.onStorageAccessMaybeGranted()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    /**
     * Голоса Google TTS ставятся в системный движок (не в APK приложения).
     * После установки список в AndroidChat обновляется.
     */
    private fun launchGoogleTtsVoiceInstall() {
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            setPackage(SpeechService.GOOGLE_TTS_ENGINE)
        }
        val canInstall = install.resolveActivity(packageManager) != null
        if (canInstall) {
            installTtsDataLauncher.launch(install)
            return
        }

        val settings = Intent("com.android.settings.TTS_SETTINGS")
        if (settings.resolveActivity(packageManager) != null) {
            startActivity(settings)
            Toast.makeText(
                this,
                "Откройте Google TTS → установить голосовые данные (en/ru)",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${SpeechService.GOOGLE_TTS_ENGINE}"),
        )
        runCatching { startActivity(market) }
            .onFailure {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://play.google.com/store/apps/details?id=${SpeechService.GOOGLE_TTS_ENGINE}",
                        ),
                    ),
                )
            }
        Toast.makeText(
            this,
            "Установите/обновите Google TTS, затем голосовые пакеты en и ru",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private class OpenDocumentWithInitialUri : ActivityResultContract<Pair<Array<String>, Uri?>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<Array<String>, Uri?>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input.first)
            input.second?.let { initialUri ->
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}
