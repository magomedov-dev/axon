# Axon

[English](README.md) · **Русский**

Минимальный, быстрый, **stateless** агент автоматизации UI для Android — лёгкая
альтернатива UIAutomator2 на базе `AccessibilityService` с транспортом
WebSocket + JSON-RPC. Предназначен для управления большим парком телефонов,
подключённых к ПК по USB.

- **Протокол:** [`docs/PROTOCOL.ru.md`](docs/PROTOCOL.ru.md)
- **План и статус:** [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)

---

## Зачем

Классическая боль UIAutomator2 — устаревшие (stale) хэндлы узлов и скрытое
состояние на устройстве. Axon убирает это, держа **всё** состояние на ПК-клиенте
и оставляя на телефоне только атомарные примитивы:

- Узлы не кэшируются; каждый вызов начинается со свежего `getRootInActiveWindow()`.
- Идентификаторы узлов валидны только в пределах одного дампа и обратно на
  устройство не присылаются.
- Действие («найти узел по критериям и выполнить») живёт и умирает внутри одного RPC.

Приложение на устройстве маленькое, предсказуемое и устойчивое к падениям;
ожидания, ретраи и навигация — на стороне клиента.

## Архитектура

Один процесс, один корутинный scope, без IPC-моста:

```
ПК-клиент ──(adb forward tcp:9008)──► WebSocket :9008
                                         │  (внутри AccessibilityService)
                                         ▼
                         JsonRpcDispatcher ── MethodRouter ── handlers/
                                         │
              ┌──────────────────────────┼───────────────────────────┐
              ▼                          ▼                           ▼
        TreeDispatcher            dispatchGesture              FrameWriter
     (single-thread, вся        (suspend до                 (запись text + binary,
      работа с узлами)            onCompleted)                сериализована Mutex)
```

Два шва держат ядро юнит-тестируемым без устройства:

- **`Sender`** абстрагирует транспорт (Java-WebSocket) от прикладного слоя.
- **`Agent`** абстрагирует то, что хендлерам нужно от хоста, чтобы диспетчер и
  хендлеры тестировались без Android.

### Устойчивость

- Сервис работает как **foreground-сервис** (`specialUse`), поэтому система с куда
  меньшей вероятностью убьёт его при свёрнутом управляющем приложении.
- Живость — на прикладном уровне: JSON-RPC [`ping`](docs/PROTOCOL.ru.md) доказывает,
  что жив весь конвейер, а не только TCP-сокет.
- Вызовы, зависящие от дерева, возвращают `ACCESSIBILITY_DISABLED` (без падения),
  когда нет корня активного окна. Scope/сервер/tree чисто останавливаются при
  unbind/destroy.

### Раскладка

```
app/src/main/java/com/axon/agent/
  AutomationAccessibilityService.kt   точка входа; владеет scope, tree, сервером
  core/      Agent, TreeDispatcher, ScreenCounter
  server/    Sender, FrameWriter, ConnectionState, WsServer
  rpc/       Messages, ErrorCodes, RpcException, MethodRouter, JsonRpcDispatcher, RpcContext
  handlers/  PingHandler, DumpHandler, GestureHandler, NodeActionHandler, ...
  tree/      NodeJson, TreeWalker
  gesture/   GestureSpec (чистый парсер/валидатор)
  node/      NodeActions, NodeActionRequest, NodeFinder
  ui/        StatusActivity (статус + переключатель RU/EN)
app/src/test/…   JVM unit-тесты (без устройства)
tests/e2e/…      Python E2E-тесты (реальное устройство, WS-клиент без зависимостей)
scripts/…        setup / build / device / test
docs/PROTOCOL.md (+ PROTOCOL.ru.md)
```

## Тулчейн (самодостаточный)

Весь инструментарий ставится **локально** в `./.tooling/` (системный JDK слишком
новый для AGP, поэтому используется локальный JDK 17):

```
scripts/setup.sh        # один раз: JDK 17 + Gradle + Android SDK в ./.tooling,
                        #           пишет local.properties, генерирует ./gradlew
scripts/build.sh <task> # сборка локальным тулчейном (host-окружение не трогается)
```

| | |
|---|---|
| Язык | Kotlin + корутины |
| min / target SDK | 30 / 35 |
| App id | `com.axon.agent` |
| WebSocket | Java-WebSocket (сервер), `0.0.0.0:9008` |
| JSON | kotlinx.serialization |

## Сборка и установка

```
scripts/setup.sh                       # только при первом запуске
scripts/build.sh :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Затем включить accessibility-сервис. На AOSP это можно сделать с хоста:

```
scripts/device.sh enable     # добавляет наш сервис, не затирая чужие
scripts/device.sh forward    # adb forward tcp:9008
scripts/device.sh status     # включённые сервисы + foreground-пакет
```

…либо открыть приложение **Axon** и нажать *Открыть настройки спец. возможностей*.

## Подключение с ПК

```
adb forward tcp:9008 tcp:9008
python3 tests/e2e/axon_client.py ping
python3 tests/e2e/axon_client.py rpc dumpHierarchy '{"compress":true}'
```

`tests/e2e/axon_client.py` — WebSocket + JSON-RPC клиент без зависимостей (только
stdlib), пригоден и как библиотека, и как быстрый CLI.

## Тестирование

Две петли, обе автоматизированы на подключённом устройстве:

```
scripts/test.sh              # unit-тесты + сборка + установка + включение + все E2E
scripts/test.sh --unit       # только JVM unit-тесты (без устройства)
scripts/test.sh --e2e stage2 # один E2E-этап
```

- **Unit** (`app/src/test/`, JUnit): чистая логика — роутинг/ошибки JSON-RPC,
  JSON узла (bounds/center, compress), парсинг/валидация жеста и nodeAction,
  фрейминг.
- **E2E** (`tests/e2e/`, Python): реальный тракт через `adb forward` — каждый
  этап поставляет `stageN_*.py`, который гоняет устройство и проверяет поведение.

## Статус

| Метод | Состояние |
|--------|-------|
| `ping` | ✅ |
| `dumpHierarchy` | ✅ |
| `gesture` | ✅ |
| `nodeAction` | ✅ |
| `globalAction` | ✅ |
| `screenshot` | ✅ |
| `setEventStream` + события | ✅ |

Поэтапный план и критерии готовности — в
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

## Тестовое устройство

Устройство разработки/CI: Redmi Note 7 (lavender), Android 13 (API 33), arm64-v8a.
