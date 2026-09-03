package com.taskertowpf.androidchatbttest98.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

class BackupFolderStorage(
    private val context: Context,
) {
    data class WriteResult(
        val path: String,
        val alreadyExisted: Boolean,
    )

    fun hasWriteAccess(settings: AppSettings, kind: FileFolderKind = FileFolderKind.Incoming): Boolean {
        val treeUri = treeUri(settings, kind)
        if (treeUri.isNotBlank()) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            return root?.canWrite() == true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    fun hasReadAccess(settings: AppSettings): Boolean = hasWriteAccess(settings, FileFolderKind.Outgoing)

    fun needsLegacyStoragePermission(settings: AppSettings): Boolean {
        if (settings.fileOutgoingTreeUri.isNotBlank() && settings.fileIncomingTreeUri.isNotBlank()) {
            return false
        }
        return !hasWriteAccess(settings, FileFolderKind.Incoming) ||
            !hasReadAccess(settings)
    }

    fun needsAllFilesAccess(settings: AppSettings): Boolean {
        if (settings.fileOutgoingTreeUri.isNotBlank() && settings.fileIncomingTreeUri.isNotBlank()) {
            return false
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    }

    fun persistTreeUri(uri: Uri): String {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        return uri.toString()
    }

    fun folderDisplayName(treeUri: String): String {
        if (treeUri.isBlank()) {
            return ""
        }
        val uri = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(uri)
        return docId.substringAfter(':', docId)
    }

    fun listOutgoingFiles(settings: AppSettings): List<LocalFileItem> =
        listFiles(settings, FileFolderKind.Outgoing)

    fun listFiles(settings: AppSettings, kind: FileFolderKind): List<LocalFileItem> {
        val treeUri = treeUri(settings, kind)
        if (treeUri.isNotBlank()) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
            return root.listFiles()
                .filter { it.isFile }
                .map { file ->
                    LocalFileItem(
                        fileName = file.name.orEmpty(),
                        fullPath = file.uri.toString(),
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        isContentUri = true,
                    )
                }
                .sortedByDescending { it.lastModified }
        }

        val folder = legacyPath(settings, kind)
        return LocalFileList.list(folder)
    }

    fun resolveFileName(pathOrUri: String): String? {
        if (pathOrUri.startsWith("content://")) {
            return DocumentFile.fromSingleUri(context, Uri.parse(pathOrUri))?.name
        }
        val file = File(pathOrUri)
        return file.name.takeIf { file.isFile }
    }

    fun fileSize(pathOrUri: String): Long {
        if (pathOrUri.startsWith("content://")) {
            return DocumentFile.fromSingleUri(context, Uri.parse(pathOrUri))?.length() ?: 0L
        }
        return File(pathOrUri).length()
    }

    fun readBytes(pathOrUri: String): ByteArray {
        if (pathOrUri.startsWith("content://")) {
            val uri = Uri.parse(pathOrUri)
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось прочитать файл.")
        }
        return File(pathOrUri).readBytes()
    }

    fun writeIncomingFile(settings: AppSettings, fileName: String, bytes: ByteArray): WriteResult {
        val treeUri = treeUri(settings, FileFolderKind.Incoming)
        if (treeUri.isNotBlank()) {
            return writeViaSaf(treeUri, fileName, bytes)
        }
        return writeViaLegacyPath(settings, fileName, bytes, FileFolderKind.Incoming)
    }

    private fun writeViaSaf(treeUri: String, fileName: String, bytes: ByteArray): WriteResult {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("Нет доступа к выбранной папке. Выберите папку снова.")

        val existing = root.findFile(fileName)
        if (existing != null && existing.isFile && existing.length() > 0L) {
            return WriteResult(existing.uri.toString(), alreadyExisted = true)
        }

        val target = existing ?: root.createFile(guessMimeType(fileName), fileName)
            ?: error("Не удалось создать файл: $fileName")

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
            stream.write(bytes)
        } ?: error("Не удалось записать файл: $fileName")

        return WriteResult(target.uri.toString(), alreadyExisted = false)
    }

    private fun writeViaLegacyPath(
        settings: AppSettings,
        fileName: String,
        bytes: ByteArray,
        kind: FileFolderKind,
    ): WriteResult {
        if (!hasWriteAccess(settings, kind)) {
            error("Нет доступа к хранилищу. Выберите папку или выдайте разрешение.")
        }

        val folderPath = legacyPath(settings, kind)
        val folder = File(folderPath)
        if (!folder.exists() && !folder.mkdirs()) {
            error("Не удалось создать папку: $folderPath")
        }

        val destination = File(folder, fileName)
        if (destination.exists() && destination.length() > 0L) {
            return WriteResult(destination.absolutePath, alreadyExisted = true)
        }

        destination.writeBytes(bytes)
        return WriteResult(destination.absolutePath, alreadyExisted = false)
    }

    private fun treeUri(settings: AppSettings, kind: FileFolderKind): String =
        when (kind) {
            FileFolderKind.Outgoing -> settings.fileOutgoingTreeUri.trim()
            FileFolderKind.Incoming -> settings.fileIncomingTreeUri.trim()
        }

    private fun legacyPath(settings: AppSettings, kind: FileFolderKind): String =
        when (kind) {
            FileFolderKind.Outgoing -> settings.fileOutgoingFolder.trim().ifEmpty {
                FileTransferConstants.defaultOutgoingFolder()
            }
            FileFolderKind.Incoming -> settings.fileIncomingFolder.trim().ifEmpty {
                FileTransferConstants.defaultIncomingFolder()
            }
        }

    private fun guessMimeType(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "xml" -> "application/xml"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
}
