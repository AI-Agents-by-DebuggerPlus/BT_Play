package com.taskertowpf.androidchatbttestv1.headset

import android.service.notification.NotificationListenerService

/**
 * Ничего не делает с уведомлениями — нужен только чтобы получить право
 * на MediaSessionManager.getActiveSessions() через Notification Access.
 */
class NoOpNotificationListener : NotificationListenerService()
