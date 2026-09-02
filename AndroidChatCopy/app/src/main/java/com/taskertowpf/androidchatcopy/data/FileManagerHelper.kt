package com.taskertowpf.androidchatcopy.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

object FileManagerHelper {
    fun openFile(context: Context, pathOrUri: String): Result<Unit> = runCatching {
        val uri = resolveUri(context, pathOrUri)
        val mime = guessMimeType(pathOrUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            error("Нет приложения для открытия файла")
        }

        context.startActivity(Intent.createChooser(intent, "Открыть файл"))
    }

    fun deleteFile(context: Context, pathOrUri: String): Result<Unit> = runCatching {
        if (pathOrUri.startsWith("content://")) {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(pathOrUri))
                ?: error("Файл не найден")
            if (!doc.delete()) {
                error("Не удалось удалить файл")
            }
            return@runCatching
        }

        val file = File(pathOrUri)
        if (!file.isFile || !file.delete()) {
            error("Не удалось удалить файл")
        }
    }

    private fun resolveUri(context: Context, pathOrUri: String): Uri {
        if (pathOrUri.startsWith("content://")) {
            return Uri.parse(pathOrUri)
        }

        val file = File(pathOrUri)
        if (!file.isFile) {
            error("Файл не найден: $pathOrUri")
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun guessMimeType(pathOrUri: String): String {
        val name = pathOrUri.substringAfterLast('/')
        return when (name.substringAfterLast('.', "").lowercase()) {
            "xml" -> "application/xml"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }
}
