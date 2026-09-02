package com.taskertowpf.androidchatcopy.lesson

import java.util.Locale

object LessonTopicNormalizer {
    fun normalize(topic: String): String =
        topic.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), " ")
}
