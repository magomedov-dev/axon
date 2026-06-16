#!/usr/bin/env python3
"""
Stage 4 E2E — nodeAction (find on the fly + performAction).

Covers the happy paths and every distinct error on our own StatusActivity:
  - NODE_NOT_FOUND, AMBIGUOUS_MATCH (+ index resolution),
  - ACTION_NOT_SUPPORTED, NOT_EDITABLE, STALE-free success,
  - click with a verified visible effect, setText/clear on the probe field.
"""

import sys
import time
from collections import Counter

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402

SUBTITLE_EN = "UI Automation Agent"
SUBTITLE_RU = "Агент автоматизации UI"
PROBE_ID = f"{PKG}:id/probeField"


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


def subtitle_of(root):
    for n in flatten(root):
        if n.get("text") in (SUBTITLE_EN, SUBTITLE_RU):
            return n["text"]
    return None


def node_action(ws, **params):
    return ws.rpc("nodeAction", params)


def err(resp):
    return resp.get("error", {}).get("code")


def main() -> int:
    ok = True
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.5)
    ws = connect_with_retry()

    def check(label, cond, extra=""):
        nonlocal ok
        if cond:
            print(f"  [ok] {label}")
        else:
            print(f"  [FAIL] {label} {extra}")
            ok = False

    try:
        # A. NODE_NOT_FOUND
        r = node_action(ws, by="resourceId", value=f"{PKG}:id/nope", action="click")
        check("missing node -> NODE_NOT_FOUND", err(r) == "NODE_NOT_FOUND", r)

        # B. click a real button -> visible effect (flip language)
        root = dump(ws)
        cur = subtitle_of(root)
        btn_id, expect = (f"{PKG}:id/btnLangRu", SUBTITLE_RU) if cur == SUBTITLE_EN \
            else (f"{PKG}:id/btnLangEn", SUBTITLE_EN)
        r = node_action(ws, by="resourceId", value=btn_id, action="click")
        check("click language button -> success", r.get("result", {}).get("success") is True, r)
        time.sleep(1.5)
        check("click had visible effect", subtitle_of(dump(ws)) == expect)

        # C. ACTION_NOT_SUPPORTED: click the (non-clickable) title TextView
        r = node_action(ws, by="text", value="Axon", action="click")
        check("click non-clickable -> ACTION_NOT_SUPPORTED", err(r) == "ACTION_NOT_SUPPORTED", r)

        # D. NOT_EDITABLE: setText on the title TextView
        r = node_action(ws, by="text", value="Axon", action="setText", text="x")
        check("setText on non-editable -> NOT_EDITABLE", err(r) == "NOT_EDITABLE", r)

        # E. setText / clear on the probe field
        # (the EditText is inside a TextInputLayout: when empty its accessibility
        # text is the hint, and text changes propagate to the tree one frame later
        # — so settle briefly and assert the *value*, not emptiness.)
        def probe_text():
            time.sleep(0.6)
            n = next((x for x in flatten(dump(ws)) if x.get("resourceId") == PROBE_ID), None)
            return (n or {}).get("text")

        r = node_action(ws, by="resourceId", value=PROBE_ID, action="setText", text="axon-probe-42")
        check("setText probe -> success", r.get("result", {}).get("success") is True, r)
        check("probe text updated", probe_text() == "axon-probe-42")

        r = node_action(ws, by="resourceId", value=PROBE_ID, action="clear")
        check("clear probe -> success", r.get("result", {}).get("success") is True, r)
        check("probe value removed", probe_text() != "axon-probe-42")

        # E2. match modes: exact misses "Axo", contains/regex find the "Axon" title
        r = node_action(ws, by="text", value="Axo", action="click")
        check("exact 'Axo' -> NODE_NOT_FOUND", err(r) == "NODE_NOT_FOUND", r)
        r = node_action(ws, by="text", value="Axo", action="click", match="contains")
        check("contains 'Axo' finds title", err(r) == "ACTION_NOT_SUPPORTED", r)
        r = node_action(ws, by="text", value="^Ax", action="click", match="regex")
        check("regex '^Ax' finds title", err(r) == "ACTION_NOT_SUPPORTED", r)

        # F. AMBIGUOUS_MATCH + index resolution
        classes = Counter(n.get("class") for n in flatten(dump(ws)) if n.get("class"))
        dup_class = next((c for c, n in classes.items() if n >= 2), None)
        if dup_class:
            r = node_action(ws, by="class", value=dup_class, action="click")
            check(f"ambiguous '{dup_class}' -> AMBIGUOUS_MATCH", err(r) == "AMBIGUOUS_MATCH", r)
            r = node_action(ws, by="class", value=dup_class, action="click", index=0)
            check("index resolves ambiguity (no AMBIGUOUS_MATCH)", err(r) != "AMBIGUOUS_MATCH", r)
        else:
            print("  [WARN] no duplicated class found; skipping ambiguity test")
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
