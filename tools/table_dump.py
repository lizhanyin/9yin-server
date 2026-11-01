#!/usr/bin/env python
"""直接导出参考服 0x09 表内容：头部 + 内修家族位置 + 与官方文件的差异集。"""
import sys
sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09, load_official_table

cap = sys.argv[1]
tbl = r"G:\pojie\ai\9yin-server\nineyin\src\main\resources\nineyin\protocol\official_property_table.txt"

frames = decode_frames(open(cap, "rb").read())
f09 = next(f for f in frames if f and f[0] == 0x09)
_, cap_entries, _ = parse_09(f09)
official = load_official_table(tbl)
off_names = {n: (i, t) for i, n, t in official}

print("== capture table 前 30 行 (position: name, type) ==")
for pos, (n, t) in enumerate(cap_entries[:30]):
    extra = ""
    if n in off_names:
        oi, ot = off_names[n]
        extra = f"  [official #{oi} t{ot}]" + ("" if (oi, ot) == (pos, t) else "  <<< MISMATCH")
    else:
        extra = "  [not in official file]"
    print(f"  {pos:4d}: {n:24s} t{t}{extra}")

print("\n== capture table 中内修家族 ==")
for pos, (n, t) in enumerate(cap_entries):
    if n in {"CurNeiGong", "Faculty", "FacultyState", "FacultyStyle", "FacultyName",
             "CurFillValue", "TotalFillValue", "CurLevel", "FillSpeed", "PowerValue",
             "ExtFacultyName", "ExtCurFillValue", "ExtTotalFillValue"}:
        oi, ot = off_names.get(n, (-1, -1))
        print(f"  {n:18s} cap_pos={pos:5d} t{t}   official#{oi} t{ot}   shift={pos - oi:+d}")

# 差异集合
cap_set = {(n, t) for n, t in cap_entries}
off_set = {(n, t) for _, n, t in official}
only_cap = [(n, t) for n, t in cap_entries if (n, t) not in off_set]
only_off = [(i, n, t) for i, n, t in official if (n, t) not in cap_set]
print(f"\n== 仅在 capture 表（{len(only_cap)}）==")
for n, t in only_cap[:20]:
    print(f"  {n} t{t}")
print(f"\n== 仅在 official 文件（{len(only_off)}）==")
for i, n, t in only_off[:20]:
    print(f"  #{i} {n} t{t}")

# 位移分布
shifts = {}
for pos, (n, t) in enumerate(cap_entries):
    if n in off_names:
        oi, _ = off_names[n]
        shifts.setdefault(pos - oi, []).append(n)
print("\n== cap_pos - official_idx 位移分布 ==")
for sh in sorted(shifts):
    sample = ", ".join(shifts[sh][:4])
    print(f"  shift {sh:+d}: {len(shifts[sh])} 项  例: {sample}")
