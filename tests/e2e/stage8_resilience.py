#!/usr/bin/env python3
"""
Stage 8 E2E — resilience.

Verifies the service runs as a foreground service and that the WebSocket server
keeps answering app-level ping after the controlling app is backgrounded (the
foreground promotion is what keeps it alive).
"""

import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402


def connect_with_retry(attempts=15, delay=0.5):
    last = None
    for _ in range(attempts):
        try:
            return AxonWS(timeout=8.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect: {last}")


def is_foreground():
    out = shell("dumpsys", "activity", "services", PKG)
    return "isForeground=true" in out


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

    def ping_ok():
        r = ws.rpc("ping")
        return r.get("result", {}).get("pong") is True

    try:
        check("ping -> pong", ping_ok())
        check("service is foreground", is_foreground())

        # background the app
        ws.rpc("globalAction", {"action": "home"})
        time.sleep(2.0)

        check("server still answers ping after backgrounding", ping_ok())
        check("service still foreground after backgrounding", is_foreground())

        shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
