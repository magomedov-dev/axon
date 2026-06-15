#!/usr/bin/env python3
"""
Stage 10 E2E — full end-to-end scenario.

One cohesive flow exercising the whole tract together: ping, dumpHierarchy,
gesture (with the resulting toast event), nodeAction, screenshot (binary frame),
and globalAction (with the resulting screenChanged event). A final integration
smoke over everything built in Stages 1-9.
"""

import socket
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402

LOGO_ID = f"{PKG}:id/imgLogo"
PROBE_ID = f"{PKG}:id/probeField"
JPEG_MAGIC = b"\xff\xd8\xff"


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


def read_events(ws, seconds):
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


def node_by_id(root, rid):
    return next((n for n in flatten(root) if n.get("resourceId") == rid), None)


def screenshot(ws, req_id):
    ws.send_json({"id": req_id, "method": "screenshot", "params": {"format": "jpeg"}})
    meta = binary = None
    for _ in range(4):
        kind, value = ws.recv_message()
        if kind == "text" and value.get("id") == req_id:
            meta = value
        elif kind == "binary":
            binary = value
        if meta and binary:
            break
    return meta, binary


def main() -> int:
    ok = True
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.3)
    ctl = connect_with_retry()
    sub = connect_with_retry()

    def check(label, cond, extra=""):
        nonlocal ok
        print(f"  [{'ok' if cond else 'FAIL'}] {label}" + ("" if cond else f"  {extra}"))
        if not cond:
            ok = False

    try:
        sub.rpc("setEventStream", {"enabled": True})
        read_events(sub, 0.6)

        # 1. ping
        check("ping", ctl.rpc("ping").get("result", {}).get("pong") is True)

        # 2. dumpHierarchy
        root = ctl.rpc("dumpHierarchy", {})["result"]
        check("dump shows our package", root.get("package") == PKG, root.get("package"))
        logo = node_by_id(root, LOGO_ID)
        check("dump found the logo node", logo is not None and "center" in logo)

        # 3. gesture tap on the logo -> toast event
        if logo:
            r = ctl.rpc("gesture", {"strokes": [
                {"points": [logo["center"]], "startTime": 0, "duration": 60}]})
            check("gesture tap", r.get("result", {}).get("success") is True, r)
            evs = read_events(sub, 2.0)
            check("gesture produced a toast event",
                  any(e.get("event") == "toast" for e in evs), evs)

        # 4. nodeAction setText + verify
        ctl.rpc("nodeAction", {"by": "resourceId", "value": PROBE_ID,
                               "action": "setText", "text": "e2e-scenario"})
        time.sleep(0.6)
        probe = node_by_id(ctl.rpc("dumpHierarchy", {})["result"], PROBE_ID)
        check("nodeAction setText took effect", (probe or {}).get("text") == "e2e-scenario",
              (probe or {}).get("text"))
        ctl.rpc("nodeAction", {"by": "resourceId", "value": PROBE_ID, "action": "clear"})

        # 5. screenshot (binary frame)
        meta, binary = screenshot(ctl, 777)
        res = (meta or {}).get("result", {})
        check("screenshot metadata + binary", binary is not None and res.get("bytes", 0) > 0)
        if binary:
            frame_id, payload = binary
            check("screenshot frame id + JPEG payload",
                  frame_id == 777 and len(payload) == res.get("bytes") and payload[:3] == JPEG_MAGIC)

        # 6. globalAction home -> screenChanged event
        read_events(sub, 0.4)
        ctl.rpc("globalAction", {"action": "home"})
        evs = read_events(sub, 2.0)
        check("globalAction produced screenChanged",
              any(e.get("event") == "screenChanged" for e in evs), evs)

        shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
        check("ping still alive at the end", ctl.rpc("ping").get("result", {}).get("pong") is True)
    finally:
        ctl.close()
        sub.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
