# Задача для агента: создать AndroidChatBtTest95 — >95% копию AndroidChatCopyV1

## Свежие данные, которые меняют диагноз

После включения диагностики в `AndroidChatBtTestV1` (манифест + `ActiveSessionsHelper`,
см. `Docs/Tasks/ChatGPT/cursor_prompt_bt_play_fix.md` и коммит
"Enable ActiveSessions diagnostics in AndroidChatBtTestV1...") получены новые факты:

**Скриншот "Монитор перехвата BT" (`Images/AndroidScreenShots/AndroidChatBtTestV1/Screenshot_20260902_114437.png`):**
`AndroidChatBtTestV1` — **единственная и приоритетная** (`#1`, `Лидер:
com.taskertowpf.androidchatbttestv1`, `Всего сессий: 1`) активная MediaSession в
системе. Никакого конфликта с `AndroidChatCopyV1` или другими "старыми" приложениями
на устройстве в момент теста не было.

**Лог `Logs/AndroidChatBtTestV1/newsender.log` и второй скриншот
(`Screenshot_20260902_114639.png`, вкладка "Логи"):** за сессию тестирования в логе
есть только `[Headset] MEDIA_PLAY via ui-simulate` — то есть кнопка, нажатая **в
интерфейсе приложения** (симуляция). Ни одной записи вида `Callback onPlay()` /
`onMediaButtonEvent keyCode=...` — то есть от **реальной физической кнопки на
гарнитуре** — в логе нет, хотя судя по скриншоту сканирования Bluetooth гарнитура
(`Pixel Buds Pro 2`) подключена и передаёт аудио (`HFP + A2DP`, `A2DP играет`).

### Вывод

Прежняя гипотеза "конфликт MediaSession с другим приложением" **не подтвердилась**:
приложение — единственный и приоритетный обработчик по данным самой ОС, но реальное
физическое нажатие кнопки на гарнитуре всё равно не долетает до
`MediaSessionCompat.Callback`. Значит проблема не в приоритете сессии, а где-то в
цепочке "физическая кнопка → Bluetooth-стек → AVRCP → `ACTION_MEDIA_BUTTON` →
`MediaSessionCompat` конкретно этого приложения" — и она специфична для
`AndroidChatBtTestV1` (переписанный "baseline"-модуль), а не воспроизводится в
`AndroidChatCopyV1`, где, по словам пользователя, реальная кнопка гарнитуры работает.

Дальнейшее "докручивание" урезанного baseline-модуля признаётся неэффективным — велик
риск, что расхождение прячется в какой-то детали, которую мы пока не нашли (порядок
инициализации, состав `AndroidManifest.xml`, поведение `Application.onCreate()`,
экран Tests с изолированным режимом и т.д.), а переписанный код по построению уже
разошёлся с оригиналом сильнее, чем казалось.

## Новая стратегия

Вместо дальнейшей правки `AndroidChatBtTestV1` — создать **новый модуль
`AndroidChatBtTest95`**, который является **честной копией `AndroidChatCopyV1` не
менее чем на 95% кода** (манифест, порядок инициализации, `headset/*` без изменений
логики), и только:

- вырезает функциональность, заведомо не влияющую на приём BT Play (урок-генератор,
  OCR, HRT, файловый трансфер, Gemini-оверлей, полноценный чат-релей и т.д.);
- заменяет package/applicationId на `com.taskertowpf.androidchatbttest95`, чтобы
  ставился параллельно с остальными приложениями без конфликтов;
- убирает избыточное логирование, не относящееся к приёму кнопки;
- добавляет минимальный, но однозначный лог/UI-индикатор, который отличает **реальное
  аппаратное** нажатие от **симулированного** (см. ниже) — именно этого не хватило в
  прошлый раз, чтобы сразу увидеть проблему.

Цель эксперимента: если BT Play заработает в `AndroidChatBtTest95` (99% идентичном
CopyV1 коде) — значит расхождение было в одной из "переписанных" частей
`AndroidChatBtTestV1`, и можно точечно найти diff между `BtTest95` и `BtTestV1`. Если
не заработает — значит дело не в коде конкретного приложения, а во внешнем факторе
(состояние Bluetooth-стека, парность устройства, конкретно с каким приложением
гарнитура "спарена" по AVRCP на уровне ОС/прошивки, и т.п.).

## Что перенести (обязательно, без изменений логики — только rename пакета)

Скопировать как есть из `AndroidChatCopyV1/app/src/main/java/com/taskertowpf/androidchatcopyv1/`:

- `AndroidChatApp.kt` — **важно сохранить порядок**: `HeadsetConnectionMonitor.ensureStarted(...)`
  вызывается прямо в `Application.onCreate()`, а не лениво из ViewModel (в
  `AndroidChatBtTestV1` этого вызова нет вообще — это одно из расхождений, которое
  нужно воспроизвести и проверить).
- `headset/HeadsetMonitorService.kt` — FGS + `MediaSessionCompat`, без изменений.
- `headset/HeadsetButtonNotifier.kt` — включая debounce-логику и разделение
  `btPlayTestIsolation` / обычного режима.
- `headset/HeadsetButtonNames.kt`
- `headset/HeadsetConnectionMonitor.kt`, `HeadsetConnectionReceiver.kt`,
  `HeadsetConnectionHelper.kt`, `HeadsetConnectionConstants.kt` — динамический ACL
  BroadcastReceiver (в `AndroidChatBtTestV1` этой части тоже нет, есть только более
  простой `BluetoothAclReceiver` — тоже воспроизвести оригинал 1:1).
- `headset/BluetoothPermissionHelper.kt`, `BluetoothToggleHelper.kt`
- `headset/LessonHeadsetGuard.kt` — можно оставить как есть (не мешает, но и не
  критичен); если решите вырезать — не трогайте остальное ради чистоты диффа.
- `bridge/HeadsetPlayHandler.kt` — реальная реакция на Play (пауза/резюм TTS, запуск
  STT). Без него нечем визуально/аудиально подтвердить, что кнопка реально нажата —
  оставить как есть, это часть "рабочего эталона".
- `SpeechService.kt` — используется `HeadsetPlayHandler`/`HeadsetConnectionHelper` для
  голосовых реплик ("Play", "Pause", "подключены/отключены"). Оставить полностью —
  это часть проверяемого поведения.
- `MainActivity.kt` — без изменений (permissions launcher и т.п.).
- `MainViewModel.kt` — **перенести логику запуска `HeadsetMonitorService` как есть**:
  сервис стартует через `updateHeadsetMonitor()` (вызывается из `saveSettings()` и
  `connect()`) и через `enterHeadsetIsolation()` при открытии экрана Tests — **не**
  безусловно в `init{}`, как сделано в `AndroidChatBtTestV1`. Это тоже расхождение,
  которое нужно воспроизвести точно.
- `ui/TestsScreen.kt` — экран с изолированным режимом теста BT Play
  (`btPlayTestIsolation`, `isolatedBtPlayHandler`) — судя по всему, именно через этот
  экран пользователь проверял "BT Test работает" в CopyV1. Перенести как есть,
  максимально близко к оригинальной вёрстке/логике.
- `AndroidManifest.xml` — скопировать **весь** манифест `AndroidChatCopyV1` без
  собственных доработок (permissions, `<queries>`, декларация `HeadsetMonitorService`,
  FileProvider). Никакого `NoOpNotificationListener`/`InterceptMonitorActivity` —
  в CopyV1 их не было, и по новым данным они не являются причиной, только диагностикой.
- `build.gradle.kts` — идентичные `compileSdk`/`minSdk`/`targetSdk`/версии зависимостей
  (35/26/35, те же androidx/media, kotlinx-coroutines и т.д.), поменять только
  `namespace`/`applicationId`/`versionName` (например `1.0.0-bttest95`).

## Что можно вырезать (не относится к приёму кнопки)

- `lesson/*`, `ui/LessonGeneratorScreen.kt`, `ui/LessonPager.kt` — генератор уроков.
- `data/PhotoOcrService.kt`, `data/OpenRouterService.kt` — OCR/LLM-фичи.
- `hrt/*`, `ui/HrtScreen.kt` — HRT-контроллер.
- `ui/FileTransferScreen.kt`, `data/FileManagerHelper.kt`, `data/LocalFileList.kt`,
  `data/BackupFolderStorage.kt`, `data/FileTransferConstants.kt`,
  `data/FileMessageFormat.kt` — файловый трансфер.
- `ui/GeminiAssistantScreen.kt`, `ui/GeminiOverlayPanel.kt` — Gemini-оверлей.
- `voice/VoiceInputService.kt` — **оставить**, если вырезаете `HeadsetPlayHandler`'ом
  используется для STT после Play; если убираете реальный чат-релей полностью, можно
  заменить голосовой ввод заглушкой, но тогда учтите, что это тоже меняет проверяемое
  поведение — по умолчанию не трогать.
- `data/ChatRepository.kt`, `data/SupabaseRepository.kt`, `ui/MainScreen.kt` (чат) —
  можно сильно упростить/урезать до минимального экрана со статусом BT + счётчиком
  Play-нажатий + логом, чат-функциональность как таковая для эксперимента не нужна.
  **Важно**: не убирать сам вызов `chatRepository.ensureConnectedForRelay` /
  `sendMessage` из `HeadsetPlayHandler`, если он реально нужен для того, чтобы
  воспроизвести штатное поведение "Play → отправка в чат" как в CopyV1 — либо
  оставить упрощённый no-op репозиторий, который просто логирует вызов.
- `ui/LogScreen.kt` — можно оставить в упрощённом виде (см. ниже про логирование).

## Логирование: что оставить, что убрать

**Оставить только Headset-категорию, и сделать источник события однозначным:**

```kotlin
// в HeadsetMonitorService.kt — при реальном аппаратном событии
notifier.notifyButton(label, source = "hardware-mediaButtonEvent")

// в HeadsetButtonNotifier.notifyButton(...) — логировать с явной пометкой
app.localLogRepository.logLocal(
    "Headset",
    "🔵 HARDWARE: $label via $source"   // для реальной кнопки
    // или
    "⚪ SIMULATED: $label via $source"  // для UI-кнопки теста
)
```

Убрать/не переносить избыточные логи из вырезанных фич (OCR, урок, HRT, файлы,
Supabase realtime, детальные TTS speak-plan логи по каждому шагу). Оставить:
- `[App] Application started`
- `[Bluetooth] ACL connected/disconnected` (из `HeadsetConnectionReceiver`)
- `[Headset] ...` — с пометкой HARDWARE/SIMULATED, как выше
- Ошибки инициализации (permissions denied, FGS start failed) — по одному логу на
  случай, без повторов/спама.

## Как проверять результат

1. Собрать `AndroidChatBtTest95` и установить рядом с `AndroidChatCopyV1` и
   `AndroidChatBtTestV1` (три разных `applicationId` — конфликтов не будет).
2. Дать все runtime-разрешения (микрофон, Bluetooth, уведомления).
3. Открыть экран Tests (`TestsScreen`) — включить изолированный режим.
4. Нажать физическую кнопку Play на гарнитуре.
5. Проверить лог: должна появиться строка `🔵 HARDWARE: ...` — если её нет, как и в
   `AndroidChatBtTestV1`, проблема не в коде приложения (общий сбой конкретно с этой
   гарнитурой/сопряжением/прошивкой). Если строка появилась — значит один из перенесённых
   элементов (порядок инициализации в `AndroidChatApp`, декларация манифеста,
   `HeadsetConnectionMonitor`, момент старта `HeadsetMonitorService` из
   `updateHeadsetMonitor()`/Tests-экрана, а не из `init{}`) и есть настоящая причина —
   дальше сравнить эти же элементы точечно с `AndroidChatBtTestV1` и перенести различие
   туда.

## Критерии готовности

- [ ] Новый модуль `AndroidChatBtTest95/` создан и подключён в `settings.gradle.kts`
      корня репозитория (если там общий корень) либо как отдельный Gradle-проект —
      как принято в остальных модулях репозитория.
- [ ] `applicationId` = `com.taskertowpf.androidchatbttest95`, ставится параллельно с
      остальными.
- [ ] Diff по количеству изменённых строк в перенесённых Kotlin/XML-файлах
      относительно `AndroidChatCopyV1` — не более ~5% (переименование пакета не
      считается).
- [ ] Есть явное разделение HARDWARE/SIMULATED в логах Headset-событий.
- [ ] Экран Tests с изолированным режимом перенесён и работает.
- [ ] Проведён реальный тест с физической кнопкой на гарнитуре, результат
      (появилась/не появилась строка HARDWARE) зафиксирован в
      `Logs/AndroidChatBtTest95/` и по возможности скриншотом, как для BtTestV1.
