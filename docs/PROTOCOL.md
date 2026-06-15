# Axon Protocol Reference

**English** · [Русский](PROTOCOL.ru.md)

JSON-RPC over a single WebSocket. This document is the source of truth for the
wire format between the PC client and the on-device agent (APK). It is kept in
sync with the implementation stage by stage.

> Status legend: ✅ implemented · 🔜 planned (reserved, not yet implemented).

---

## 1. Design principle — the device is stateless

All state — what to wait for, retries, navigation, chained actions — lives on the
**PC client**. The device exposes only atomic primitives:

- `AccessibilityNodeInfo` is **never cached** between calls. Every call starts
  from a fresh `getRootInActiveWindow()`.
- `nodeId` values are valid **only within a single dump**. They are never sent
  back to the device.
- A node action ("find a node by criteria and act on it") completes within one
  RPC; the node never outlives the call.

The only permitted per-connection state is the event-stream toggle and the
debounce accumulator for the event stream, plus a process-wide screen counter.

---

## 2. Transport

- WebSocket server inside the accessibility service, listening on
  `0.0.0.0:9008`.
- The PC reaches it over USB via `adb forward`:

  ```
  adb forward tcp:9008 tcp:9008
  # then connect to ws://127.0.0.1:9008
  ```

- Liveness is checked at the application layer with [`ping`](#ping-) — a live TCP
  socket does not prove the service logic is alive.

---

## 3. Message kinds

Three kinds of messages share the one socket:

| Kind | Direction | How to recognize it |
|------|-----------|---------------------|
| **Response** | device → PC | text frame, JSON with an `id` and a `result` or `error` |
| **Event** (server-push) 🔜 | device → PC | text frame, JSON with an `event` field and **no** `id` |
| **Binary frame** (screenshots) 🔜 | device → PC | binary frame: `[4-byte id, uint32 big-endian][payload bytes]` |

### Conventions

- JSON keys are **camelCase** (`resourceId`, not `resource-id`).
- `bounds` is a numeric object `{ "left", "top", "right", "bottom" }` (not a string).
- Each node carries a computed `center` `{ "x", "y" }` = center of `bounds`.

---

## 4. Request / response shape

### Request

```json
{ "id": 1, "method": "methodName", "params": { } }
```

- `id` — any JSON value; echoed back verbatim. `params` is optional.

### Success response

```json
{ "id": 1, "result": { } }
```

### Error response

```json
{ "id": 1, "error": { "code": "NODE_NOT_FOUND", "message": "..." } }
```

- On a request so malformed that the `id` cannot be read, `id` is `null`.
- The device **never retries silently** — retries are the client's decision. Each
  failure has a distinct, stable `code`.

---

## 5. Error codes

| Code | Meaning |
|------|---------|
| `PARSE_ERROR` | request was not valid JSON |
| `INVALID_REQUEST` | valid JSON but not a proper request (not an object, or missing/non-string `method`) |
| `METHOD_NOT_FOUND` | unknown method |
| `INVALID_PARAMS` | params missing or wrong for the method |
| `INTERNAL` | unexpected server-side failure |
| `ACCESSIBILITY_DISABLED` | no active-window root (service off or no foreground window) |
| `NODE_NOT_FOUND` | node-action criteria matched nothing |
| `AMBIGUOUS_MATCH` | criteria matched several nodes and no `index` was given |
| `ACTION_NOT_SUPPORTED` | the node does not support the requested action |
| `NOT_EDITABLE` | `setText`/`clear` on a non-editable node |
| `STALE` | `performAction` returned false (node went stale) |
| `GESTURE_FAILED` | gesture was cancelled or could not be dispatched |

---

## 6. Methods

### `ping` ✅

Heartbeat. Proves the whole pipeline is alive, not just the socket.

- **params:** none
- **result:** `{ "pong": true, "ts": <epoch millis> }`

```json
→ { "id": 1, "method": "ping" }
← { "id": 1, "result": { "pong": true, "ts": 1781552384385 } }
```

---

### `dumpHierarchy` ✅

Serialize the UI tree from a fresh `getRootInActiveWindow()`.

- **params:**
  - `maxDepth` *(int, optional)* — max tree depth; root is depth 0. `0` = root only.
    Default: unbounded.
  - `compress` *(bool, optional)* — drop the (recomputable) `center` and any empty
    `children` array to save bandwidth. Default: `false`.
- **result:** the **root node object** (see [node schema](#node-schema)) with two
  extra top-level fields:
  - `screen` *(int)* — screen-state generation (see `screenChanged`).
  - `package` *(string)* — foreground app package; present in every dump.
- **errors:** `ACCESSIBILITY_DISABLED` when there is no active-window root.

```json
→ { "id": 2, "method": "dumpHierarchy", "params": { "maxDepth": 2, "compress": true } }
← { "id": 2, "result": { "screen": 0, "package": "com.axon.agent",
      "nodeId": 0, "parentId": null, "class": "android.widget.FrameLayout", ... } }
```

#### Node schema

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

- `nodeId` — running pre-order counter, **valid only within this dump**. Root = 0.
- `parentId` — `null` for the root.
- `compress: true` omits `center` and empty `children`.
- `class`/`text`/`resourceId`/`contentDesc` are always present (`null` when absent)
  for a stable schema.

---

### `gesture` ✅

The single coordinate primitive via `dispatchGesture`. Tap, long-press,
double-tap, swipe, drag and multi-touch are all just variations in point count,
duration and number of parallel strokes.

- **params:**
  - `strokes` *(array, required, non-empty)* — each stroke:
    - `points` *(array, required, non-empty)* — `[{ "x": int, "y": int }, ...]`.
      One point = tap/long-press; many = path (swipe/drag).
    - `startTime` *(int ms, optional, default 0)* — offset from the start of the
      whole gesture. Use it to stagger parallel strokes.
    - `duration` *(int ms, required, > 0)* — stroke duration.
- **result:** `{ "success": true }` — sent **only after** the gesture completes
  (the `onCompleted` callback).
- **errors:**
  - `INVALID_PARAMS` — missing/empty `strokes` or `points`, missing/non-positive
    `duration`, negative `startTime`, too many strokes, or `duration` over the
    system limit (`GestureDescription.getMaxGestureDuration()`).
  - `GESTURE_FAILED` — gesture cancelled or could not be dispatched.

```json
// tap
→ { "id": 3, "method": "gesture",
    "params": { "strokes": [ { "points": [ { "x": 540, "y": 1860 } ], "duration": 50 } ] } }
← { "id": 3, "result": { "success": true } }

// swipe up
→ { "id": 4, "method": "gesture", "params": { "strokes": [
      { "points": [ { "x": 540, "y": 1500 }, { "x": 540, "y": 300 } ],
        "startTime": 0, "duration": 250 } ] } }

// pinch (two parallel strokes)
→ { "method": "gesture", "params": { "strokes": [
      { "points": [ { "x": 400, "y": 1000 }, { "x": 200, "y": 1000 } ], "duration": 300 },
      { "points": [ { "x": 600, "y": 1000 }, { "x": 800, "y": 1000 } ], "duration": 300 } ] } }
```

---

### `nodeAction` ✅

Find a node on the fly from a **fresh root** by exact-match criteria and perform an
action on it. Stateless within the call — the node never outlives the RPC.

- **params:**
  - `by` *(required)* — selector: `resourceId` | `text` | `class` | `contentDesc`.
  - `value` *(string, required)* — exact value to match.
  - `index` *(int, optional)* — pick the N-th match (0-based) when several match.
  - `action` *(required)* — one of the actions below.
  - `text` *(string)* — **required for** `setText`.
  - `start`, `end` *(int)* — **required for** `setSelection`.
- **result:** `{ "success": true }`.
- **errors:**
  - `NODE_NOT_FOUND` — nothing matched.
  - `AMBIGUOUS_MATCH` — several matched and no `index` was given (refine or pass `index`).
  - `INVALID_PARAMS` — bad/missing params, or `index` out of range.
  - `NOT_EDITABLE` — `setText`/`clear` on a non-editable node.
  - `ACTION_NOT_SUPPORTED` — the matched node does not support the action.
  - `STALE` — `performAction` returned false (node changed under us). **Not retried**
    on the device — the PC decides whether to re-dump and retry.

#### Action table

| action | effect | extra params |
|--------|--------|--------------|
| `click` | `ACTION_CLICK` | — |
| `longClick` | `ACTION_LONG_CLICK` | — |
| `setText` | `ACTION_SET_TEXT` | `text` |
| `clear` | `ACTION_SET_TEXT` with `""` | — |
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

## 7. Planned methods 🔜

Reserved and documented here for completeness; not yet implemented.

- **`globalAction`** — `back`, `home`, `recents`, `notifications`, `quickSettings`,
  `powerDialog`, `lockScreen`.
- **`screenshot`** — JSON metadata then a binary frame
  (`{ id, result: { screen, format, width, height, bytes } }` + `[4-byte id][image]`).
- **`setEventStream`** — `{ enabled: bool }` toggles server-push for this connection.

### Planned server-push events 🔜

- `{ "event": "screenChanged", "screen": int, "package": string }`
- `{ "event": "toast", "text": string, "package": string }`

---

## 8. What is intentionally **not** in the APK

These stay on the PC over plain `adb` and are out of scope for the agent: launch/
kill apps, install/uninstall, package lists, volume/power/arbitrary keyevents,
clipboard, rotation/density/resolution, files, logcat.
