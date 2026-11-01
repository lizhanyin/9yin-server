#!/usr/bin/env python
"""从 F 盘参考服捕获提取真实 0x09 协商表 → official_property_table.txt（场景方言）。

- 校验全部捕获的 0x09 表一致后生成；index = 帧内位置（客户端按位置绑定）。
- 回归校验：由生成文件重组 0x09 帧，与捕获帧逐字节一致。
"""
import sys
import glob

sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09

CAP_DIR = r"F:\Game\Online\9yin\9yin_server1\captures\local"
OUT = r"G:\pojie\ai\9yin-server\nineyin\src\main\resources\nineyin\protocol\official_property_table.txt"


def main():
    tables = {}
    raw09 = None
    for path in sorted(glob.glob(CAP_DIR + r"\*_s2c.bin")):
        try:
            frames = decode_frames(open(path, "rb").read())
        except Exception as exc:
            print("skip", path, exc)
            continue
        f09 = next((f for f in frames if f and f[0] == 0x09), None)
        if f09:
            tables.setdefault(tuple(parse_09(f09)[1]), []).append(path.split("\\")[-1])
            if raw09 is None:
                raw09 = f09
    assert len(tables) == 1, f"捕获表不一致: {len(tables)} 种"
    entries = list(tables.keys().__iter__().__next__())
    print(f"distinct tables=1, entries={len(entries)}, from {sum(len(v) for v in tables.values())} captures")

    with open(OUT, "w", encoding="ascii", newline="\n") as fh:
        for pos, (name, tc) in enumerate(entries):
            assert "\t" not in name and name.isascii()
            fh.write(f"{pos}\t{name}\t{tc}\n")
    print("written", OUT)

    # 回归：按 Java propertyTableFrame 布局重组并比对捕获帧
    rebuilt = bytearray([0x09, len(entries) & 0xFF, len(entries) >> 8])
    for name, tc in entries:
        rebuilt += name.encode("latin-1") + b"\x00" + bytes([tc])
    assert bytes(rebuilt) == raw09, "重组帧与捕获帧不一致"
    print(f"round-trip OK: rebuilt {len(rebuilt)} bytes == capture frame")


if __name__ == "__main__":
    main()
