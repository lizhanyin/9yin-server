#!/usr/bin/env python
"""比对参考服 0x09 表与 official_property_table.txt：找被剔除的行 + 位置偏移。"""
import sys
sys.path.insert(0, r"G:\pojie\ai\9yin-server\tools")
from capture_analyze import decode_frames, parse_09, load_official_table

cap = sys.argv[1]
tbl = sys.argv[2] if len(sys.argv) > 2 else \
    r"G:\pojie\ai\9yin-server\nineyin\src\main\resources\nineyin\protocol\official_property_table.txt"

frames = decode_frames(open(cap, "rb").read())
f09 = next(f for f in frames if f and f[0] == 0x09)
_, cap_entries, _ = parse_09(f09)
official = load_official_table(tbl)

print(f"capture entries={len(cap_entries)}  file entries={len(official)}")

# 对齐：capture 应该是 official 剔除若干行后的子序列
ci = 0
drop_before = 0
mapping = []  # (file_idx, name, cap_pos, shift)
dropped = []
for file_idx, name, tc in official:
    if ci < len(cap_entries) and cap_entries[ci] == (name, tc):
        mapping.append((file_idx, name, ci, file_idx - ci))
        ci += 1
    else:
        dropped.append((file_idx, name, tc))
print(f"aligned={ci} dropped={len(dropped)}")
print("\ndropped rows (file):")
for idx, name, tc in dropped:
    print(f"  #{idx} {name} type={tc}")
    if idx > 800 and len(dropped) > 60:
        break

shift_at = {}
for file_idx, name, cap_pos, shift in mapping:
    shift_at[name] = (file_idx, cap_pos, shift)
print("\n关键属性：file_idx / 参考服位置 / 位移")
for name in ["Type", "Name", "LogicState", "HP", "MaxHP", "CurNeiGong",
             "FacultyState", "FacultyStyle", "FacultyName", "CurLevel",
             "CurFillValue", "TotalFillValue", "FillSpeed", "PowerValue",
             "CapitalType1", "Str", "MaxVisuals"]:
    if name in shift_at:
        fi, cp, sh = shift_at[name]
        print(f"  {name:16s} file={fi:5d} cap={cp:5d} shift={sh:+d}")
    else:
        print(f"  {name:16s} NOT IN CAPTURE TABLE")
