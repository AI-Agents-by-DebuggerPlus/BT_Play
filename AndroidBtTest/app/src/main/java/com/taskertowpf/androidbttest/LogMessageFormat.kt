package com.taskertowpf.androidbttest

object LogMessageFormat {
    const val PREFIX = "[LOG:"

    fun buildContent(category: String, message: String): String {
        val cat = category.trim().ifEmpty { "App" }
        val text = message.trim()
        return "$PREFIX$cat] $text"
    }
}
