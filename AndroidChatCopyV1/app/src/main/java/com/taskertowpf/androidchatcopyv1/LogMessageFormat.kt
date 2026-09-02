package com.taskertowpf.androidchatcopyv1

object LogMessageFormat {
    const val PREFIX = "[LOG:"

    fun buildContent(category: String, message: String): String {
        val cat = category.trim().ifEmpty { "App" }
        val text = message.trim()
        return "$PREFIX$cat] $text"
    }

    fun isLogContent(content: String): Boolean =
        content.trimStart().startsWith(PREFIX)
}
