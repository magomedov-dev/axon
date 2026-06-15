#!/usr/bin/env python3
"""
Stage 9 E2E — the status console.

Reads the StatusActivity through the agent itself and verifies it is a live
control panel: service/server indicators, a live connection count, and a server
on/off switch that actually stops the WebSocket server (restored afterwards by
restarting the accessibility service).
"""

import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, SERVICE, shell  # noqa: E402

SERVICE_STATE_ID = f"{PKG}:id/txtServiceState"
SERVER_STATE_ID = f"{PKG}:id/txtServerState"
SWITCH_ID = f"{PKG}:id/swServer"
ENABLED_TEXTS = ("Enabled", "Включена")


def flatten(node):
    yield node
    for child in node.get("children", []):
        yield from flatten(child)


def connect_with_retry(attempts=20, delay=0.5):
    last = None
    for _ in range(attempts):
        try:
            return AxonWS(timeout=8.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect: {last}")


def node_by_id(root, rid):
    return next((n for n in flatten(root) if n.get("resourceId") == rid), None)


def server_count(ws):
    root = ws.rpc("dumpHierarchy", {})["result"]
    node = node_by_id(root, SERVER_STATE_ID)
    text = (node or {}).get("text", "")
    nums = re.findall(r"\d+", text)            # e.g. [9008, 2]
    return text, (int(nums[-1]) if len(nums) >= 2 else None)


def server_down():
    for _ in range(8):
        try:
            w = AxonWS(timeout=2.0).connect()
            w.close()
            return False
        except (OSError, AxonError):
            time.sleep(0.5)
    return True


def restart_service():
    # Reliably re-bind the accessibility service so onServiceConnected -> startServer
    # runs again. force-stop kills the process and clears the enabled-services list;
    # re-adding the service is then a clean (absent -> present) transition that the
    # framework actually acts on (toggling accessibility_enabled alone does not).
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    shell("settings", "put", "secure", "enabled_accessibility_services", SERVICE)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(4.0)


def main() -> int:
    ok = True
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.2)
    ws = connect_with_retry()

    def check(label, cond, extra=""):
        nonlocal ok
        print(f"  [{'ok' if cond else 'FAIL'}] {label}" + ("" if cond else f"  {extra}"))
        if not cond:
            ok = False

    try:
        root = ws.rpc("dumpHierarchy", {})["result"]
        svc = node_by_id(root, SERVICE_STATE_ID)
        check("service indicator shows enabled", svc is not None and svc.get("text") in ENABLED_TEXTS,
              (svc or {}).get("text"))
        check("server switch present", node_by_id(root, SWITCH_ID) is not None)

        # the count is refreshed by a 1s UI tick — settle before reading it
        time.sleep(1.6)
        text, count = server_count(ws)
        check("server indicator shows :9008", "9008" in text, text)
        check("connection count = 1 (this client)", count == 1, text)

        # live count: a second connection bumps it
        ws2 = connect_with_retry()
        time.sleep(1.6)  # let the 1s UI tick refresh
        _, count2 = server_count(ws)
        check("connection count = 2 with a second client", count2 == 2, count2)
        ws2.close()
        time.sleep(1.6)
        _, count3 = server_count(ws)
        check("connection count back to 1", count3 == 1, count3)

        # the switch really stops the server
        try:
            ws.rpc("nodeAction", {"by": "resourceId", "value": SWITCH_ID, "action": "click"})
        except (OSError, AxonError):
            pass  # the server may drop us as it stops
        ws.close()
        check("switch off stops the WebSocket server", server_down())
    finally:
        try:
            ws.close()
        except Exception:
            pass
        # restore the server by restarting the accessibility service
        restart_service()

    restored = connect_with_retry()
    try:
        check("server restored after service restart",
              restored.rpc("ping").get("result", {}).get("pong") is True)
    finally:
        restored.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
