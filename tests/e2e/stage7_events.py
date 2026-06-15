#!/usr/bin/env python3
"""
Stage 7 E2E — server-push events (screenChanged / toast) + setEventStream.

Uses three connections: a control connection to trigger things, a subscribed
reader, and an unsubscribed reader (to prove push goes only to subscribers).
"""

import socket
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402

LOGO_ID = f"{PKG}:id/imgLogo"


def connect_with_retry(attempts=15, delay=0.5):
    last = None
    for _ in range(attempts):
        try:
            return AxonWS(timeout=8.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect: {last}")


def read_events(ws, seconds):
    """Collect server-push events (text frames with an 'event' field) for a window."""
    out = []
    ws.sock.settimeout(0.4)
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            kind, val = ws.recv_message()
        except (socket.timeout, TimeoutError):
            continue
        except (OSError, AxonError):
            break
        if kind == "text" and isinstance(val, dict) and "event" in val:
            out.append(val)
    return out


def main() -> int:
    ok = True
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.2)

    ctl = connect_with_retry()
    sub = connect_with_retry()
    unsub = connect_with_retry()

    def check(label, cond, extra=""):
        nonlocal ok
        print(f"  [{'ok' if cond else 'FAIL'}] {label}" + ("" if cond else f"  {extra}"))
        if not cond:
            ok = False

    try:
        # subscribe only `sub`
        resp = sub.rpc("setEventStream", {"enabled": True})
        check("setEventStream -> enabled", resp.get("result", {}).get("enabled") is True, resp)
        read_events(sub, 0.8)   # drain anything from the launch

        # --- toast: tapping the logo shows a Toast ---
        ctl.rpc("nodeAction", {"by": "resourceId", "value": LOGO_ID, "action": "click"})
        evs = read_events(sub, 2.0)
        toast = next((e for e in evs if e.get("event") == "toast"), None)
        check("toast event delivered", toast is not None, evs)
        if toast:
            check("toast text + package", str(toast.get("text", "")).startswith("Axon")
                  and toast.get("package") == PKG, toast)

        # --- screenChanged: leave to home ---
        read_events(sub, 0.6)  # drain
        ctl.rpc("globalAction", {"action": "home"})
        sub_evs = read_events(sub, 2.0)
        unsub_evs = read_events(unsub, 1.0)

        sc = next((e for e in sub_evs if e.get("event") == "screenChanged"), None)
        check("screenChanged delivered to subscriber", sc is not None, sub_evs)
        if sc:
            check("screenChanged has screen(int) + package",
                  isinstance(sc.get("screen"), int) and isinstance(sc.get("package"), str), sc)

        check("unsubscribed connection got nothing", unsub_evs == [], unsub_evs)

        # restore app for following runs
        shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    finally:
        ctl.close()
        sub.close()
        unsub.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
