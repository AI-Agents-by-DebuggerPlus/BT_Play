package com.taskertowpf.androidchatbttestv1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskertowpf.androidchatbttestv1.ui.InterceptMonitorScreen

/** Отдельное окно: кто перехватывает media-кнопки Bluetooth-гарнитуры. */
class InterceptMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: InterceptMonitorViewModel = viewModel()
                    InterceptMonitorScreen(
                        viewModel = vm,
                        onClose = { finish() },
                    )
                }
            }
        }
    }
}
