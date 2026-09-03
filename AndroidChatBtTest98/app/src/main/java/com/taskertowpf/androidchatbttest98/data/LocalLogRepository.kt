package com.taskertowpf.androidchatbttest98.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter

class LocalLogRepository {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<LocalLogEntry>>(emptyList())
    val entries: StateFlow<List<LocalLogEntry>> = _entries.asStateFlow()

    suspend fun append(category: String, message: String, status: String) {
        mutex.withLock {
            val updated = (_entries.value + LocalLogEntry(
                timestampMillis = System.currentTimeMillis(),
                category = category,
                message = message,
                status = status,
            )).takeLast(MAX_ENTRIES)
            _entries.value = updated
        }
    }

    suspend fun logLocal(category: String, message: String) {
        append(category, message, "local")
    }

    suspend fun clear() {
        mutex.withLock { _entries.value = emptyList() }
    }

    fun formatLine(entry: LocalLogEntry): String {
        val time = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entry.timestampMillis))
        return "[$time] [${entry.category}] ${entry.message} (${entry.status})"
    }

    companion object {
        private const val MAX_ENTRIES = 300
    }
}
