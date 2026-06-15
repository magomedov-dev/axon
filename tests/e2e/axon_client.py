#!/usr/bin/env python3
"""
Axon test client — minimal, dependency-free WebSocket + JSON-RPC client.

Implements just enough of RFC 6455 (client side) to drive the on-device agent
over `adb forward`, with no pip dependencies (pure stdlib). Handles the three
message kinds Axon uses in one socket:

  - JSON responses     (text frame, has "id" + "result"/"error")
  - JSON server events (text frame, has "event", no "id")
  - binary frames      (screenshots: [4-byte id uint32 BE][image bytes])

Usable as a library (class AxonWS) or as a tiny CLI for manual pokes:

  python3 axon_client.py ping
  python3 axon_client.py rpc dumpHierarchy '{"maxDepth":2,"compress":true}'
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
import sys
import time

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 9008

OP_CONT = 0x0
OP_TEXT = 0x1
OP_BIN = 0x2
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA


class AxonError(Exception):
    pass


class AxonWS:
    def __init__(
        self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, timeout: float = 10.0
    ):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.sock: socket.socket | None = None
        self._buf = b""
        self._next_id = 1

    # ---- connection -------------------------------------------------------
    def connect(self) -> "AxonWS":
        self.sock = socket.create_connection(
            (self.host, self.port), timeout=self.timeout
        )
        self.sock.settimeout(self.timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET / HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        resp = self._read_until(b"\r\n\r\n")
        status = resp.split(b"\r\n", 1)[0].decode(errors="replace")
        if "101" not in status:
            raise AxonError(f"WebSocket handshake failed: {status}")
        return self

    def close(self) -> None:
        if self.sock is not None:
            try:
                self._send_frame(OP_CLOSE, b"")
            except OSError:
                pass
            self.sock.close()
            self.sock = None

    def __enter__(self) -> "AxonWS":
        return self.connect()

    def __exit__(self, *exc) -> None:
        self.close()

    # ---- low-level IO -----------------------------------------------------
    def _read_until(self, marker: bytes) -> bytes:
        while marker not in self._buf:
            chunk = self.sock.recv(4096)  # pyright: ignore[reportOptionalMemberAccess]
            if not chunk:
                raise AxonError("connection closed during handshake")
            self._buf += chunk
        idx = self._buf.index(marker) + len(marker)
        head, self._buf = self._buf[:idx], self._buf[idx:]
        return head

    def _recv_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)  # pyright: ignore[reportOptionalMemberAccess]
            if not chunk:
                raise AxonError("connection closed")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        fin_op = 0x80 | opcode
        header = bytearray([fin_op])
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack("!H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", length)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)  # pyright: ignore[reportOptionalMemberAccess]

    def _recv_frame(self) -> tuple[int, bytes]:
        """Return (opcode, payload). Transparently answers ping with pong."""
        while True:
            b0, b1 = self._recv_exact(2)
            opcode = b0 & 0x0F
            masked = (b1 & 0x80) != 0
            length = b1 & 0x7F
            if length == 126:
                (length,) = struct.unpack("!H", self._recv_exact(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", self._recv_exact(8))
            mask = self._recv_exact(4) if masked else b""
            payload = self._recv_exact(length)
            if masked:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == OP_PING:
                self._send_frame(OP_PONG, payload)
                continue
            if opcode == OP_PONG:
                continue
            return opcode, payload

    # ---- message layer ----------------------------------------------------
    def send_json(self, obj: dict) -> None:
        self._send_frame(OP_TEXT, json.dumps(obj).encode())

    def recv_message(self) -> tuple[str, object]:
        """
        Return one application message as (kind, value):
          ("text",   dict)   parsed JSON (response or event)
          ("binary", (id, bytes))   binary frame split into header id + payload
        """
        opcode, payload = self._recv_frame()
        if opcode == OP_TEXT:
            return "text", json.loads(payload.decode())
        if opcode == OP_BIN:
            if len(payload) < 4:
                raise AxonError("binary frame shorter than 4-byte header")
            (frame_id,) = struct.unpack("!I", payload[:4])
            return "binary", (frame_id, payload[4:])
        if opcode == OP_CLOSE:
            raise AxonError("server closed the connection")
        raise AxonError(f"unexpected opcode {opcode}")

    # ---- JSON-RPC convenience --------------------------------------------
    def rpc(
        self,
        method: str,
        params: dict | None = None,
        *,
        req_id: int | None = None,
        collect_events: bool = False,
    ) -> dict:
        """
        Send a request and return its matching response dict ({"id","result"} or
        {"id","error"}). Events and binary frames received in between are skipped
        (or, if collect_events, attached under "_events").
        """
        if req_id is None:
            req_id = self._next_id
            self._next_id += 1
        msg = {"id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        self.send_json(msg)
        events = []
        while True:
            kind, value = self.recv_message()
            if kind == "text" and value.get("id") == req_id:  # pyright: ignore[reportAttributeAccessIssue]
                if collect_events:
                    value["_events"] = events  # pyright: ignore[reportIndexIssue]
                return value  # pyright: ignore[reportReturnType]
            if kind == "text" and "event" in value:  # pyright: ignore[reportOperatorIssue]
                events.append(value)
            # binary frames belonging to other requests are ignored here


# ---- tiny CLI for manual use ----------------------------------------------
def _main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cmd = argv[0]
    with AxonWS() as ws:
        if cmd == "ping":
            t0 = time.time()
            resp = ws.rpc("ping")
            print(json.dumps(resp))
            print(f"# round-trip {1000 * (time.time() - t0):.1f} ms", file=sys.stderr)
        elif cmd == "rpc":
            method = argv[1]
            params = json.loads(argv[2]) if len(argv) > 2 else None
            print(json.dumps(ws.rpc(method, params), ensure_ascii=False))
        else:
            print(f"unknown command: {cmd}", file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
