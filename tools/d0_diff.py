#!/usr/bin/env python
"""找参考服玩家 0x0D 帧（objID=1）并全量 dump 属性行；按捕获表类型解。"""
import sys
sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09, Reader

cap = sys.argv[1]
frames = decode_frames(open(cap, "rb").read())
f09 = next(f for f in frames if f and f[0] == 0x09)
_, tbl, _ = parse_09(f09)

for i, f in enumerate(frames):
    if not f or f[0] != 0x0D or len(f) < 0x40:
        continue
    obj = int.from_bytes(f[1:5], "little")
    if obj not in (1, 0x10000, 2):
        continue
    cnt = f[0x3D] | (f[0x3E] << 8)
    rd = Reader(f, 0x3F)
    rows = []
    bad = 0
    while rd.o < len(f):
        idx = rd.u16()
        entry = tbl[idx] if idx < len(tbl) else None
        if entry is None:
            bad += 1
            break
        try:
            val = rd.value(entry[1])
        except Exception:
            bad += 1
            break
        rows.append((idx, entry[0], val))
    print(f"frame#{i} 0x0D obj={obj} headerCount={cnt} parsed={len(rows)} bad={bad} "
          f"consumed={rd.o}/{len(f)}")
    for idx, name, val in rows:
        print(f"  [{idx:5d}] {name:22s} = {str(val)[:60]}")
    print()
