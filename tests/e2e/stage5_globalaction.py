#!/usr/bin/env python3
"""
Stage 5 E2E — globalAction (performGlobalAction).

Verifies the mapping table works and validation rejects junk. `home` is checked
for a real effect (we leave our app); `notifications`/`recents` are exercised and
then dismissed so the device is left in a sane state.
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

    def ga(action):
        return ws.rpc("globalAction", {"action": action})

    def success(resp):
        return resp.get("result", {}).get("success") is True

    def err(resp):
        return resp.get("error", {}).get("code")

    def foreground_pkg():
        r = ws.rpc("dumpHierarchy", {"maxDepth": 0})
        return r.get("result", {}).get("package")

    try:
        # validation
        check("missing action -> INVALID_PARAMS", err(ws.rpc("globalAction", {})) == "INVALID_PARAMS")
        check("unknown action -> INVALID_PARAMS", err(ga("teleport")) == "INVALID_PARAMS")

        # home: real effect — foreground leaves our app
        check("home -> success", success(ga("home")))
        time.sleep(1.0)
        left = foreground_pkg()
        check("home left our app", left != PKG, f"foreground still {left}")

        # restore, then notifications shade + dismiss
        shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
        time.sleep(1.0)
        check("notifications -> success", success(ga("notifications")))
        time.sleep(0.8)
        check("back (close shade) -> success", success(ga("back")))
        time.sleep(0.5)

        # recents + dismiss back home
        check("recents -> success", success(ga("recents")))
        time.sleep(0.8)
        check("home (leave recents) -> success", success(ga("home")))

        # leave the device on our app for following stages
        shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
