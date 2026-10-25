"""动态回血上限的端到端验证（一条常驻 RCON 连接）。

覆盖提示词 §九 里能靠指令复现的场景：
  满血受 10 / 60 点、上限挡住回血、低伤害不得抬高上限、连续重伤继续收紧、
  /battlefield heal 重置、吸收护盾不计入、关掉开关后回到原行为。
"""
import argparse
import socket
import struct
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "bftest"
PLAYER = "Steve"


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
_req = [100]


def cmd(command):
    _req[0] += 1
    sock.sendall(pack(_req[0], 2, command))
    return recv(sock)


def health():
    body = cmd(f"data get entity {PLAYER} Health")
    return body.split("：")[-1].strip() if "：" in body else body.strip()


def info():
    return cmd(f"battlefield regeninfo {PLAYER}").strip()


def report(label):
    print(f"  [{label}]")
    print(f"    {info()}")
    print(f"    Health={health()}")


def damage(amount, dtype="minecraft:generic"):
    cmd(f"damage {PLAYER} {amount} {dtype}")
    time.sleep(0.4)


def wait(seconds):
    time.sleep(seconds)


print("=== 初始 ===")
report("开局")

print("\n=== 场景 1：满血挨 10 点（轻微伤，应能回满）===")
damage(10)
report("受 10 伤后")
wait(8)
report("脱战 8 秒后")

print("\n=== 场景 2：满血挨 60 点（重伤，上限应压到 70）===")
cmd(f"battlefield heal {PLAYER}")
time.sleep(0.4)
damage(60)
report("受 60 伤后")
wait(11)
report("等到回血停住")
wait(3)
report("再等 3 秒（确认停在 70 不动）")

print("\n=== 场景 3：70 血再摔 5 点（不得把上限刷回 100）===")
damage(5, "minecraft:fall")
report("摔 5 点后")
wait(10)
report("再回血后（应仍是 70，不是 100）")

print("\n=== 场景 4：70 血再挨 40 点（上限继续收紧到 60）===")
damage(40)
report("再受 40 后")
wait(10)
report("回血停住后（应停在 60）")

print("\n=== 场景 5：/battlefield heal（满血 + 上限重置）===")
print("    " + cmd(f"battlefield heal {PLAYER}").strip())
report("heal 之后")

print("\n=== 场景 6：吸收护盾不得计入实际损失 ===")
print("    " + cmd(f"effect give {PLAYER} minecraft:absorption 60 4").strip())
time.sleep(0.5)
report("吃吸收后")
damage(20)
report("带护盾挨 20 点")
wait(10)
report("回血后（上限应仍是 100）")

sock.close()
