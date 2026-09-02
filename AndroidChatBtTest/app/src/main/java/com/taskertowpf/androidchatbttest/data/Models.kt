package com.taskertowpf.androidchatbttest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val senderName: String = "AndroidChatBtTest",
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

data class ActiveMediaSessionRow(
    val rank: Int,
    val packageName: String,
    val appLabel: String,
    val playbackState: String,
    val receivesButton: Boolean,
    val isSelf: Boolean,
    val isKnownCompetitor: Boolean,
    val competitorNote: String?,
)

data class ActiveSessionHistoryEntry(
    val atMillis: Long,
    val topPackage: String,
    val summary: String,
)

@Serializable
data class LocalLogEntry(
    val id: Long,
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
