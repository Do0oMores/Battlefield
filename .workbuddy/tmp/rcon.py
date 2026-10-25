#!/usr/bin/env python3
"""Minimal RCON client for driving the test server."""
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 25575
PASS = "bftest"


def _pack(req_id, ptype, body):
    payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _recv(sock):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return None, None, None
    (length,) = struct.unpack("<i", raw_len)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return req_id, ptype, body


def run(cmds):
    sock = socket.create_connection((HOST, PORT), timeout=20)
    sock.sendall(_pack(1, 3, PASS))
    req_id, _, _ = _recv(sock)
    if req_id == -1:
        print("AUTH FAILED")
        return 1

    for cmd in cmds:
        sock.sendall(_pack(2, 2, cmd))
        _, _, body = _recv(sock)
        print(f"> {cmd}\n{body}\n")

    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
