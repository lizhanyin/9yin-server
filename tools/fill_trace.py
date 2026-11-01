#!/usr/bin/env python
"""用捕获自身的 0x09 表（位置序）解析 0x0D/0x10，跟踪 FillSpeed/CurFillValue 全程。"""
import sys
sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09, Reader

cap = sys.argv[1]
frames = decode_frames(open(cap, "rb").read())
f09 = next(f for f in frames if f and f[0] == 0x09)
_, tbl, _ = parse_09(f09)  # [(name, typeCode)] 位置序

watch = {"CurNeiGong", "Faculty", "FacultyState", "FacultyStyle", "FacultyName",
         "CurFillValue", "TotalFillValue", "CurLevel", "FillSpeed",
         "LogicState", "Level", "Name"}

counts = {}
first_last = {}
for i, f in enumerate(frames):
    if not f or f[0] not in (0x0D, 0x10):
        continue
    op = f[0]
    start = 0x3F if op == 0x0D else 12  # 0x10: op+isView+objID+owner+count = 12B 头
    if len(f) <= start:
        continue
    rd = Reader(f, start)
    while rd.o < len(f):
        idx = rd.u16()
        entry = tbl[idx] if idx < len(tbl) else None
        if entry is None:
            print(f"frame#{i} {hex(op)}: idx {idx} 超表，停在 {rd.o}/{len(f)}")
            break
        try:
            val = rd.value(entry[1])
        except Exception as exc:
            print(f"frame#{i} {hex(op)}: idx {idx} {entry[0]} 解码失败 {exc} @ {rd.o}/{len(f)}")
            break
        if entry[0] in watch:
            counts[(hex(op), entry[0])] = counts.get((hex(op), entry[0]), 0) + 1
            key = (hex(op), entry[0])
            if key not in first_last:
                first_last[key] = (i, val, val)
            else:
                fi, fv, _ = first_last[key]
                first_last[key] = (fi, fv, val)
        if rd.o >= len(f):
            break

print(f"{'frame':10s} {'prop':16s} {'count':>5s}  first#  first_val -> last_val")
for (op, name), cnt in sorted(counts.items()):
    fi, fv, lv = first_last[(op, name)]
    print(f"{op:10s} {name:16s} {cnt:5d}  #{fi:<6d} {fv} -> {lv}")
