#!/usr/bin/env python
"""解析参考服 0x18 视图行（0x18|viewId|objIdx|count|ViewProperty*），按捕获表类型解。"""
import sys
sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09, Reader

cap = sys.argv[1]
frames = decode_frames(open(cap, "rb").read())
f09 = next(f for f in frames if f and f[0] == 0x09)
_, tbl, _ = parse_09(f09)

by_view = {}
for i, f in enumerate(frames):
    if not f or f[0] != 0x18 or len(f) < 7:
        continue
    view = f[1] | (f[2] << 8)
    obj = f[3] | (f[4] << 8)
    cnt = f[5] | (f[6] << 8)
    rd = Reader(f, 7)
    rows = []
    ok = True
    for _ in range(cnt):
        if rd.o + 2 > len(f):
            ok = False
            break
        idx = rd.u16()
        entry = tbl[idx] if idx < len(tbl) else None
        if entry is None:
            ok = False
            break
        try:
            val = rd.value(entry[1])
        except Exception:
            ok = False
            break
        rows.append((idx, entry[0], val))
    by_view.setdefault(view, []).append((i, obj, rows, ok, rd.o, len(f)))

for view in sorted(by_view):
    items = by_view[view]
    ok_n = sum(1 for _, _, _, ok, _, _ in items if ok)
    print(f"\n== view {view}: {len(items)} 帧, 完整解析 {ok_n} ==")
    for i, obj, rows, ok, off, ln in items[:4]:
        status = "" if ok and off == ln else f"  [解析至 {off}/{ln} ok={ok}]"
        print(f"  frame#{i} obj={obj}{status}")
        for idx, name, val in rows:
            print(f"    [{idx}] {name} = {val}")
