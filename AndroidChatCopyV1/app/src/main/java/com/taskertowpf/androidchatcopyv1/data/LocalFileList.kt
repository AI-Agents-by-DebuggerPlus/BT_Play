package com.taskertowpf.androidchatcopyv1.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalFileItem(
    val fileName: String,
    val fullPath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isContentUri: Boolean = false,
) {
    val displayLabel: String
        get() {
            val size = when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
                else -> String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
            }
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastModified))
            return "$fileName  ($size, $date)"
        }
}

object LocalFileList {
    fun list(folderPath: String): List<LocalFileItem> {
        val folder = File(folderPath)
        if (!folder.isDirectory) {
            return emptyList()
        }

        return folder.listFiles()
            ?.filter { it.isFile }
            ?.map { file ->
                LocalFileItem(
                    fileName = file.name,
                    fullPath = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.lastModified }
            .orEmpty()
    }
}
