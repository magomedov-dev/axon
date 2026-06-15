#!/usr/bin/env python3
"""
Stage 1 E2E — WebSocket + JSON-RPC tract end to end via `adb forward`.

Proves: socket connects, requests route, ping replies, and malformed / unknown
requests come back as structured errors with the id echoed (or null).
"""

import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from axon_client import AxonWS, AxonError, OP_TEXT  # noqa: E402


def connect_with_retry(attempts: int = 15, delay: float = 0.5) -> AxonWS:
    """The service starts the server asynchronously after enable; retry briefly."""
    last = None
    for _ in range(attempts):
        try:
            return AxonWS(timeout=5.0).connect()
        except (OSError, AxonError) as e:
            last = e
            time.sleep(delay)
    raise SystemExit(f"could not connect to ws://127.0.0.1:9008 — {last}")


def main() -> int:
    ok = True
    ws = connect_with_retry()
    print("  [ok] connected — server is listening")
    try:
        # 1. ping
        resp = ws.rpc("ping", req_id=1)
        if resp.get("id") == 1 and resp.get("result", {}).get("pong") is True \
                and isinstance(resp["result"].get("ts"), int):
            print(f"  [ok] ping -> pong (ts={resp['result']['ts']})")
        else:
            print(f"  [FAIL] unexpected ping response: {resp}")
            ok = False

        # 2. unknown method -> METHOD_NOT_FOUND, id echoed
        resp = ws.rpc("doesNotExist", req_id=2)
        if resp.get("id") == 2 and resp.get("error", {}).get("code") == "METHOD_NOT_FOUND":
            print("  [ok] unknown method -> METHOD_NOT_FOUND")
        else:
            print(f"  [FAIL] expected METHOD_NOT_FOUND, got: {resp}")
            ok = False

        # 3. malformed JSON -> PARSE_ERROR, id null
        ws._send_frame(OP_TEXT, b"{ this is not json")
        kind, value = ws.recv_message()
        if kind == "text" and value.get("id") is None \
                and value.get("error", {}).get("code") == "PARSE_ERROR":
            print("  [ok] malformed JSON -> PARSE_ERROR (id null)")
        else:
            print(f"  [FAIL] expected PARSE_ERROR, got: {value}")
            ok = False

        # 4. missing method -> INVALID_REQUEST
        ws.send_json({"id": 4})
        while True:
            kind, value = ws.recv_message()
            if kind == "text" and value.get("id") == 4:
                break
        if value.get("error", {}).get("code") == "INVALID_REQUEST":
            print("  [ok] missing method -> INVALID_REQUEST")
        else:
            print(f"  [FAIL] expected INVALID_REQUEST, got: {value}")
            ok = False
    finally:
        ws.close()

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
