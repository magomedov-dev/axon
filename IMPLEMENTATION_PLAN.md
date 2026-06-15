# IMPLEMENTATION_PLAN.md — Axon UI Automation Agent (APK)

Stateless UI-агент автоматизации для Android на базе `AccessibilityService` +
WebSocket (Java-WebSocket) + JSON-RPC. Альтернатива UIAutomator2 для массового
управления парком устройств по USB (`adb forward`).

## Зафиксированные решения

- **Язык/рантайм:** Kotlin + kotlinx.coroutines.
- **minSdk = 30**, targetSdk — актуальный (35/36 на момент сборки).
- **applicationId:** `com.axon.agent`.
- **Транспорт:** Java-WebSocket (`org.java_websocket`), server-режим, `0.0.0.0:9008`.
- **Протокол:** JSON-RPC поверх WS. Сериализация — **kotlinx.serialization**.
- **Heartbeat:** app-level через JSON-RPC метод `ping` → `{ "result": { "pong": true, "ts": <ms> } }`.
- **Каркас Gradle собираем с нуля** (директория пустая).
- ПК-клиент в этом проекте НЕ реализуется.

## Главный архитектурный инвариант (нарушать нельзя)

**APK БЕЗ СОСТОЯНИЯ.** `AccessibilityNodeInfo` не кэшируется и не переживает
RPC-вызов. Каждый вызов стартует со свежего `getRootInActiveWindow()`. `nodeId`
валиден только внутри одного дампа. Единственные допустимые «капли состояния»:
(1) булев флаг `eventStream` на соединение, (2) дебаунс-аккумулятор потока событий,
(3) сквозной счётчик `screen`. Ретраи/ожидания/навигация — на стороне ПК.

## Структура проекта (ориентир)

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/
  build.gradle.kts
  src/main/
    AndroidManifest.xml
    res/xml/accessibility_service_config.xml
    res/values/strings.xml
    res/layout/activity_status.xml
    java/com/axon/agent/
      AutomationAccessibilityService.kt   # сервис + точка входа, владелец scope и сервера
      server/
        WsServer.kt                        # Java-WebSocket сервер, lifecycle, connections
        ConnectionState.kt                 # per-connection: eventStream flag, write mutex
        FrameWriter.kt                      # сериализованная запись (text + binary), Mutex
        JsonRpcDispatcher.kt               # parse → route → reply, единый формат ошибок
      rpc/
        Messages.kt                        # @Serializable модели request/response/event/error
        ErrorCodes.kt                      # константы кодов ошибок
        MethodRouter.kt                    # method name → handler
      handlers/
        DumpHandler.kt
        GestureHandler.kt
        NodeActionHandler.kt
        GlobalActionHandler.kt
        ScreenshotHandler.kt
        EventStreamHandler.kt
        PingHandler.kt
      tree/
        NodeModel.kt                       # @Serializable узел + bounds + center
        TreeWalker.kt                      # обход getRootInActiveWindow, maxDepth, compress
        NodeFinder.kt                      # поиск по by/value/index для nodeAction
      events/
        AccessibilityEventHub.kt           # onAccessibilityEvent, debounce, screen counter
      core/
        TreeDispatcher.kt                  # единый single-thread dispatcher для дерева
        ScreenCounter.kt                   # атомарный счётчик состояния экрана
      ui/
        StatusActivity.kt                  # включение accessibility, статус сервера/сокета
```

## Маппинг-таблицы (по одной, без дублирования)

- `nodeAction`: строка → `AccessibilityNodeInfo.AccessibilityAction` / `performAction`.
- `globalAction`: строка → `AccessibilityService.GLOBAL_ACTION_*`.

## Тулчейн и сборка (самодостаточный проект)

Весь инструментарий ставится **локально в `./.tooling/`** (требование: лёгкий доступ,
независимость от хоста; системный JDK 26 несовместим с AGP):

- `.tooling/jdk` — Temurin JDK 17 · `.tooling/gradle` — Gradle 8.10.2 ·
  `.tooling/android-sdk` — platform android-35, build-tools 35.0.0.
- `scripts/setup.sh` — установка тулчейна, `local.properties`, генерация `./gradlew`. Идемпотентно.
- `scripts/build.sh <gradle-task>` — сборка через локальный JDK/SDK (хост не трогается).

## Тестирование (две петли)

Цель проекта — лёгкий и **быстрый** аналог UIAutomator2, поэтому регрессии ловим
автоматически на каждом этапе. Тестовое устройство уже подключено по USB.

1. **JVM unit-тесты** (`app/src/test/`, JUnit) — чистая логика без устройства:
   JSON-RPC (де)сериализация, таблицы action→константа, математика bounds/center,
   кодирование 4-байтного заголовка бинарного фрейма, парсинг params. Запуск:
   `scripts/test.sh --unit`.
2. **E2E на устройстве** (`tests/e2e/`, Python stdlib) — поднимают реальный тракт:
   build → install → включение accessibility через `adb shell settings` (с
   сохранением чужих сервисов) → `adb forward` → WS/JSON-RPC и проверки.
   Клиент — `tests/e2e/axon_client.py` (минимальный WebSocket на чистой stdlib,
   без pip; разбирает response/event/binary). Раннер: `scripts/test.sh`
   (всё) или `scripts/test.sh --e2e stageN` (один этап).

Каждый этап ниже добавляет свой файл `tests/e2e/stageN_*.py`; критерий «готово»
этапа включает прохождение его E2E-теста. Вспомогалки: `scripts/device.sh`
(enable/disable/forward/status/logcat), `tests/e2e/_adb.py`.

**Замечание по тестовому устройству:** Redmi Note 7 (lavender), Android 13 (API 33),
arm64-v8a. На нём уже включён сторонний accessibility-агент `com.reflex.deviceagent` —
наш сервис добавляется в список, не затирая его; возможный конфликт за порт 9008
проверяется на Этапе 1.

---

# Этапы

Легенда статуса: ⬜ не начат · 🟨 в работе · ✅ готово.

---

## Этап 0 — Каркас Gradle + сервис-заглушка ✅

**Цель:** собирается APK, ставится на устройство, `AutomationAccessibilityService`
виден и включается в системных настройках Accessibility. Сетки/RPC ещё нет.

**Файлы/классы:**
- `settings.gradle.kts`, корневой `build.gradle.kts`, `gradle/libs.versions.toml`
  (AGP, Kotlin, coroutines, kotlinx.serialization, Java-WebSocket).
- `app/build.gradle.kts` (applicationId `com.axon.agent`, minSdk 30, плагин
  serialization, зависимости).
- `AndroidManifest.xml` — регистрация сервиса с
  `android.permission.BIND_ACCESSIBILITY_SERVICE`, intent-filter
  `AccessibilityService`, метаданные конфига; `StatusActivity` как launcher.
- `res/xml/accessibility_service_config.xml` —
  `canRetrieveWindowContent=true`, `canTakeScreenshot=true`,
  `canPerformGestures=true`, типы событий
  `typeWindowStateChanged|typeWindowContentChanged|typeNotificationStateChanged`,
  `flagDefault|flagReportViewIds`.
- `AutomationAccessibilityService.kt` — минимум: `onServiceConnected`,
  `onAccessibilityEvent` (пустой), `onInterrupt`, `onDestroy`; логирование статуса.
- `StatusActivity.kt` + layout — текст «сервис включён/выключен» + кнопка перехода
  в `ACTION_ACCESSIBILITY_SETTINGS`.

- Тулчейн + тестовая инфраструктура: `scripts/setup.sh`, `scripts/build.sh`,
  `scripts/device.sh`, `scripts/test.sh`, `tests/e2e/axon_client.py`,
  `tests/e2e/_adb.py`, `tests/e2e/stage0_smoke.py`, JVM-тест `SanityTest.kt`.

**Готово:** APK собирается и ставится; сервис виден в Accessibility-настройках;
включается через `adb shell settings` и привязывается системой; проходят
`stage0_smoke` (установлен / включён / bound) и JVM unit-тесты.

**Проверка:** один раз `scripts/setup.sh`, затем `scripts/test.sh` — собирает,
ставит, включает сервис, прогоняет unit + `stage0_smoke`. Ручной кросс-чек:
открыть приложение, индикатор «Accessibility: ENABLED».

**Зависимости:** нет.

---

## Этап 1 — WebSocket-сервер + каркас JSON-RPC + сериализация записи ✅

**Цель:** сервис поднимает WS-сервер на `0.0.0.0:9008`, принимает соединения,
парсит JSON-RPC, отвечает единым форматом. Один метод-пустышка `ping` доказывает
тракт «сокет → RPC → ответ». Корутинный scope и single-thread tree-dispatcher
заведены, но дерево ещё не обходим.

**Файлы/классы:**
- `core/TreeDispatcher.kt` — `newSingleThreadContext`/limitedParallelism(1) для
  всех операций с деревом; владелец — сервис.
- `server/WsServer.kt` — наследник `WebSocketServer`; `onOpen/onClose/onMessage/
  onError`; реестр соединений; запуск/остановка из сервиса.
- `server/ConnectionState.kt` — на соединение: `eventStream: Boolean` (def. false),
  ссылка на `FrameWriter`.
- `server/FrameWriter.kt` — `Mutex`-сериализованная отправка text и binary; бинарный
  фрейм уходит одним куском.
- `rpc/Messages.kt` — `@Serializable` `RpcRequest(id, method, params: JsonObject?)`,
  `RpcResponse`, `RpcError(code, message)`, `RpcEvent`.
- `rpc/ErrorCodes.kt` — `PARSE_ERROR`, `INVALID_REQUEST`, `METHOD_NOT_FOUND`,
  `INVALID_PARAMS`, `ACCESSIBILITY_DISABLED`, `NODE_NOT_FOUND`,
  `AMBIGUOUS_MATCH`, `ACTION_NOT_SUPPORTED`, `NOT_EDITABLE`, `STALE`, `INTERNAL`.
- `rpc/JsonRpcDispatcher.kt` — parse → валидация → `MethodRouter` → reply;
  оборачивание исключений в `error`; ответ всегда несёт `id` запроса.
- `rpc/MethodRouter.kt` — таблица method→handler.
- `handlers/PingHandler.kt` — `{ "result": { "pong": true, "ts": <ms> } }`.
- Интеграция в `AutomationAccessibilityService`: старт сервера в
  `onServiceConnected`, стоп + отмена scope в `onDestroy/onUnbind`.

- Доп. швы для тестируемости/развязки: `server/Sender.kt` (абстракция транспорта
  над Java-WebSocket), `core/Agent.kt` (что нужно хендлерам от хоста, без Android).
  Статус сервера протянут в UI (карточка «WebSocket-сервер»).

**Готово ✅:** подключение по WS работает; `ping` → `{pong:true, ts}`; кривой
JSON → `PARSE_ERROR` (id null); неизвестный метод → `METHOD_NOT_FOUND`; нет method
→ `INVALID_REQUEST`; брошенное хендлером `RpcException` → структурный error.
Проверено: 9 JVM unit-тестов (`JsonRpcDispatcherTest`, `FrameWriterTest`) +
E2E `stage1_ping` на устройстве; UI показывает «Слушает :9008».

**Проверка:** `scripts/test.sh` (unit + `stage0_smoke` + `stage1_ping`).
Ручной кросс-чек: `tests/e2e/axon_client.py ping`.

**Зависимости:** Этап 0.

---

## Этап 2 — Вертикальный срез: `dumpHierarchy` ✅

**Цель:** первый полноценный end-to-end метод. Обход дерева от свежего корня,
JSON-ответ с узлами, `screen` и `package` в корне. Прогоняет всю архитектуру
насквозь.

**Файлы/классы:**
- `tree/NodeModel.kt` — `@Serializable` узел: `nodeId, parentId, class, text,
  resourceId, contentDesc, clickable, enabled, focused, bounds{left,top,right,
  bottom}, center{x,y}, children[]`. `bounds` — числовой объект, `center` —
  вычисленный.
- `tree/TreeWalker.kt` — обход от `getRootInActiveWindow()` на `TreeDispatcher`;
  сквозной `nodeId` (счётчик обхода), `parentId` (у корня null); `maxDepth`;
  `compress` (выкинуть `center` и пустые `children`); recycle узлов; минимизация
  `getChild()`-IPC.
- `core/ScreenCounter.kt` — текущий `screen`.
- `handlers/DumpHandler.kt` — params `{maxDepth?, compress?}`; собрать дерево +
  `screen` + foreground `package` (гарантированно в каждом дампе); если
  `getRootInActiveWindow()==null` → `ACCESSIBILITY_DISABLED`/`INTERNAL` (внятный
  error, не падение).

- Чистый билдер узла вынесен в `tree/NodeJson.kt` (без Android) — bounds/center и
  правила `compress` юнит-тестируемы. `Agent` расширен `rootNode()` + `screen`.

**Готово ✅:** `dumpHierarchy` отдаёт дерево реального экрана; pre-order `nodeId`
(root=0), `parentId` (root=null); `screen` и `package` в корне; `maxDepth` и
`compress` работают; пустой корень → `ACCESSIBILITY_DISABLED`. Проверено: 6
unit-тестов `NodeJsonTest` + E2E `stage2_dump` (14 узлов своей `StatusActivity`,
center=середина, maxDepth=0, compress).

**Проверка:** `scripts/test.sh`. Ручной: `tests/e2e/axon_client.py rpc dumpHierarchy '{"compress":true}'`.

**Зависимости:** Этап 1.

---

## Этап 3 — `gesture` ✅

**Цель:** единый координатный примитив через `dispatchGesture` (тап/лонг/свайп/
драг/мультитач).

**Файлы/классы:**
- `handlers/GestureHandler.kt` — params `{strokes:[{points[], startTime, duration}]}`;
  сборка `GestureDescription` (несколько `StrokeDescription` с `startTime`);
  `dispatchGesture` обёрнут в `suspendCancellableCoroutine`; ответ **только** после
  `onCompleted` (на `onCancelled` → error); учёт системного лимита длительности.

- Чистый парсер/валидатор `gesture/GestureSpec.kt` (без Android) отдельно от
  Android-сборки `GestureDescription`; `Agent.performGesture` ждёт `onCompleted`.

**Готово ✅:** тап/свайп/мультитач отрабатывают; ответ строго после `onCompleted`;
валидация (пустые strokes/points, duration ≤ 0 / > лимита, startTime < 0, число
strokes) → `INVALID_PARAMS`; cancel/недиспетч → `GESTURE_FAILED`. Проверено: 13
unit-тестов `GestureSpecTest` + E2E `stage3_gesture` (реальный тап переключил
язык — эффект подтверждён; ответ 519 мс для жеста 500 мс; свайп; валидация).

**Проверка:** `scripts/test.sh`. Документация заведена с этого этапа:
`README.md` + `docs/PROTOCOL.md` (ведутся синхронно с кодом).

**Зависимости:** Этап 1 (на практике после Этапа 2 — координаты из `dumpHierarchy`).

---

## Этап 4 — `nodeAction` ✅

**Цель:** stateless-в-пределах-вызова поиск узла + `performAction`.

**Файлы/классы:**
- `tree/NodeFinder.kt` — поиск от свежего корня по `by`
  (`resourceId|text|class|contentDesc`) + `value`; при N>1 совпадений без `index`
  → `AMBIGUOUS_MATCH`; с `index` — взять N-й; на `TreeDispatcher`.
- `handlers/NodeActionHandler.kt` — одна таблица `action`→константа:
  `click, longClick, setText(+text), clear, focus, clearFocus, select,
  setSelection(+start,end), scrollForward, scrollBackward`; валидация params под
  action (нет `text` у setText → `INVALID_PARAMS`; нет `start/end` у setSelection);
  ошибки: `NODE_NOT_FOUND`, `NOT_EDITABLE`, `ACTION_NOT_SUPPORTED`,
  `STALE` (performAction вернул false); ответ `{success:bool}`; **без молчаливых
  ретраев**.

- Чистые части: `node/NodeActions.kt` (одна таблица ключей), `node/NodeActionRequest.kt`
  (парсер+валидация, юнит-тест), `node/NodeFinder.kt` (точный поиск от свежего корня).
  В статус-экран добавлено «тестовое поле» для E2E `setText`.

**Готово ✅:** click/setText/clear по реальным узлам работают; `NODE_NOT_FOUND`,
`AMBIGUOUS_MATCH` (+ разрешение через `index`), `NOT_EDITABLE`,
`ACTION_NOT_SUPPORTED`, `STALE`, `INVALID_PARAMS` — каждый со своим кодом; без
молчаливых ретраев. Проверено: 9 unit-тестов `NodeActionRequestTest` + E2E
`stage4_nodeaction` (11 проверок, включая реальный click с эффектом и setText/clear).

**Проверка:** `scripts/test.sh`.

**Зависимости:** Этап 2 (обход), Этап 1.

---

## Этап 5 — `globalAction` ✅

**Цель:** системные действия через `performGlobalAction`.

**Файлы/классы:**
- `handlers/GlobalActionHandler.kt` — одна таблица: `back, home, recents,
  notifications, quickSettings, powerDialog, lockScreen` →
  `GLOBAL_ACTION_*`; неизвестное действие → `INVALID_PARAMS`; ответ `{success:bool}`.

- Чистый валидатор `global/GlobalActions.kt` (ключи), Android-маппинг
  ключ→`GLOBAL_ACTION_*` в хендлере. `Agent.performGlobalAction` (сервис наследует
  его от `AccessibilityService`).

**Готово ✅:** все 7 действий выполняются; неизвестное/отсутствующее → `INVALID_PARAMS`;
ответ `{success: bool}`. Проверено: 3 unit-теста `GlobalActionsTest` + E2E
`stage5_globalaction` (home реально уводит из приложения; notifications/recents с
возвратом; валидация).

**Проверка:** `scripts/test.sh`.

**Зависимости:** Этап 1.

---

## Этап 6 — `screenshot` (бинарный фрейм) ⬜

**Цель:** скриншот через `takeScreenshot()` (API 30+), двухсообщательный ответ:
JSON-метаданные + бинарный фрейм `[4 байта id uint32 BE][байты картинки]`.

**Файлы/классы:**
- `handlers/ScreenshotHandler.kt` — params `{format:"jpeg"|"png", quality?}`;
  `takeScreenshot` обёрнут в корутину; `HardwareBuffer`→`Bitmap`→компрессия
  (JPEG def. quality 80); сначала JSON
  `{id, result:{screen, format, width, height, bytes}}`, затем бинарный фрейм
  через `FrameWriter` (целиком, без перемешивания); корректное закрытие буфера.
- Дополнить `FrameWriter`/`Messages` сборкой 4-байтного заголовка id (uint32 BE).

**Готово:** PNG и JPEG приходят как метаданные + бинарь; заголовок = id; картинка
открывается; бинарный фрейм не перемешивается с другими сообщениями под нагрузкой.

**Проверка вручную:** WS-клиент сохраняет бинарь после метаданных, проверить
`width/height/bytes` и что файл открывается; снять несколько подряд параллельно с
событиями.

**Зависимости:** Этап 1 (FrameWriter), Этап 2 (screen counter).

---

## Этап 7 — События: `onAccessibilityEvent`, debounce, `setEventStream` ⬜

**Цель:** server-push события `screenChanged` и `toast`, дебаунс, дедуп по `screen`,
кран подписки на соединение.

**Файлы/классы:**
- `events/AccessibilityEventHub.kt` — триггерят `screenChanged` только
  `TYPE_WINDOW_STATE_CHANGED` и `TYPE_WINDOW_CONTENT_CHANGED` (остальное — шум);
  trailing-debounce ~50–100 мс; инкремент `ScreenCounter` только при реальном
  изменении (дедуп по номеру); `toast` из `TYPE_NOTIFICATION_STATE_CHANGED`
  (текст из `event.text`); push только подписанным соединениям.
- `handlers/EventStreamHandler.kt` — `{enabled:bool}` переключает per-connection
  флаг (единственное допустимое состояние подписки).
- Связать `AutomationAccessibilityService.onAccessibilityEvent` → Hub.

**Готово:** при смене экрана подписчик получает `screenChanged{screen,package}`
один раз после затихания; повторный тот же `screen` не шлётся; тост даёт
`toast{text,package}`; без `setEventStream(true)` событий нет; скролл/фокус не
порождают `screenChanged`.

**Проверка вручную:** включить `setEventStream`, переключать экраны/листать,
наблюдать единичные события; спровоцировать тост (например, ошибку формы).

**Зависимости:** Этап 1, Этап 2 (ScreenCounter, package).

---

## Этап 8 — Устойчивость: foreground, ping/pong, состояние «accessibility off» ⬜

**Цель:** сервис живёт дольше, мёртвый сервис детектируется, граничные состояния
не валят процесс.

**Файлы/классы:**
- Foreground-нотификация в `AutomationAccessibilityService`
  (`startForeground`, notification channel) для продления жизни.
- `handlers/PingHandler.kt` (из Этапа 1) — подтвердить app-level семантику:
  `pong` доказывает, что жив именно сервис, а не только TCP-сокет.
- Граничные состояния: «сокет слушает, accessibility выключен» (нет корня) →
  `ACCESSIBILITY_DISABLED` во всех tree-зависимых методах; никаких падений.
- Корректная отмена scope и остановка сервера в `onDestroy/onUnbind`.

**Готово:** сервис не убивается агрессивно при свёрнутом приложении; `ping`
отвечает только при живом сервисе; при выключенном accessibility методы дают
внятный error.

**Проверка вручную:** свернуть приложение, подождать, убедиться что WS жив и
`dumpHierarchy` работает; выключить accessibility — проверить error; убить сервис
и убедиться, что `ping` не отвечает (детект на ПК).

**Зависимости:** Этапы 1–2.

---

## Этап 9 — UI-экран включения/статуса (полный) ⬜

**Цель:** довести `StatusActivity` до рабочего пульта.

**Файлы/классы:**
- `ui/StatusActivity.kt` + layout — индикаторы: «accessibility включён»,
  «WS-сервер слушает 9008», «активных соединений N»; кнопка перехода в настройки
  accessibility; тумблер запуска/остановки WS-сервера; краткая инструкция.

**Готово:** экран показывает реальные статусы; тумблер реально стартует/останавливает
сервер; кнопка ведёт в настройки.

**Проверка вручную:** открыть приложение — проверить индикаторы и тумблер в разных
состояниях (accessibility on/off, сервер on/off, есть/нет соединений).

**Зависимости:** Этапы 1, 8.

---

## Этап 10 — Финализация и задел на будущее ⬜

**Цель:** документация протокола, мелкая полировка, незакрытый путь к `getWindows`.

**Файлы/классы:**
- `PROTOCOL.md` — все методы, форматы запросов/ответов, коды ошибок, формат
  бинарного фрейма, события.
- Точка расширения под `getWindows` (несколько окон) в `TreeWalker`/router без
  реализации.
- Прогон полного сценария: dump → gesture → nodeAction → screenshot → события.

**Готово:** протокол задокументирован; задел под `getWindows` обозначен; сквозной
сценарий проходит.

**Зависимости:** все предыдущие.

---

## Карта зависимостей

```
0 → 1 → 2 → 4
        2 → 6
        2 → 7
    1 → 3
    1 → 5
1,2 → 8 → 9
все → 10
```

## Рабочий процесс

Реализуем строго по одному этапу. После каждого: объяснение ключевых решений,
инструкция по проверке, ожидание подтверждения, затем обновление статуса (⬜→✅)
в этом файле. Несколько этапов за раз — только с явного согласия.
