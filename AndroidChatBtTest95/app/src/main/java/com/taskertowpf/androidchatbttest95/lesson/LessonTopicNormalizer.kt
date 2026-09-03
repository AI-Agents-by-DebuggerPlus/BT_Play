package com.taskertowpf.androidchatbttest95.lesson

import java.util.Locale

object LessonTopicNormalizer {
    fun normalize(topic: String): String =
        topic.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), " ")
}
