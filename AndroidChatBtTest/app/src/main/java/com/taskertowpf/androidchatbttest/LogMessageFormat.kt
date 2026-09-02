package com.taskertowpf.androidchatbttest

object LogMessageFormat {
    const val PREFIX = "[LOG:"

    fun buildContent(category: String, message: String): String {
        val cat = category.trim().ifEmpty { "App" }
        val text = message.trim()
        return "$PREFIX$cat] $text"
    }
}
