package com.taskertowpf.androidchatbttestv1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskertowpf.androidchatbttestv1.data.ActiveMediaSessionRow
import com.taskertowpf.androidchatbttestv1.data.ActiveSessionHistoryEntry
import com.taskertowpf.androidchatbttestv1.headset.ActiveSessionsHelper
import com.taskertowpf.androidchatbttestv1.headset.HeadsetMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class InterceptMonitorUiState(
    val sessions: List<ActiveMediaSessionRow> = emptyList(),
    val history: List<ActiveSessionHistoryEntry> = emptyList(),
    val notificationAccessHint: String = "",
    val statusText: String = "",
    val isOwnTop: Boolean = false,
    val topPackage: String = "",
)

class InterceptMonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndroidChatBtTestV1App
    private var lastTopPackage: String? = null

    private val _uiState = MutableStateFlow(InterceptMonitorUiState())
    val uiState: StateFlow<InterceptMonitorUiState> = _uiState.asStateFlow()

    init {
        app.headsetButtonNotifier.onActiveSessionRowsChanged = { rows ->
            applySessions(rows, source = "live")
        }
        refreshNow(source = "open")
        HeadsetMonitorService.ensureRunning(getApplication())
    }

    fun refreshNow(source: String = "manual") {
        viewModelScope.launch {
            val rows = ActiveSessionsHelper.snapshot(getApplication())
            applySessions(rows, source = source)
            if (rows.isEmpty()) {
                _uiState.update {
                    it.copy(
                        notificationAccessHint =
                            "Baseline V1: NotificationListener отключён — ActiveSessions недоступны",
                        statusText = "Нет данных ActiveSessions ($source)",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        notificationAccessHint = "",
                        statusText = "Обновлено: ${rows.size} сессий ($source)",
                    )
                }
            }
        }
    }

    fun openNotificationAccessSettings() {
        _uiState.update {
            it.copy(statusText = "Baseline V1: Notification Access не требуется")
        }
    }

    fun reassertSelf() {
        HeadsetMonitorService.ensureRunning(getApplication())
        _uiState.update { it.copy(statusText = "ensureRunning (baseline)") }
    }

    override fun onCleared() {
        if (app.headsetButtonNotifier.onActiveSessionRowsChanged != null) {
            app.headsetButtonNotifier.onActiveSessionRowsChanged = null
        }
        super.onCleared()
    }

    private fun applySessions(rows: List<ActiveMediaSessionRow>, source: String) {
        val top = rows.firstOrNull()?.packageName.orEmpty()
        val ownTop = rows.firstOrNull()?.isSelf == true
        val historyEntry = if (top.isNotBlank() && top != lastTopPackage) {
            lastTopPackage = top
            ActiveSessionHistoryEntry(
                atMillis = System.currentTimeMillis(),
                topPackage = top,
                summary = rows.joinToString { it.packageName },
            )
        } else {
            null
        }
        _uiState.update { state ->
            state.copy(
                sessions = rows,
                isOwnTop = ownTop,
                topPackage = top,
                history = if (historyEntry != null) {
                    (listOf(historyEntry) + state.history).take(30)
                } else {
                    state.history
                },
                statusText = if (rows.isEmpty()) {
                    "Пусто ($source)"
                } else {
                    "Топ: $top" + if (ownTop) " (мы)" else " (чужой)"
                },
            )
        }
    }

    companion object {
        fun formatTime(millis: Long): String =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}
