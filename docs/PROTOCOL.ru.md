# Справочник протокола Axon

[English](PROTOCOL.md) · **Русский**

JSON-RPC поверх одного WebSocket. Этот документ — источник истины для формата
обмена между ПК-клиентом и агентом на устройстве (APK). Поддерживается в
актуальном состоянии по мере прохождения этапов.

> Легенда статуса: ✅ реализовано · 🔜 запланировано (зарезервировано, пока не
> реализовано).

---

## 1. Принцип проектирования — устройство без состояния

Всё состояние — что ждать, ретраи, навигация, цепочки действий — живёт на
**ПК-клиенте**. Устройство предоставляет только атомарные примитивы:

- `AccessibilityNodeInfo` **никогда не кэшируется** между вызовами. Каждый вызов
  начинается со свежего `getRootInActiveWindow()`.
- Значения `nodeId` валидны **только в пределах одного дампа**. Обратно на
  устройство они не присылаются.
- Действие по узлу («найти узел по критериям и выполнить») завершается в пределах
  одного RPC; узел не переживает вызов.

Единственное допустимое состояние на соединении — флаг потока событий и
дебаунс-аккумулятор этого потока, плюс общий по процессу счётчик экрана.

---

## 2. Транспорт

- WebSocket-сервер внутри accessibility-сервиса, слушает `0.0.0.0:9008`.
- ПК достаёт его по USB через `adb forward`:

  ```
  adb forward tcp:9008 tcp:9008
  # затем подключиться к ws://127.0.0.1:9008
  ```

- Живость проверяется на прикладном уровне методом [`ping`](#ping-) — живой
  TCP-сокет не доказывает, что логика сервиса жива.

---

## 3. Типы сообщений

В одном сокете сосуществуют три типа сообщений:

| Тип | Направление | Как распознать |
|------|-----------|---------------------|
| **Ответ** | устройство → ПК | text-фрейм, JSON с `id` и `result` или `error` |
| **Событие** (server-push) | устройство → ПК | text-фрейм, JSON с полем `event` и **без** `id` |
| **Бинарный фрейм** (скриншоты) | устройство → ПК | бинарный фрейм: `[4 байта id, uint32 big-endian][байты картинки]` |

### Соглашения

- Ключи JSON — **camelCase** (`resourceId`, не `resource-id`).
- `bounds` — числовой объект `{ "left", "top", "right", "bottom" }` (не строка).
- Каждый узел несёт вычисленный `center` `{ "x", "y" }` = центр `bounds`.

---

## 4. Формат запроса / ответа

### Запрос

```json
{ "id": 1, "method": "methodName", "params": { } }
```

- `id` — любое JSON-значение; возвращается обратно как есть. `params` опционально.

### Успешный ответ

```json
{ "id": 1, "result": { } }
```

### Ответ с ошибкой

```json
{ "id": 1, "error": { "code": "NODE_NOT_FOUND", "message": "..." } }
```

- Если запрос настолько кривой, что `id` прочитать нельзя, `id` равен `null`.
- Устройство **никогда не ретраит молча** — ретраи решает клиент. У каждой
  неудачи свой стабильный `code`.

---

## 5. Коды ошибок

| Код | Значение |
|------|---------|
| `PARSE_ERROR` | запрос не является валидным JSON |
| `INVALID_REQUEST` | валидный JSON, но не корректный запрос (не объект или отсутствует/нестроковый `method`) |
| `METHOD_NOT_FOUND` | неизвестный метод |
| `INVALID_PARAMS` | параметры отсутствуют или некорректны для метода |
| `INTERNAL` | неожиданный сбой на стороне сервера |
| `ACCESSIBILITY_DISABLED` | нет корня активного окна (сервис выключен или нет foreground-окна) |
| `NODE_NOT_FOUND` | критерии nodeAction ничего не нашли |
| `AMBIGUOUS_MATCH` | критерии совпали с несколькими узлами, а `index` не передан |
| `ACTION_NOT_SUPPORTED` | узел не поддерживает запрошенное действие |
| `NOT_EDITABLE` | `setText`/`clear` по нередактируемому узлу |
| `STALE` | `performAction` вернул false (узел устарел) |
| `GESTURE_FAILED` | жест отменён или не удалось его диспетчеризовать |

---

## 6. Методы

### `ping` ✅

Heartbeat. Доказывает, что жив весь конвейер, а не только сокет.

- **params:** нет
- **result:** `{ "pong": true, "ts": <epoch millis> }`

```json
→ { "id": 1, "method": "ping" }
← { "id": 1, "result": { "pong": true, "ts": 1781552384385 } }
```

---

### `dumpHierarchy` ✅

Сериализует дерево UI от свежего `getRootInActiveWindow()`.

- **params:**
  - `maxDepth` *(int, опц.)* — максимальная глубина дерева; корень — глубина 0.
    `0` = только корень. По умолчанию: без ограничения.
  - `compress` *(bool, опц.)* — выкинуть (вычислимый) `center` и пустые массивы
    `children` для экономии трафика. По умолчанию: `false`.
- **result:** **объект корневого узла** (см. [схему узла](#схема-узла)) с двумя
  дополнительными полями верхнего уровня:
  - `screen` *(int)* — поколение состояния экрана (см. `screenChanged`).
  - `package` *(string)* — пакет foreground-приложения; присутствует в каждом дампе.
- **ошибки:** `ACCESSIBILITY_DISABLED`, когда нет корня активного окна.

```json
→ { "id": 2, "method": "dumpHierarchy", "params": { "maxDepth": 2, "compress": true } }
← { "id": 2, "result": { "screen": 0, "package": "com.axon.agent",
      "nodeId": 0, "parentId": null, "class": "android.widget.FrameLayout", ... } }
```

#### Схема узла

```json
{
  "nodeId": 42,
  "parentId": 17,
  "class": "android.widget.Button",
  "text": "Войти",
  "resourceId": "com.app:id/login",
  "contentDesc": null,
  "clickable": true,
  "enabled": true,
  "focused": false,
  "bounds": { "left": 420, "top": 1800, "right": 660, "bottom": 1920 },
  "center": { "x": 540, "y": 1860 },
  "children": [ ]
}
```

- `nodeId` — сквозной pre-order счётчик, **валиден только в этом дампе**. Корень = 0.
- `parentId` — `null` у корня.
- `compress: true` опускает `center` и пустые `children`.
- `class`/`text`/`resourceId`/`contentDesc` присутствуют всегда (`null`, если
  отсутствуют) ради стабильной схемы.

---

### `gesture` ✅

Единый координатный примитив через `dispatchGesture`. Тап, лонг-тап, дабл-тап,
свайп, драг и мультитач — это лишь вариации числа точек, длительности и числа
параллельных strokes.

- **params:**
  - `strokes` *(массив, обяз., непустой)* — каждый stroke:
    - `points` *(массив, обяз., непустой)* — `[{ "x": int, "y": int }, ...]`.
      Одна точка = тап/лонг-тап; много = путь (свайп/драг).
    - `startTime` *(int мс, опц., по умолч. 0)* — смещение от начала всего жеста.
      Используется для рассинхронизации параллельных strokes.
    - `duration` *(int мс, обяз., > 0)* — длительность stroke.
- **result:** `{ "success": true }` — отправляется **только после** завершения
  жеста (колбэк `onCompleted`).
- **ошибки:**
  - `INVALID_PARAMS` — отсутствуют/пусты `strokes` или `points`,
    отсутствует/неположительный `duration`, отрицательный `startTime`, слишком
    много strokes или `duration` сверх системного лимита
    (`GestureDescription.getMaxGestureDuration()`).
  - `GESTURE_FAILED` — жест отменён или не удалось диспетчеризовать.

```json
// тап
→ { "id": 3, "method": "gesture",
    "params": { "strokes": [ { "points": [ { "x": 540, "y": 1860 } ], "duration": 50 } ] } }
← { "id": 3, "result": { "success": true } }

// свайп вверх
→ { "id": 4, "method": "gesture", "params": { "strokes": [
      { "points": [ { "x": 540, "y": 1500 }, { "x": 540, "y": 300 } ],
        "startTime": 0, "duration": 250 } ] } }

// щипок (два параллельных stroke)
→ { "method": "gesture", "params": { "strokes": [
      { "points": [ { "x": 400, "y": 1000 }, { "x": 200, "y": 1000 } ], "duration": 300 },
      { "points": [ { "x": 600, "y": 1000 }, { "x": 800, "y": 1000 } ], "duration": 300 } ] } }
```

---

### `nodeAction` ✅

Найти узел на лету от **свежего корня** по точным критериям и выполнить над ним
действие. Stateless в пределах вызова — узел не переживает RPC.

- **params:**
  - `by` *(обяз.)* — селектор: `resourceId` | `text` | `class` | `contentDesc`.
  - `value` *(string, обяз.)* — точное значение для совпадения.
  - `index` *(int, опц.)* — выбрать N-е совпадение (с 0), когда совпадений несколько.
  - `action` *(обяз.)* — одно из действий ниже.
  - `text` *(string)* — **обязателен для** `setText`.
  - `start`, `end` *(int)* — **обязательны для** `setSelection`.
- **result:** `{ "success": true }`.
- **ошибки:**
  - `NODE_NOT_FOUND` — ничего не совпало.
  - `AMBIGUOUS_MATCH` — совпало несколько, а `index` не передан (уточните или
    передайте `index`).
  - `INVALID_PARAMS` — плохие/отсутствующие параметры или `index` вне диапазона.
  - `NOT_EDITABLE` — `setText`/`clear` по нередактируемому узлу.
  - `ACTION_NOT_SUPPORTED` — найденный узел не поддерживает действие.
  - `STALE` — `performAction` вернул false (узел изменился под нами). **Не
    ретраится** на устройстве — ПК сам решает, передампить ли и повторить.

#### Таблица действий

| action | эффект | доп. параметры |
|--------|--------|--------------|
| `click` | `ACTION_CLICK` | — |
| `longClick` | `ACTION_LONG_CLICK` | — |
| `setText` | `ACTION_SET_TEXT` | `text` |
| `clear` | `ACTION_SET_TEXT` с `""` | — |
| `focus` | `ACTION_FOCUS` | — |
| `clearFocus` | `ACTION_CLEAR_FOCUS` | — |
| `select` | `ACTION_SELECT` | — |
| `setSelection` | `ACTION_SET_SELECTION` | `start`, `end` |
| `scrollForward` | `ACTION_SCROLL_FORWARD` | — |
| `scrollBackward` | `ACTION_SCROLL_BACKWARD` | — |

```json
→ { "id": 5, "method": "nodeAction",
    "params": { "by": "resourceId", "value": "com.app:id/login", "action": "click" } }
← { "id": 5, "result": { "success": true } }

→ { "method": "nodeAction",
    "params": { "by": "class", "value": "android.widget.EditText",
                "index": 0, "action": "setText", "text": "hello" } }
```

---

### `globalAction` ✅

Системные действия через `performGlobalAction`. Одна таблица ключ→константа.

- **params:** `action` *(обяз.)* — одно из: `back`, `home`, `recents`,
  `notifications`, `quickSettings`, `powerDialog`, `lockScreen`.
- **result:** `{ "success": <bool> }` — результат платформенного `performGlobalAction`.
- **ошибки:** `INVALID_PARAMS` при отсутствующем или неизвестном `action`.

```json
→ { "id": 6, "method": "globalAction", "params": { "action": "home" } }
← { "id": 6, "result": { "success": true } }
```

---

### `screenshot` ✅

Снимок экрана через `takeScreenshot()`. Ответ — **два сообщения**: JSON-метаданные,
сразу за которыми идёт картинка бинарным фреймом. Они отправляются атомарно (между
ними ничего не вклинивается).

- **params:**
  - `format` *(опц.)* — `jpeg` (по умолч.) или `png`.
  - `quality` *(int 0..100, опц.)* — качество JPEG, по умолч. 80 (для PNG игнорируется).
- **result (сообщение 1, JSON):**
  `{ "screen": int, "format": string, "width": int, "height": int, "bytes": int }`
- **сообщение 2 (бинарный фрейм):** `[4 байта id, uint32 big-endian][байты картинки]`,
  где `id` — id запроса, а число байт равно `bytes` из метаданных.
- **ошибки:** `INVALID_PARAMS` (плохой `format`/`quality`); `INTERNAL`, если захват
  не удался (например, платформа лимитирует частоту `takeScreenshot`). При ошибке
  бинарный фрейм не отправляется.

```json
→ { "id": 7, "method": "screenshot", "params": { "format": "jpeg", "quality": 80 } }
← { "id": 7, "result": { "screen": 0, "format": "jpeg", "width": 1080, "height": 2340, "bytes": 142233 } }
← <бинарный фрейм: 00 00 00 07  FF D8 FF …>
```

---

### `setEventStream` ✅

Кран server-push событий для соединения (раздел 7). Булев флаг — единственное
состояние подписки, которое держит устройство.

- **params:** `enabled` *(bool, обяз.)*.
- **result:** `{ "success": true, "enabled": <bool> }`.
- **ошибки:** `INVALID_PARAMS`, если `enabled` отсутствует/не булев.

```json
→ { "id": 8, "method": "setEventStream", "params": { "enabled": true } }
← { "id": 8, "result": { "success": true, "enabled": true } }
```

---

## 7. Server-push события ✅

У событий есть поле `event` и **нет** `id`. Доставляются только тем соединениям,
которые включили их через [`setEventStream`](#seteventstream-).

### `screenChanged`

`{ "event": "screenChanged", "screen": int, "package": string }`

- Триггерится только `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`
  (скролл/фокус/выделение игнорируются как шум).
- **Trailing-debounce** (~80 мс): всплеск схлопывается в одно событие после затихания.
- **Дедуп:** `screen` инкрементируется (и событие шлётся) только при *реальном*
  изменении — смена состояния окна или новый пакет/окно. Чистая возня контента на
  том же экране (часы, тикеры) подавляется.

### `toast`

`{ "event": "toast", "text": string, "package": string }`

- Источник — `TYPE_NOTIFICATION_STATE_CHANGED`; шлётся сразу (без дебаунса).
- Полезно для перехвата фидбэка форм («Неверный пароль» и т.п.).

---

## 8. Что намеренно **не** входит в APK

Эти возможности остаются на ПК через обычный `adb` и вне зоны ответственности
агента: запуск/убийство приложений, установка/удаление, списки пакетов,
громкость/питание/произвольные keyevent, буфер обмена,
поворот/плотность/разрешение, файлы, logcat.
