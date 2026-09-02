package com.taskertowpf.androidbttest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val senderName: String = "AndroidBtTest",
    val recipientName: String = "WpfChat",
    val useAnonymousAuth: Boolean = true,
)

@Serializable
data class MessageInsert(
    @SerialName("sender_id")
    val senderId: String,
    @SerialName("sender_name")
    val senderName: String,
    @SerialName("recipient_name")
    val recipientName: String,
    val content: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class LocalLogEntry(
    val timestampMillis: Long,
    val category: String,
    val message: String,
    val status: String,
) {
    fun shouldSkipManualSupabaseUpload(skipDuplicates: Boolean): Boolean {
        if (!skipDuplicates) {
            return false
        }
        return status == "sent"
    }
}
