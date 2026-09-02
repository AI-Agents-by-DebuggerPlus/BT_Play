package com.taskertowpf.androidchatbttest.headset

import android.service.notification.NotificationListenerService

/**
 * Ничего не делает с уведомлениями — нужен только чтобы получить право
 * на MediaSessionManager.getActiveSessions() через Notification Access.
 */
class NoOpNotificationListener : NotificationListenerService()
