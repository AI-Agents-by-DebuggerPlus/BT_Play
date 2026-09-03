package com.taskertowpf.androidchatbttest98.data

import android.os.Environment
import java.io.File

object FileTransferConstants {
    const val CHAT_FILES_BUCKET = "chat-files"

    fun defaultOutgoingFolder(): String =
        File(Environment.getExternalStorageDirectory(), "Tasker/config/user Outcoming").absolutePath

    fun defaultIncomingFolder(): String =
        File(Environment.getExternalStorageDirectory(), "Tasker/config/user Incoming").absolutePath

    /** @deprecated use defaultOutgoingFolder */
    fun defaultBackupsFolder(): String = defaultOutgoingFolder()
}
