#!/usr/bin/env python3
"""
Stage 3 E2E — gesture via dispatchGesture.

Drives our own StatusActivity: finds the RU/ENG toggle by dumping the tree, taps
it with a real gesture, and confirms the tap had a visible effect (the subtitle
switches language). Also checks: the reply arrives only AFTER the gesture
completes (timing), bad params are rejected, and a multi-point swipe succeeds.
"""

import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402

SUBTITLE_EN = "UI Automation Agent"
SUBTITLE_RU = "Агент автоматизации UI"


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


def dump(ws):
    return ws.rpc("dumpHierarchy", {})["result"]


def find_center(root, text):
    for n in flatten(root):
        if n.get("text") == text and "center" in n:
            return n["center"]
    return None


def tap(ws, center, duration=60):
    return ws.rpc("gesture", {
        "strokes": [{"points": [{"x": center["x"], "y": center["y"]}],
                     "startTime": 0, "duration": duration}]
    })


def subtitle_of(root):
    for n in flatten(root):
        if n.get("text") in (SUBTITLE_EN, SUBTITLE_RU):
            return n["text"]
    return None


def main() -> int:
    ok = True
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.5)
    ws = connect_with_retry()
    try:
        # --- real tap with a visible effect: flip language via the toggle ---
        root = dump(ws)
        current = subtitle_of(root)
        target_text, expect = ("ENG", SUBTITLE_EN) if current == SUBTITLE_RU else ("RU", SUBTITLE_RU)

        center = find_center(root, target_text)
        if not center:
            print(f"  [FAIL] could not find '{target_text}' button in dump")
            return 1

        resp = tap(ws, center)
        if resp.get("result", {}).get("success") is True:
            print(f"  [ok] tap '{target_text}' -> success")
        else:
            print(f"  [FAIL] tap response: {resp}")
            ok = False

        time.sleep(1.5)  # activity recreates on locale change
        after = subtitle_of(dump(ws))
        if after == expect:
            print(f"  [ok] tap had effect — subtitle now {after!r}")
        else:
            print(f"  [FAIL] subtitle is {after!r}, expected {expect!r}")
            ok = False

        # --- timing: reply only after onCompleted (~duration) ---
        root = dump(ws)
        mid = {"x": root["bounds"]["right"] // 2, "y": root["bounds"]["bottom"] // 2} \
            if "bounds" in root else {"x": 540, "y": 1200}
        t0 = time.time()
        tap(ws, mid, duration=500)
        elapsed = time.time() - t0
        if elapsed >= 0.3:
            print(f"  [ok] reply waited for completion ({elapsed*1000:.0f}ms for a 500ms gesture)")
        else:
            print(f"  [FAIL] reply returned too early ({elapsed*1000:.0f}ms)")
            ok = False

        # --- validation: empty strokes -> INVALID_PARAMS ---
        resp = ws.rpc("gesture", {"strokes": []})
        if resp.get("error", {}).get("code") == "INVALID_PARAMS":
            print("  [ok] empty strokes -> INVALID_PARAMS")
        else:
            print(f"  [FAIL] expected INVALID_PARAMS, got {resp}")
            ok = False

        # --- swipe: 2-point stroke succeeds ---
        resp = ws.rpc("gesture", {"strokes": [{
            "points": [{"x": mid["x"], "y": mid["y"] + 300}, {"x": mid["x"], "y": mid["y"] - 300}],
            "startTime": 0, "duration": 250}]})
        if resp.get("result", {}).get("success") is True:
            print("  [ok] swipe -> success")
        else:
            print(f"  [FAIL] swipe response: {resp}")
            ok = False
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
