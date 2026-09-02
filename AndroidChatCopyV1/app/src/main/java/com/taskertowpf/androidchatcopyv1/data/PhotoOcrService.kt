package com.taskertowpf.androidchatcopyv1.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Сжатие JPEG с камеры и (опционально) OCR через OpenRouter Vision.
 * Кнопка камеры в чате шлёт фото в Supabase, без OCR.
 */
class PhotoOcrService(
    context: Context,
    private val openRouterService: OpenRouterService,
) {
    private val appContext = context.applicationContext

    suspend fun recognizeText(
        imageUri: Uri,
        apiKey: String,
        model: String,
    ): String {
        Log.i(TAG, "recognizeText begin uri=$imageUri")
        val jpegBytes = compressToJpeg(imageUri)
            ?: error("Не удалось прочитать фото")
        Log.i(TAG, "OCR image bytes=${jpegBytes.size}")
        return openRouterService.extractTextFromImage(
            apiKey = apiKey,
            model = model,
            jpegBytes = jpegBytes,
        ).getOrThrow()
    }

    fun compressToJpeg(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (!decodeBounds(uri, bounds)) {
                Log.e(TAG, "bounds decode failed for $uri")
                return null
            }
            Log.i(
                TAG,
                "image bounds ${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType}",
            )

            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (maxSide / sample > MAX_SIDE_PX) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = decodeBitmap(uri, opts)
            if (bitmap == null) {
                Log.e(TAG, "bitmap decode returned null sample=$sample")
                return null
            }

            val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_SIDE_PX) {
                val scale = MAX_SIDE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                scaled.recycle()
                out.toByteArray()
            }
        } catch (error: Exception) {
            Log.e(TAG, "compress failed: ${error.message}", error)
            null
        }
    }

    private fun decodeBounds(uri: Uri, bounds: BitmapFactory.Options): Boolean {
        resolveLocalFile(uri)?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) return true
        }
        openImageStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }
        return false
    }

    private fun decodeBitmap(uri: Uri, opts: BitmapFactory.Options): Bitmap? {
        resolveLocalFile(uri)?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let { return it }
        }
        openImageStream(uri)?.use { stream ->
            return BitmapFactory.decodeStream(stream, null, opts)
        }
        return null
    }

    /**
     * FileProvider: openInputStream иногда null, а AFD/файл в cache — ок.
     */
    private fun openImageStream(uri: Uri): InputStream? {
        runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                return ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
        }.onFailure { Log.w(TAG, "openFileDescriptor failed: ${it.message}") }

        runCatching {
            appContext.contentResolver.openInputStream(uri)?.let { return it }
        }.onFailure { Log.w(TAG, "openInputStream failed: ${it.message}") }

        resolveLocalFile(uri)?.let { file ->
            Log.i(TAG, "fallback FileInputStream ${file.absolutePath} size=${file.length()}")
            return FileInputStream(file)
        }
        return null
    }

    private fun resolveLocalFile(uri: Uri): File? {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val cached = File(appContext.cacheDir, name)
        if (cached.isFile && cached.length() > 0L) {
            Log.i(TAG, "resolved cache file ${cached.absolutePath} size=${cached.length()}")
            return cached
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return File(path).takeIf { it.isFile && it.length() > 0L }
        }
        return null
    }

    fun deleteQuietly(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            }
        }
        runCatching {
            val name = uri.lastPathSegment ?: return
            File(appContext.cacheDir, name).takeIf { it.exists() }?.delete()
        }
        runCatching {
            appContext.contentResolver.delete(uri, null, null)
        }
    }

    companion object {
        private const val TAG = "PhotoOcrService"
        private const val MAX_SIDE_PX = 1600
        private const val JPEG_QUALITY = 82

        fun toDataUrl(jpegBytes: ByteArray): String {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            return "data:image/jpeg;base64,$b64"
        }
    }
}
