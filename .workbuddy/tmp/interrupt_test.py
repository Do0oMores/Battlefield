"""验证「回血过程中受伤立即停止，脱战后重新按点上涨」。

时间线（一条常驻 RCON 连接，避免重连误差）：
  t=0      damage 40          -> 60
  t=0..7   poll（6 秒静默，然后 1 点 1 点涨）
  t=8      damage 15          -> 掉到 65 左右，回血必须立刻停
  t=8..15  poll               -> 必须一动不动
输出只保留数值发生变化的时刻。
"""
import socket
import struct
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "bftest"


def pack(req_id, ptype, body):
    payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def recv(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        return ""
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    return data[8:-2].decode("utf-8", errors="replace")


sock = socket.create_connection((HOST, PORT), timeout=20)
sock.sendall(pack(1, 3, PASSWORD))
recv(sock)
req = 100


def send(cmd):
    global req
    req += 1
    sock.sendall(pack(req, 2, cmd))
    return recv(sock)


def health():
    body = send("data get entity Steve Health")
    return body.split("：")[-1].strip() if "：" in body else body.strip()


start = time.monotonic()
send("damage Steve 40 minecraft:generic")
print(f"{0:6.0f}ms  {health():8s}  <- damage 40")

prev = None
second_done = False
while True:
    t = (time.monotonic() - start) * 1000
    if t > 15000:
        break
    if not second_done and t >= 8000:
        send("damage Steve 15 minecraft:generic")
        print(f"{t:6.0f}ms  {health():8s}  <- damage 15（回血中途打断）")
        second_done = True
        prev = None
    v = health()
    if v != prev:
        print(f"{t:6.0f}ms  {v}")
        prev = v
    time.sleep(0.06)

sock.close()
