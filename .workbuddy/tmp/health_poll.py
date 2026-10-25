"""高频采样某玩家的 Health，用一条常驻 RCON 连接，避免每次重连的 0.3~1s 开销。

用法:
    python health_poll.py --player Steve --seconds 12 [--interval 0.05]
输出: 相对时间(ms) + Health
"""
import argparse
import socket
import struct
import time


def pack(req_id, ptype, body):
    payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def recv(sock):
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
    return req_id, ptype, data[8:-2].decode("utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="bftest")
    ap.add_argument("--player", default="Steve")
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--interval", type=float, default=0.05)
    args = ap.parse_args()

    sock = socket.create_connection((args.host, args.port), timeout=20)
    sock.sendall(pack(1, 3, args.password))
    recv(sock)

    cmd = f"data get entity {args.player} Health"
    start = time.monotonic()
    req = 100
    while time.monotonic() - start < args.seconds:
        req += 1
        sock.sendall(pack(req, 2, cmd))
        _, _, body = recv(sock)
        t = (time.monotonic() - start) * 1000
        value = body.split("：")[-1].strip() if "：" in body else body.strip()
        print(f"{t:7.0f}ms  {value}")
        time.sleep(args.interval)
    sock.close()


if __name__ == "__main__":
    main()
