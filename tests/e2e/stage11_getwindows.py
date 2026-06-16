#!/usr/bin/env python3
"""
E2E — getWindows (enumerate all interactive windows).

Verifies window metadata for the whole window stack, that our app appears as an
application window, that trees are omitted by default, and that includeTree
attaches a per-window node tree.
"""

import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402


def flatten(node):
    yield node
    for child in node.get("children", []):
        yield from flatten(child)


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
    time.sleep(1.3)
    ws = connect_with_retry()

    def check(label, cond, extra=""):
        nonlocal ok
        print(f"  [{'ok' if cond else 'FAIL'}] {label}" + ("" if cond else f"  {extra}"))
        if not cond:
            ok = False

    try:
        res = ws.rpc("getWindows", {})["result"]
        windows = res.get("windows")
        check("result has screen + windows[]", isinstance(res.get("screen"), int)
              and isinstance(windows, list) and len(windows) >= 1, res.get("screen"))

        app = next((w for w in windows if w.get("package") == PKG), None)
        check("our app appears as a window", app is not None,
              [w.get("package") for w in (windows or [])])
        if app:
            check("app window metadata", app.get("type") == "application"
                  and app.get("active") is True
                  and isinstance(app.get("windowId"), int)
                  and isinstance(app.get("layer"), int)
                  and all(isinstance(app["bounds"][k], int) for k in ("left", "top", "right", "bottom")),
                  app)
            check("no tree without includeTree", "root" not in app)

        # includeTree attaches each window's node tree
        res = ws.rpc("getWindows", {"includeTree": True, "compress": True})["result"]
        app = next((w for w in res["windows"] if w.get("package") == PKG), None)
        check("includeTree attaches a root tree", app is not None and "root" in app)
        if app and "root" in app:
            root = app["root"]
            check("root tree is well-formed", root.get("nodeId") == 0)
            check("app window tree contains 'Axon'",
                  any(n.get("text") == "Axon" for n in flatten(root)))

        # window-scoped nodeAction: act inside a specific window by windowId
        if app:
            win_id = app["windowId"]
            probe_id = f"{PKG}:id/probeField"
            r = ws.rpc("nodeAction", {"by": "resourceId", "value": probe_id,
                                      "action": "setText", "text": "win-scoped", "windowId": win_id})
            check("nodeAction with windowId -> success", r.get("result", {}).get("success") is True, r)
            time.sleep(0.6)
            node = next((n for n in flatten(ws.rpc("dumpHierarchy", {})["result"])
                         if n.get("resourceId") == probe_id), None)
            check("window-scoped setText took effect", (node or {}).get("text") == "win-scoped",
                  (node or {}).get("text"))
            ws.rpc("nodeAction", {"by": "resourceId", "value": probe_id, "action": "clear", "windowId": win_id})

        r = ws.rpc("nodeAction", {"by": "text", "value": "Axon", "action": "click", "windowId": 987654321})
        check("nodeAction unknown windowId -> WINDOW_NOT_FOUND",
              r.get("error", {}).get("code") == "WINDOW_NOT_FOUND", r)

        # window-scoped dumpHierarchy
        if app:
            res = ws.rpc("dumpHierarchy", {"windowId": app["windowId"], "compress": True})["result"]
            check("dumpHierarchy windowId dumps that window",
                  res.get("package") == PKG and res.get("nodeId") == 0, res.get("package"))
        r = ws.rpc("dumpHierarchy", {"windowId": 987654321})
        check("dumpHierarchy unknown windowId -> WINDOW_NOT_FOUND",
              r.get("error", {}).get("code") == "WINDOW_NOT_FOUND", r)
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
