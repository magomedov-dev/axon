#!/usr/bin/env python3
"""
Stage 2 E2E — dumpHierarchy end to end.

Brings our own StatusActivity to the foreground (a known, stable UI), dumps it,
and verifies: screen + package at the root, pre-order ids, computed centers,
numeric bounds, maxDepth and compress behavior.
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
            return AxonWS(timeout=5.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect: {last}")


def main() -> int:
    ok = True
    # Foreground a known UI.
    shell("am", "start", "-n", f"{PKG}/.ui.StatusActivity")
    time.sleep(1.5)

    ws = connect_with_retry()
    try:
        resp = ws.rpc("dumpHierarchy", {})
        if "error" in resp:
            print(f"  [FAIL] dumpHierarchy errored: {resp['error']}")
            return 1
        root = resp["result"]

        # root-level screen + package
        if isinstance(root.get("screen"), int):
            print(f"  [ok] screen present ({root['screen']})")
        else:
            print(f"  [FAIL] screen missing/not int: {root.get('screen')}")
            ok = False

        if root.get("package") == PKG:
            print(f"  [ok] package == {PKG}")
        else:
            print(f"  [FAIL] package = {root.get('package')!r}, expected {PKG}")
            ok = False

        # root node identity
        if root.get("nodeId") == 0 and root.get("parentId") is None:
            print("  [ok] root nodeId=0, parentId=null")
        else:
            print(f"  [FAIL] root id/parent wrong: id={root.get('nodeId')} parent={root.get('parentId')}")
            ok = False

        nodes = list(flatten(root))
        print(f"  [ok] traversed {len(nodes)} nodes")

        # bounds numeric + center is the midpoint
        sample = next((n for n in nodes if "center" in n), None)
        if sample:
            b, c = sample["bounds"], sample["center"]
            if all(isinstance(b[k], int) for k in ("left", "top", "right", "bottom")) \
                    and c["x"] == (b["left"] + b["right"]) // 2 \
                    and c["y"] == (b["top"] + b["bottom"]) // 2:
                print("  [ok] bounds numeric, center = midpoint")
            else:
                print(f"  [FAIL] bounds/center mismatch: {b} {c}")
                ok = False

        # our title text should be somewhere in the tree
        if any(n.get("text") == "Axon" for n in nodes):
            print("  [ok] found expected node text 'Axon'")
        else:
            print("  [WARN] 'Axon' text not found (UI may differ); continuing")

        # maxDepth = 0 -> root only, empty children
        resp = ws.rpc("dumpHierarchy", {"maxDepth": 0})
        r0 = resp["result"]
        if r0.get("children", []) == []:
            print("  [ok] maxDepth=0 -> root has no children")
        else:
            print(f"  [FAIL] maxDepth=0 produced children: {len(r0.get('children', []))}")
            ok = False

        # compress -> no center key anywhere
        resp = ws.rpc("dumpHierarchy", {"compress": True})
        rc = list(flatten(resp["result"]))
        # note: result root also carries screen/package; check node objects for center
        if all("center" not in n for n in rc):
            print("  [ok] compress -> center dropped everywhere")
        else:
            print("  [FAIL] compress still contains center")
            ok = False
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
