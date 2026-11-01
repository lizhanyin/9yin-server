#!/usr/bin/env python
"""F 盘参考服 captures/local 取证：解码 s2c 流，比对 0x09 协商表与内修属性行。

用法: python -I capture_analyze.py <capture.bin> [--table official_property_table.txt]
帧协议: XOR(0xFA6D3BCC 小端) + 0xEE 转义 + EE EE 终止（与 Go transport 一致）。
"""
import sys
from collections import Counter

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def xor(data: bytes) -> bytes:
    return bytes(b ^ KEY[i & 3] for i, b in enumerate(data))


def decode_frames(buf: bytes):
    frames, body = [], bytearray()
    i = 0
    while i < len(buf):
        b = buf[i]
        if b == 0xEE:
            if i + 1 >= len(buf):
                break
            nxt = buf[i + 1]
            if nxt == 0x00:
                body.append(0xEE)
                i += 2
            elif nxt == 0xEE:
                frames.append(xor(bytes(body)))
                body = bytearray()
                i += 2
            else:
                raise ValueError(f"invalid escape EE {nxt:02X} at {i}")
        else:
            body.append(b)
            i += 1
    return frames


def load_official_table(path):
    entries = []  # (index, name, typeCode)
    with open(path, encoding="ascii") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) == 3:
                entries.append((int(parts[0]), parts[1], int(parts[2])))
    return entries


def parse_09(frame):
    """0x09 | u16 count | (name\\0 u8 type)* → [(name, typeCode)]"""
    count = frame[1] | (frame[2] << 8)
    entries, off = [], 3
    for _ in range(count):
        end = frame.index(b"\x00", off)
        name = frame[off:end].decode("latin-1")
        entries.append((name, frame[end + 1]))
        off = end + 2
    return count, entries, off


class Reader:
    def __init__(self, data, off=0):
        self.d, self.o = data, off

    def u8(self):
        v = self.d[self.o]
        self.o += 1
        return v

    def u16(self):
        v = self.d[self.o] | (self.d[self.o + 1] << 8)
        self.o += 2
        return v

    def u32(self):
        v = int.from_bytes(self.d[self.o:self.o + 4], "little")
        self.o += 4
        return v

    def u64(self):
        v = int.from_bytes(self.d[self.o:self.o + 8], "little")
        self.o += 8
        return v

    def value(self, type_code):
        if type_code == 1:
            return self.u8()
        if type_code == 2:
            return self.u16()
        if type_code == 3:
            return self.u32()
        if type_code == 4:
            return self.u64()
        if type_code == 5:
            import struct
            return struct.unpack("<f", self.d[self.o:self.o + 4])[0] if False else ("f32", self.u32())
        if type_code == 7:
            n = self.u32()
            raw = self.d[self.o:self.o + n]
            self.o += n
            return raw.rstrip(b"\x00").decode("latin-1")
        if type_code == 8:
            n = self.u32()
            raw = self.d[self.o:self.o + n]
            self.o += n
            return raw.decode("utf-16-le", "replace").rstrip("\x00")
        if type_code == 9:
            return ("obj", self.u64())
        raise ValueError(f"type {type_code}")


def dump_props(frame, table_by_name_order, start, limit=None, only=None, label=""):
    """按帧内出现顺序解析 indexed 属性行（类型由 0x09 表的位置序决定）。"""
    rd = Reader(frame, start)
    rows = []
    n = 0
    while rd.o < len(frame) and (limit is None or n < limit):
        idx = rd.u16()
        entry = table_by_name_order[idx] if idx < len(table_by_name_order) else None
        try:
            val = rd.value(entry[1] if entry else 3)
        except Exception as exc:
            rows.append((idx, entry, f"<err {exc}>"))
            break
        rows.append((idx, entry, val))
        n += 1
        if only is not None and idx not in only and (entry and entry[0] not in only):
            pass
    print(f"  {label}: {len(rows)} rows, consumed {rd.o}/{len(frame)}")
    return rows


def main():
    path = sys.argv[1]
    table_path = "nineyin/src/main/resources/nineyin/protocol/official_property_table.txt"
    if "--table" in sys.argv:
        table_path = sys.argv[sys.argv.index("--table") + 1]
    official = load_official_table(table_path)

    frames = decode_frames(open(path, "rb").read())
    hist = Counter(f[0] for f in frames)
    print(f"frames={len(frames)} opcodes={dict(sorted(hist.items()))}")

    # 0x09 帧
    for i, f in enumerate(frames):
        if f and f[0] == 0x09:
            count, entries, off = parse_09(f)
            print(f"\nframe#{i} 0x09: count={count} parsed={len(entries)} end={off}/{len(f)}")
            same = entries == [(n, t) for _, n, t in official]
            print(f"  identical to official_property_table.txt: {same}")
            if not same:
                for pos, ((n1, t1), (_idx, n2, t2)) in enumerate(zip(entries, official)):
                    if (n1, t1) != (n2, t2):
                        print(f"  first diff at pos {pos}: capture=({n1},{t1}) official=(#{_idx} {n2},{t2})")
                        break
                print(f"  capture len={len(entries)} official len={len(official)}")
            break
    else:
        print("no 0x09 frame found")

    # 用捕获表的 (name,type) 位置序解析 0x0D/0x10，打印内修家族行
    tbl = [(n, t) for _, n, t in official]
    fac_names = {"CurNeiGong", "Faculty", "FacultyState", "FacultyStyle", "FacultyName",
                 "CurFillValue", "TotalFillValue", "CurLevel", "FillSpeed",
                 "ExtFacultyName", "ExtCurFillValue", "ExtTotalFillValue", "ExtCurLevel"}
    shown = {0x0D: 0, 0x10: 0}
    for i, f in enumerate(frames):
        if not f or f[0] not in (0x0D, 0x10):
            continue
        op = f[0]
        start = 0x3F if op == 0x0D else 1
        if len(f) <= start:
            continue
        rd = Reader(f, start)
        hits = []
        while rd.o < len(f):
            idx = rd.u16()
            entry = tbl[idx] if idx < len(tbl) else None
            try:
                val = rd.value(entry[1] if entry else 3)
            except Exception:
                break
            if entry and entry[0] in fac_names:
                hits.append((idx, entry[0], val))
        if hits and shown[op] < 8:
            shown[op] += 1
            print(f"\nframe#{i} {hex(op)} len={len(f)} fill-family rows:")
            for idx, name, val in hits:
                print(f"  [{idx}] {name} = {val}")


if __name__ == "__main__":
    main()
