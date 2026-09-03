package com.taskertowpf.androidchatbttest98.lesson

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedLessonMeta(
    val topicKey: String,
    val topic: String,
    val savedAtMillis: Long,
)

@Serializable
data class SavedLesson(
    val topic: String,
    val topicKey: String,
    val markdown: String,
    val savedAtMillis: Long,
)

@Serializable
private data class SavedLessonIndex(
    val lessons: List<SavedLessonMeta> = emptyList(),
)

class LessonStorageRepository(
    private val appContext: Context,
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lessonsDir: File
        get() = File(appContext.filesDir, "lessons").also { it.mkdirs() }

    suspend fun listMeta(): List<SavedLessonMeta> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().lessons.sortedByDescending { it.savedAtMillis } }
    }

    suspend fun findByTopic(topic: String): SavedLesson? = withContext(Dispatchers.IO) {
        val key = LessonTopicNormalizer.normalize(topic)
        if (key.isBlank()) return@withContext null
        mutex.withLock {
            val file = lessonFile(key)
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString<SavedLesson>(file.readText()) }.getOrNull()
        }
    }

    suspend fun load(topicKey: String): SavedLesson? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = lessonFile(topicKey)
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString<SavedLesson>(file.readText()) }.getOrNull()
        }
    }

    suspend fun save(topic: String, markdown: String) = withContext(Dispatchers.IO) {
        val trimmedTopic = topic.trim()
        val key = LessonTopicNormalizer.normalize(trimmedTopic)
        if (key.isBlank() || markdown.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val lesson = SavedLesson(
            topic = trimmedTopic,
            topicKey = key,
            markdown = markdown,
            savedAtMillis = now,
        )
        mutex.withLock {
            lessonFile(key).writeText(json.encodeToString(lesson))
            val index = readIndex()
            val updated = (index.lessons.filterNot { it.topicKey == key } + SavedLessonMeta(
                topicKey = key,
                topic = trimmedTopic,
                savedAtMillis = now,
            )).sortedByDescending { it.savedAtMillis }
            writeIndex(SavedLessonIndex(updated))
        }
    }

    private fun lessonFile(topicKey: String): File =
        File(lessonsDir, "$topicKey.json")

    private fun readIndex(): SavedLessonIndex {
        val file = File(lessonsDir, INDEX_FILE)
        if (!file.exists()) return SavedLessonIndex()
        return runCatching {
            json.decodeFromString<SavedLessonIndex>(file.readText())
        }.getOrDefault(SavedLessonIndex())
    }

    private fun writeIndex(index: SavedLessonIndex) {
        File(lessonsDir, INDEX_FILE).writeText(json.encodeToString(index))
    }

    companion object {
        private const val INDEX_FILE = "index.json"
    }
}
