#!/usr/bin/env python3
"""
Stage 6 E2E — screenshot (takeScreenshot -> metadata + binary frame).

Verifies the two-message reply: JSON metadata then a binary frame
`[4-byte id BE][image bytes]`, that the framed id matches the request, that
`bytes` matches the payload length, that the payload is a real JPEG/PNG, and that
bad params are rejected.
"""

import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError  # noqa: E402
from _adb import PKG, shell  # noqa: E402

JPEG_MAGIC = b"\xff\xd8\xff"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def connect_with_retry(attempts=15, delay=0.5):
    last = None
    for _ in range(attempts):
        try:
            return AxonWS(timeout=10.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect: {last}")


def shoot(ws, params, req_id):
    """Send screenshot and collect (metadata, (frame_id, payload))."""
    ws.send_json({"id": req_id, "method": "screenshot", "params": params})
    meta = None
    binary = None
    for _ in range(4):
        kind, value = ws.recv_message()
        if kind == "text" and value.get("id") == req_id:
            meta = value
        elif kind == "binary":
            binary = value
        if meta is not None and binary is not None:
            break
    return meta, binary


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
        # JPEG
        meta, binary = shoot(ws, {"format": "jpeg", "quality": 80}, 1)
        res = (meta or {}).get("result", {})
        check("jpeg metadata present", res.get("format") == "jpeg"
              and res.get("width", 0) > 0 and res.get("height", 0) > 0
              and res.get("bytes", 0) > 0 and isinstance(res.get("screen"), int), res)
        check("binary frame received", binary is not None)
        if binary:
            frame_id, payload = binary
            check("framed id matches request", frame_id == 1, frame_id)
            check("payload length matches metadata.bytes", len(payload) == res.get("bytes"),
                  f"{len(payload)} vs {res.get('bytes')}")
            check("payload is a JPEG", payload[:3] == JPEG_MAGIC)

        time.sleep(1.2)  # takeScreenshot is rate-limited; space the calls out

        # PNG
        meta, binary = shoot(ws, {"format": "png"}, 2)
        res = (meta or {}).get("result", {})
        check("png metadata present", res.get("format") == "png" and res.get("bytes", 0) > 0, res)
        if binary:
            frame_id, payload = binary
            check("png framed id matches", frame_id == 2, frame_id)
            check("payload is a PNG", payload[:8] == PNG_MAGIC)

        # validation (no binary follows an error)
        r = ws.rpc("screenshot", {"format": "gif"})
        check("invalid format -> INVALID_PARAMS", r.get("error", {}).get("code") == "INVALID_PARAMS", r)

        # a non-integer id can't be encoded into the binary header -> rejected
        ws.send_json({"id": "not-an-int", "method": "screenshot", "params": {}})
        kind, value = ws.recv_message()
        check("string id -> INVALID_PARAMS (no binary)",
              kind == "text" and value.get("id") == "not-an-int"
              and value.get("error", {}).get("code") == "INVALID_PARAMS", value)
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
