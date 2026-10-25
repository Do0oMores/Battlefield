"""Minimal NBT reader: enough to pull Dimension/Pos out of playerdata."""
import gzip
import struct
import sys


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.i = 0

    def u1(self):
        v = self.data[self.i]
        self.i += 1
        return v

    def i1(self):
        v = struct.unpack_from(">b", self.data, self.i)[0]
        self.i += 1
        return v

    def i2(self):
        v = struct.unpack_from(">h", self.data, self.i)[0]
        self.i += 2
        return v

    def i4(self):
        v = struct.unpack_from(">i", self.data, self.i)[0]
        self.i += 4
        return v

    def i8(self):
        v = struct.unpack_from(">q", self.data, self.i)[0]
        self.i += 8
        return v

    def f4(self):
        v = struct.unpack_from(">f", self.data, self.i)[0]
        self.i += 4
        return v

    def f8(self):
        v = struct.unpack_from(">d", self.data, self.i)[0]
        self.i += 8
        return v

    def string(self):
        n = self.i2()
        raw = self.data[self.i:self.i + n]
        self.i += n
        return raw.decode("utf-8", errors="replace")


def read_payload(r: Reader, tag: int):
    if tag == 1:
        return r.i1()
    if tag == 2:
        return r.i2()
    if tag == 3:
        return r.i4()
    if tag == 4:
        return r.i8()
    if tag == 5:
        return r.f4()
    if tag == 6:
        return r.f8()
    if tag == 7:
        n = r.i4()
        raw = r.data[r.i:r.i + n]
        r.i += n
        return list(raw)
    if tag == 8:
        return r.string()
    if tag == 9:
        sub = r.u1()
        n = r.i4()
        return [read_payload(r, sub) for _ in range(n)]
    if tag == 10:
        out = {}
        while True:
            t = r.u1()
            if t == 0:
                return out
            name = r.string()
            out[name] = read_payload(r, t)
    if tag == 11:
        n = r.i4()
        vals = [r.i4() for _ in range(n)]
        return vals
    if tag == 12:
        n = r.i4()
        vals = [r.i8() for _ in range(n)]
        return vals
    raise ValueError(f"unknown tag {tag}")


def load(path: str) -> dict:
    with gzip.open(path, "rb") as fh:
        data = fh.read()
    r = Reader(data)
    t = r.u1()
    if t != 10:
        raise ValueError("root is not a compound")
    r.string()
    return read_payload(r, 10)


if __name__ == "__main__":
    tag = load(sys.argv[1])
    print("Dimension:", tag.get("Dimension"))
    print("Pos:", [round(v, 2) for v in tag.get("Pos", [])])
    print("Health:", tag.get("Health"))
    inv = tag.get("Inventory", [])
    print("InventorySlots:", len(inv), "| ids:", [s.get("id") for s in inv][:6])
