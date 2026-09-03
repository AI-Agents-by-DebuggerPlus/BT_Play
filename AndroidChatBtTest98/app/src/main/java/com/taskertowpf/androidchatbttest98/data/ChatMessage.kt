package com.taskertowpf.androidchatbttest98.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String = "",
    @SerialName("sender_id")
    val senderId: String = "",
    @SerialName("sender_name")
    val senderName: String = "",
    @SerialName("recipient_name")
    val recipientName: String = "",
    val content: String = "",
    @SerialName("created_at")
    val createdAt: String = "",
) {
    val routeLabel: String
        get() = "$senderName → $recipientName"
}
