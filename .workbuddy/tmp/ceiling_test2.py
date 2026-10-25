"""补充验证：吸收护盾、回血中途再受伤、关闭开关。"""
import socket
import struct
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "bftest"
PLAYER = "Steve"


def pack(rid, ptype, body):
    payload = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
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
_r = [100]


def cmd(c):
    _r[0] += 1
    sock.sendall(pack(_r[0], 2, c))
    return recv(sock)


def health():
    b = cmd(f"data get entity {PLAYER} Health")
    return b.split("：")[-1].strip() if "：" in b else b.strip()


def absorption():
    b = cmd(f"data get entity {PLAYER} AbsorptionAmount")
    return b.split("：")[-1].strip() if "：" in b else b.strip()


def info():
    return cmd(f"battlefield regeninfo {PLAYER}").strip()


def report(label):
    print(f"  [{label}]")
    print(f"    {info()}")
    print(f"    Health={health()}  Absorption={absorption()}")


print("=== 场景 6（重做）：吸收护盾不得计入实际生命损失 ===")
cmd(f"battlefield heal {PLAYER}")
time.sleep(0.4)
cmd(f"minecraft:effect give {PLAYER} minecraft:absorption 120 4")
time.sleep(0.5)
report("满血 + 20 点护盾")
cmd(f"damage {PLAYER} 30 minecraft:generic")
time.sleep(0.4)
report("挨 30 点（20 被护盾吃掉，实际掉血 10）")
time.sleep(9)
report("回血后（上限应仍是 100）")

print("\n=== 场景 5（补）：回血中途再次受伤 ===")
cmd(f"battlefield heal {PLAYER}")
time.sleep(0.3)
cmd(f"damage {PLAYER} 60 minecraft:generic")
time.sleep(0.4)
report("受 60 伤后（上限 70）")
time.sleep(7)
report("回血 7 秒（应已爬到 50 左右）")
cmd(f"damage {PLAYER} 20 minecraft:generic")
time.sleep(0.4)
report("中途再受 20（上限应按新血量重算）")
time.sleep(10)
report("回血停住后")

print("\n=== 场景 10：关闭动态上限开关 ===")
print("    " + cmd("bt reload").strip()[:60])
sock.close()
