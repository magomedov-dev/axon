# Axon

A minimal, fast, **stateless** UI-automation agent for Android — a lightweight
alternative to UIAutomator2, built on `AccessibilityService` with a WebSocket +
JSON-RPC transport. Designed for driving a large fleet of phones connected to a
PC over USB.

- **Protocol:** [`docs/PROTOCOL.md`](docs/PROTOCOL.md)
- **Roadmap & status:** [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)

---

## Why

UIAutomator2's classic pain is stale node handles and hidden state on the device.
Axon removes that by keeping **all** state on the PC client and exposing only
atomic primitives on the phone:

- Nodes are never cached; every call starts from a fresh `getRootInActiveWindow()`.
- Node ids are valid only within one dump and never sent back.
- An action ("find node by criteria + act") lives and dies inside one RPC.

The device app is small, predictable, and crash-resistant; the client owns waits,
retries and navigation.

## Architecture

One process, one coroutine scope, no IPC bridge:

```
PC client ──(adb forward tcp:9008)──► WebSocket :9008
                                         │  (inside the AccessibilityService)
                                         ▼
                         JsonRpcDispatcher ── MethodRouter ── handlers/
                                         │
              ┌──────────────────────────┼───────────────────────────┐
              ▼                          ▼                           ▼
        TreeDispatcher            dispatchGesture              FrameWriter
     (single-thread, all       (suspend until            (Mutex-serialized
      AccessibilityNodeInfo)     onCompleted)              text + binary)
```

Two seams keep the core unit-testable off-device:

- **`Sender`** abstracts the transport (Java-WebSocket) away from the app layer.
- **`Agent`** abstracts what handlers need from the host, so the dispatcher and
  handlers can be tested without Android.

### Layout

```
app/src/main/java/com/axon/agent/
  AutomationAccessibilityService.kt   entry point; owns scope, tree, server
  core/      Agent, TreeDispatcher, ScreenCounter
  server/    Sender, FrameWriter, ConnectionState, WsServer
  rpc/       Messages, ErrorCodes, RpcException, MethodRouter, JsonRpcDispatcher, RpcContext
  handlers/  PingHandler, DumpHandler, GestureHandler, ...
  tree/      NodeJson, TreeWalker
  gesture/   GestureSpec (pure parser/validator)
  ui/        StatusActivity (status + RU/EN switch)
app/src/test/…   JVM unit tests (no device)
tests/e2e/…      Python E2E tests (real device, dependency-free WS client)
scripts/…        setup / build / device / test
docs/PROTOCOL.md
```

## Toolchain (self-contained)

Everything is installed **locally** under `./.tooling/` (the host JDK is too new
for AGP, so a local JDK 17 is used):

```
scripts/setup.sh        # one-time: JDK 17 + Gradle + Android SDK into ./.tooling,
                        #           writes local.properties, generates ./gradlew
scripts/build.sh <task> # build via the local toolchain (host env untouched)
```

| | |
|---|---|
| Language | Kotlin + coroutines |
| min / target SDK | 30 / 35 |
| App id | `com.axon.agent` |
| WebSocket | Java-WebSocket (server), `0.0.0.0:9008` |
| JSON | kotlinx.serialization |

## Build & install

```
scripts/setup.sh                       # first time only
scripts/build.sh :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable the accessibility service. On AOSP this can be done from the host:

```
scripts/device.sh enable     # appends our service, keeps others
scripts/device.sh forward    # adb forward tcp:9008
scripts/device.sh status     # enabled services + foreground package
```

…or open the **Axon** app and tap *Open Accessibility settings*.

## Connect from the PC

```
adb forward tcp:9008 tcp:9008
python3 tests/e2e/axon_client.py ping
python3 tests/e2e/axon_client.py rpc dumpHierarchy '{"compress":true}'
```

`tests/e2e/axon_client.py` is a dependency-free (stdlib-only) WebSocket + JSON-RPC
client, usable as a library or a quick CLI.

## Testing

Two loops, both automated against the connected device:

```
scripts/test.sh              # unit tests + build + install + enable + all E2E
scripts/test.sh --unit       # JVM unit tests only (no device)
scripts/test.sh --e2e stage2 # one E2E stage
```

- **Unit** (`app/src/test/`, JUnit): pure logic — JSON-RPC routing/errors, node
  JSON (bounds/center, compress), gesture parsing/validation, frame framing.
- **E2E** (`tests/e2e/`, Python): the real tract over `adb forward` — each stage
  ships a `stageN_*.py` that drives the device and asserts behavior.

## Status

| Method | State |
|--------|-------|
| `ping` | ✅ |
| `dumpHierarchy` | ✅ |
| `gesture` | ✅ |
| `nodeAction` | ✅ |
| `globalAction` | 🔜 |
| `screenshot` | 🔜 |
| `setEventStream` + events | 🔜 |

See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) for the staged roadmap and
per-stage acceptance criteria.

## Test device

Development/CI device: Redmi Note 7 (lavender), Android 13 (API 33), arm64-v8a.
