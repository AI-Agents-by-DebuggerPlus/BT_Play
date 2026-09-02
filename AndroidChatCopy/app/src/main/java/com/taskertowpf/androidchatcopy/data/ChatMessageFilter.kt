package com.taskertowpf.androidchatcopy.data

object ChatMessageFilter {
    fun shouldShowInChat(message: ChatMessage, settings: AppSettings): Boolean {
        if (message.id.isBlank()) {
            return false
        }
        if (com.taskertowpf.androidchatcopy.LogMessageFormat.isLogContent(message.content)) {
            return false
        }
        if (com.taskertowpf.androidchatcopy.hrt.HrtProtocol.isRemoteTerminal(message)
            || com.taskertowpf.androidchatcopy.hrt.HrtProtocol.isHwtContent(message.content)
        ) {
            return false
        }

        val inbox = settings.incomingRecipientName.trim().ifBlank { "Android" }
        val mine = settings.senderName.trim().ifBlank { "AndroidChatCopy" }
        val recipient = message.recipientName.trim()
        val sender = message.senderName.trim()

        // Own outbound (e.g. AndroidChat → WpfChat / Hermes)
        if (sender.equals(mine, ignoreCase = true)) {
            return true
        }
        // Addressed to this device inbox (WpfChat/Hermes → Android)
        if (recipient.equals(inbox, ignoreCase = true)) {
            return true
        }
        return false
    }

    fun shouldAutoSpeak(message: ChatMessage, settings: AppSettings): Boolean {
        if (!settings.enableIncomingTts) {
            return false
        }
        if (!shouldShowInChat(message, settings)) {
            return false
        }
        val inbox = settings.incomingRecipientName.trim().ifBlank { "Android" }
        val mine = settings.senderName.trim().ifBlank { "AndroidChatCopy" }
        if (message.senderName.trim().equals(mine, ignoreCase = true)) {
            return false
        }
        return message.recipientName.trim().equals(inbox, ignoreCase = true)
    }
}
