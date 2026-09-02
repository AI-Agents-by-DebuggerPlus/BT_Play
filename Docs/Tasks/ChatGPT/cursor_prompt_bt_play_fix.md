# Задача для агента: диагностика и фикс перехвата BT Play в AndroidChatBtTestV1

Репозиторий: `AI-Agents-by-DebuggerPlus/BT_Play`
Рабочий модуль: `AndroidChatBtTestV1/` (applicationId `com.taskertowpf.androidchatbttestv1`)
Модуль для сравнения: `AndroidChatCopyV1/` (applicationId `com.taskertowpf.androidchatcopyv1`)

## Контекст

В `AndroidChatCopyV1` кнопка гарнитуры "BT Play" ловится (через `MediaSessionCompat` в
`HeadsetMonitorService`, foreground-service типа `mediaPlayback`). В `AndroidChatBtTestV1`
(специально сделан как "baseline"-копия того же механизма, см. комментарий в
`headset/HeadsetMonitorService.kt`) кнопка НЕ ловится, даже если `AndroidChatCopyV1`
принудительно остановлен через Настройки Android.

Логика `MediaSessionCompat` в обоих модулях практически идентична — проблема не в
обработчике кнопки, а в том, что **система отдаёт приоритет чужой активной
MediaSession**, и мы не можем сейчас это доказать, потому что встроенный диагностический
экран в `AndroidChatBtTestV1` сломан/отключён.

## Найденные факты (уже проверено)

1. `AndroidChatBtTestV1/app/src/main/AndroidManifest.xml` **не содержит** `<service>`
   для `headset/NoOpNotificationListener.kt`, хотя класс существует в коде и нужен для
   `MediaSessionManager.getActiveSessions()`.
2. `InterceptMonitorViewModel.kt` сознательно не запрашивает Notification Access и не
   вызывает реальную проверку — статус жёстко захардкожен как
   `"Baseline V1: NotificationListener отключён — ActiveSessions недоступны"`.
3. `headset/ActiveSessionsHelper.kt` уже содержит карту `knownCompetitors` — известных
   пакетов, которые "перехватывают BT Play": `com.taskertowpf.androidchatcopy` (БЕЗ
   суффикса V1), `com.taskertowpf.androidchat`, `com.taskertowpf.androidbttest`. Обрати
   внимание: `com.taskertowpf.androidchatcopyv1` в этом списке нет — значит раньше
   конфликт фиксировали именно со старыми не-V1 версиями приложений из этого же
   репозитория (`AndroidChat`, `AndroidChatCopy`, `AndroidBtTest`, `AndroidChatBtTest`).
4. Оба модуля идентичны по `compileSdk`/`targetSdk`/`minSdk` (35/35/26) и версии
   `androidx.media`, так что дело не в конфигурации сборки.

## Что нужно сделать

### 1. Зарегистрировать NotificationListenerService в манифесте

В `AndroidChatBtTestV1/app/src/main/AndroidManifest.xml` внутри `<application>` добавить:

```xml
<service
    android:name=".headset.NoOpNotificationListener"
    android:exported="false"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

### 2. Включить реальную проверку ActiveSessions

В `InterceptMonitorViewModel.kt`:
- убрать хардкод `"Baseline V1: NotificationListener отключён..."`;
- реально вызывать `ActiveSessionsHelper.snapshot(context)`;
- если `NotificationListenerService` для приложения не включён пользователем в
  системных настройках — показывать понятную подсказку и кнопку, которая открывает
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.

В `MainViewModel.kt` метод `openNotificationAccessSettings()` сейчас тоже
захардкожен ("Baseline V1: Notification Access не нужен для BT Play") — привести к
реальному запросу доступа аналогично.

### 3. Добавить свой пакет в диагностику как "self", а не только конкурентов

Проверить, что `com.taskertowpf.androidchatcopyv1` тоже присутствует в
`ActiveSessionsHelper.knownCompetitors` (сейчас отсутствует) — добавить с пометкой,
аналогичной остальным записям, чтобы диагностика ловила и его тоже:

```kotlin
"com.taskertowpf.androidchatcopyv1" to "AndroidChatCopyV1 — текущий рабочий эталон MediaSession",
```

### 4. Проверить сборку и (если возможно) прогнать на подключённом устройстве

```bash
cd AndroidChatBtTestV1
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Затем:
1. Открыть приложение → выдать разрешение Notification Access, когда попросит.
2. Открыть экран "Монитор перехвата BT" (`InterceptMonitorActivity`).
3. Нажать Play на гарнитуре.
4. Посмотреть, какой `packageName` оказался в топе (`rank == 1`, `receivesButton == true`).

Если топ — не `com.taskertowpf.androidchatbttestv1`, значит вопрос решён: реальный
"перехватчик" найден по имени пакета, и дальше нужно проверить, установлено ли это
приложение на устройстве (Настройки → Приложения), и принудительно остановить/удалить
именно его — не `AndroidChatCopyV1`.

### 5. (Опционально) Добавить сборку через GitHub Actions

Если локальной Android-среды нет, добавить `.github/workflows/build.yml`, который
собирает `assembleDebug` для `AndroidChatBtTestV1` и кладёт APK в артефакты workflow
(на самостоятельно захостенном раннере есть доступ к `dl.google.com` и
`services.gradle.org`, чего нет в изолированных средах без прямого доступа в интернет).

## Критерии готовности

- [ ] Манифест содержит корректно объявленный `NotificationListenerService`.
- [ ] Экран "Монитор перехвата BT" реально показывает список активных MediaSession
      с именами пакетов (а не заглушку).
- [ ] Понятно (по логам/экрану), какой именно пакет перехватывает кнопку Play —
      подтверждено экспериментально, а не предположением.
- [ ] Список `knownCompetitors` включает все родственные приложения из репозитория,
      включая `androidchatcopyv1`.
