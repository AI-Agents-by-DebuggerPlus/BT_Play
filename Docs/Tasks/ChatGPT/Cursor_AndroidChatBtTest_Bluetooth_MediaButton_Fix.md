# Cursor Prompt — Fix Bluetooth Media Button Interception in AndroidChatBtTest

## Цель

Исправить `AndroidChatBtTest` так, чтобы Bluetooth-кнопка **Play / Play-Pause** стабильно доходила до `HeadsetMonitorService` и отображалась в BT Test UI.

Рабочий эталон:

- `AndroidChatCopy`
- `AndroidChatCopy/app/src/main/java/com/taskertowpf/androidchatcopy/headset/HeadsetMonitorService.kt`

Проблемный проект:

- `AndroidChatBtTest/`

Главная гипотеза: проблема не в базовой конфигурации `MediaSessionCompat`, а в дополнительной логике `MediaSessionManager`, `ActiveSessions`, `NotificationListenerService`, watchdog/reassert и диагностике.

Нужно сначала сделать **минимальный A/B baseline**, максимально идентичный Bluetooth-части `AndroidChatCopy`.

---

## 1. Целевой pipeline

```text
Bluetooth headset
      ↓
Android system media button
      ↓
MediaSessionCompat
      ↓
HeadsetMonitorService.Callback
      ↓
HeadsetButtonNotifier.notifyButton()
      ↓
BT Test UI
```

Для основного перехвата НЕ использовать:

```text
MediaSessionManager.getActiveSessions()
ActiveSessionsChangedListener
NotificationListenerService
```

---

## 2. Эталон AndroidChatCopy

Ключевая реализация `AndroidChatCopy`:

```kotlin
private fun attachMediaSession() {
    if (mediaSession != null) {
        Log.d(TAG, "attachMediaSession: уже существует, пропуск")
        return
    }

    val notifier = HeadsetButtonNotifier.get(this)

    val session = MediaSessionCompat(
        this,
        "AndroidChatCopyHeadset"
    ).apply {

        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )

        setCallback(
            object : MediaSessionCompat.Callback() {

                override fun onPlay() {
                    notifier.notifyButton("MEDIA_PLAY")
                }

                override fun onPause() {
                    notifier.notifyButton("MEDIA_PAUSE")
                }

                override fun onSkipToNext() {
                    notifier.notifyButton("MEDIA_NEXT")
                }

                override fun onSkipToPrevious() {
                    notifier.notifyButton("MEDIA_PREVIOUS")
                }

                override fun onStop() {
                    notifier.notifyButton("MEDIA_STOP")
                }

                override fun onMediaButtonEvent(
                    mediaButtonIntent: Intent?
                ): Boolean {

                    val event =
                        extractKeyEvent(mediaButtonIntent)
                            ?: return super.onMediaButtonEvent(
                                mediaButtonIntent
                            )

                    val label =
                        HeadsetButtonNames.fromKeyCode(event.keyCode)

                    if (
                        label != null &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0
                    ) {
                        notifier.notifyButton(label)
                        return true
                    }

                    return super.onMediaButtonEvent(
                        mediaButtonIntent
                    )
                }
            },
        )

        setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    0,
                    0f,
                )
                .build(),
        )

        isActive = true
    }

    mediaSession = session
}
```

Это считать **baseline implementation**.

---

## 3. Что сейчас отличается в AndroidChatBtTest

Текущий `HeadsetMonitorService.kt` содержит ту же базовую MediaSession, но дополнительно:

```kotlin
startActiveSessionsWatch()
```

и использует:

```text
MediaSessionManager
MediaSessionManager.OnActiveSessionsChangedListener
ActiveSessionsHelper
NoOpNotificationListener
heartbeatRunnable
reassertSession()
dumpDiagnostics()
publishActiveSessions()
```

Для первого эксперимента эти механизмы нужно отключить от Bluetooth interception.

---

## 4. Патч HeadsetMonitorService.kt

### 4.1 Удалить импорты, ставшие ненужными

После упрощения удалить unused imports, связанные с:

```kotlin
ComponentName
AudioManager
MediaSessionManager
Handler
Looper
```

и другие imports, которые станут неиспользуемыми.

Не удалять необходимые imports для:

```kotlin
Service
Context
Intent
Build
IBinder
MediaSessionCompat
PlaybackStateCompat
KeyEvent
Notification
PendingIntent
NotificationCompat
ServiceCompat
```

---

## 5. Упростить поля класса

Удалить поля, относящиеся к:

- heartbeat;
- ActiveSessions;
- MediaSessionManager;
- reassert;
- playback diagnostics.

В частности, не должны использоваться:

```kotlin
playbackStateCode
lastHeartbeatFingerprint
lastKeyEventCount
keyEventCount
handler
activeSessionsListener
```

`scope` можно оставить, если он нужен для `logDiag()`.

---

## 6. onCreate()

Заменить текущую реализацию на:

```kotlin
override fun onCreate() {
    super.onCreate()

    logDiag(
        "Service onCreate instance=$instanceId sdk=${Build.VERSION.SDK_INT}"
    )

    createNotificationChannel()
    startForegroundWithNotification()
    attachMediaSession()
}
```

НЕ вызывать:

```kotlin
startActiveSessionsWatch()
```

НЕ запускать:

```kotlin
heartbeatRunnable
```

НЕ выполнять ActiveSessions diagnostics.

---

## 7. onStartCommand()

Использовать простой вариант:

```kotlin
override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
): Int {

    if (mediaSession?.isActive != true) {
        attachMediaSession()
    }

    return START_STICKY
}
```

Для baseline НЕ использовать:

```kotlin
ACTION_REASSERT
ACTION_DIAGNOSE
reassertSession()
dumpDiagnostics()
```

---

## 8. onDestroy()

Использовать:

```kotlin
override fun onDestroy() {
    mediaSession?.isActive = false
    mediaSession?.release()
    mediaSession = null
    super.onDestroy()
}
```

Если существует независимый `HeadsetConnectionMonitor`, не удалять его без необходимости. Он не должен влиять на MediaSession interception.

---

## 8.1. Голосовое оповещение при подключении Bluetooth

При событии подключения Bluetooth-устройства (ACL connected / аналог в `BluetoothAclReceiver` или `HeadsetConnectionMonitor`) голосовое оповещение **обязано** начинаться с **названия приложения**, которое говорит (то же имя, что озвучивается при готовности приложения, например `Android Chat Bt Test`).

Текущий паттерн (недостаточно):

```text
{deviceName} подключены
```

Требуемый паттерн при **подключении**:

```text
{appSpokenName}. {deviceName} подключены
```

Пример:

```text
Android Chat Bt Test. Pixel Buds подключены
```

Правила:

- `{appSpokenName}` — человекочитаемое имя приложения для TTS (не package name).
- Использовать одно и то же имя, что уже говорится при старте / ready (например `"Android Chat Bt Test"`).
- При **отключении** достаточно оставить текущий формат `{deviceName} отключены` (без обязательного имени приложения), если иное не потребуется отдельно.
- Язык фразы — как сейчас (`speakRussian` для «подключены» / «отключены»); имя приложения произносить так, как оно уже озвучивается в ready-cue (допустимо английское имя в начале русской фразы).
- Не менять логику MediaSession interception ради этой правки: только текст TTS при ACL connected.

Место правки в `AndroidChatBtTest`:

- `MainViewModel.registerAcl()` (или общий helper формирования connection-phrase).

---

## 9. attachMediaSession() — ключевой патч

Сделать реализацию максимально похожей на `AndroidChatCopy`, сохранив только диагностические `logDiag()`:

```kotlin
private fun attachMediaSession() {
    if (mediaSession != null) {
        Log.d(TAG, "attachMediaSession: уже существует, пропуск")
        return
    }

    val notifier = HeadsetButtonNotifier.get(this)

    val session = MediaSessionCompat(
        this,
        "AndroidChatBtTestHeadset"
    ).apply {

        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )

        setCallback(
            object : MediaSessionCompat.Callback() {

                override fun onPlay() {
                    logDiag("Callback onPlay()")
                    notifier.notifyButton(
                        "MEDIA_PLAY",
                        source = "callback-onPlay"
                    )
                }

                override fun onPause() {
                    logDiag("Callback onPause()")
                    notifier.notifyButton(
                        "MEDIA_PAUSE",
                        source = "callback-onPause"
                    )
                }

                override fun onSkipToNext() {
                    logDiag("Callback onSkipToNext()")
                    notifier.notifyButton(
                        "MEDIA_NEXT",
                        source = "callback-onNext"
                    )
                }

                override fun onSkipToPrevious() {
                    logDiag("Callback onSkipToPrevious()")
                    notifier.notifyButton(
                        "MEDIA_PREVIOUS",
                        source = "callback-onPrev"
                    )
                }

                override fun onStop() {
                    logDiag("Callback onStop()")
                    notifier.notifyButton(
                        "MEDIA_STOP",
                        source = "callback-onStop"
                    )
                }

                override fun onMediaButtonEvent(
                    mediaButtonIntent: Intent?
                ): Boolean {

                    val event = extractKeyEvent(mediaButtonIntent)

                    if (event == null) {
                        logDiag(
                            "onMediaButtonEvent: no KeyEvent, defer to super"
                        )
                        return super.onMediaButtonEvent(
                            mediaButtonIntent
                        )
                    }

                    val known =
                        HeadsetButtonNames.fromKeyCode(event.keyCode)

                    logDiag(
                        "onMediaButtonEvent " +
                            "keyCode=${event.keyCode} " +
                            "label=${known ?: "UNKNOWN"} " +
                            "action=${event.action} " +
                            "repeat=${event.repeatCount}"
                    )

                    if (
                        known != null &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0
                    ) {
                        notifier.notifyButton(
                            known,
                            source = "mediaButtonEvent"
                        )
                        return true
                    }

                    return super.onMediaButtonEvent(
                        mediaButtonIntent
                    )
                }
            }
        )

        setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    0,
                    0f,
                )
                .build()
        )

        isActive = true
    }

    mediaSession = session

    logDiag(
        "MediaSession attached active=${session.isActive}"
    )
}
```

Не добавлять AudioFocus.

Не добавлять MediaButtonReceiver.

Не менять `STATE_PAUSED`.

---

## 10. extractKeyEvent()

Сохранить:

```kotlin
private fun extractKeyEvent(intent: Intent?): KeyEvent? {
    if (intent == null) return null

    return if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        intent.getParcelableExtra(
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java
        )
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(
            Intent.EXTRA_KEY_EVENT
        )
    }
}
```

---

## 11. AndroidManifest.xml

Файл:

```text
AndroidChatBtTest/app/src/main/AndroidManifest.xml
```

Оставить:

```xml
<service
    android:name=".headset.HeadsetMonitorService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

Временно удалить регистрацию:

```xml
<service
    android:name=".headset.NoOpNotificationListener"
    android:exported="true"
    android:label="AndroidChatBtTest diagnostics"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action
            android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

Не добавлять:

```xml
androidx.media.session.MediaButtonReceiver
```

---

## 12. ActiveSessionsHelper.kt

Не удалять файл сразу.

Проверить его usages.

Для baseline:

```text
HeadsetMonitorService
    X → ActiveSessionsHelper
```

Если UI использует `ActiveSessionsHelper` только для диагностической таблицы, можно сохранить UI, но он не должен влиять на получение media-button.

---

## 13. NoOpNotificationListener.kt

Не удалять физически без проверки usages.

Но для baseline он не должен быть зарегистрирован в Manifest и не должен быть необходим для Bluetooth interception.

---

## 14. HeadsetButtonNotifier.kt

Не переписывать без необходимости.

Сохранить:

```kotlin
fun notifyButton(
    buttonLabel: String,
    source: String = "native"
)
```

и:

```kotlin
onButton?.invoke(label, source)
```

Debounce:

```kotlin
private const val DEBOUNCE_MS = 500L
```

оставить.

Первое событие не должно отбрасываться.

---

## 15. Диагностика

Оставить только диагностику, которая не вмешивается в MediaSession.

Минимальные сообщения:

```text
Service onCreate
MediaSession attached active=true
Callback onPlay()
Callback onPause()
Callback onSkipToNext()
Callback onSkipToPrevious()
Callback onStop()
onMediaButtonEvent keyCode=...
```

Это позволит определить, какой путь используется:

```text
onMediaButtonEvent
```

или:

```text
onPlay()
```

---

# 16. A/B Test

После патча:

### Test A

1. Закрыть `AndroidChatCopy`.
2. Закрыть YouTube и другие media apps.
3. Запустить `AndroidChatBtTest`.
4. Открыть BT Test.
5. Нажать Play на Bluetooth headset.
6. Нажать Play/Pause несколько раз.

Ожидается:

```text
Bluetooth headset
   ↓
AndroidChatBtTest MediaSession
   ↓
onPlay() / onMediaButtonEvent()
   ↓
HeadsetButtonNotifier
   ↓
BT Test UI
```

---

# 17. Test B — конкурирующая MediaSession

После успешного Test A:

1. Запустить YouTube.
2. Сделать его MediaSession активной.
3. Запустить `AndroidChatBtTest`.
4. Проверить Play.

Не исправлять этот сценарий через `getActiveSessions()` до получения чистого baseline.

---

# 18. Если baseline заработал

Возвращать дополнительную функциональность строго по одной части:

```text
1. чистая MediaSession
       ↓ TEST

2. + NoOpNotificationListener
       ↓ TEST

3. + ActiveSessions snapshot
       ↓ TEST

4. + OnActiveSessionsChangedListener
       ↓ TEST

5. + diagnostics
       ↓ TEST

6. + watchdog
       ↓ TEST

7. + reassert
       ↓ TEST
```

После каждого шага снова проверить Play.

Если после конкретного шага Play перестал работать — зафиксировать этот шаг как вероятный источник проблемы.

---

# 19. Что НЕ делать

Не делать без доказательства необходимости:

### AudioFocus

Не использовать:

```kotlin
requestAudioFocus(...)
```

### MediaButtonReceiver

Не добавлять:

```xml
androidx.media.session.MediaButtonReceiver
```

### STATE_PLAYING

Не менять:

```kotlin
STATE_PAUSED
```

на:

```kotlin
STATE_PLAYING
```

### ActiveSession selection

Не использовать:

```kotlin
if (index == 0) ...
```

как механизм перехвата.

### Постоянный reassert

Не выполнять регулярно:

```kotlin
session.isActive = true
session.setPlaybackState(...)
```

### Notification Access

Bluetooth interception не должен требовать Notification Access.

---

# 20. Build verification

Проверить compilation:

```text
AndroidChatBtTest
```

Исправить:

- compile errors;
- unresolved references;
- unused imports;
- lifecycle problems.

Особенно проверить отсутствие зависимостей `HeadsetMonitorService` от:

```text
activeSessionsListener
startActiveSessionsWatch()
stopActiveSessionsWatch()
publishActiveSessions()
ActiveSessionsHelper
NoOpNotificationListener
```

---

# 21. Критерий успеха

Baseline успешен, если `AndroidChatBtTest` получает Bluetooth Play без:

```text
Notification Access
ActiveSessionsHelper
MediaSessionManager
MediaButtonReceiver
AudioFocus
watchdog
reassert
```

и событие отображается в существующем BT Test UI.

Дополнительно: при ACL connected TTS говорит `{appSpokenName}. {deviceName} подключены` (см. §8.1).

---

# 22. Финальный отчёт Cursor

После реализации не писать просто «готово».

Вывести:

1. Какие файлы изменены.
2. Какие части удалены/отключены.
3. Точная конфигурация `MediaSession`.
4. Есть ли `NotificationListener` в Manifest.
5. Есть ли `MediaButtonReceiver`.
6. Есть ли AudioFocus.
7. Какой путь media-button события используется.
8. Результат compilation.
9. Какие тесты нужно выполнить на физическом Pixel/гарнитуре.

Если Bluetooth на физическом устройстве не проверялся, явно написать:

```text
Код изменён, но фактический Bluetooth interception на устройстве не подтверждён.
```

---

# 23. Итоговая архитектура baseline

```text
Foreground Service
        ↓
MediaSessionCompat
        ↓
FLAG_HANDLES_MEDIA_BUTTONS
        +
FLAG_HANDLES_TRANSPORT_CONTROLS
        ↓
STATE_PAUSED
        ↓
isActive = true
        ↓
Callback
        ↓
HeadsetButtonNotifier
        ↓
BT Test UI
```

Сначала воспроизвести именно эту архитектуру в `AndroidChatBtTest`.

Только после подтверждения работоспособности возвращать дополнительные диагностические механизмы.

**Не изменять `AndroidChatCopy`.**
