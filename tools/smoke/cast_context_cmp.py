# -*- coding: utf-8 -*-
"""出招受理簇对拍：锚定每个 msg20 帧，打印前后 N 帧摘要（类型+关键字段）。

用法: python -I cast_context_cmp.py <s2c.bin> [前后帧数]
"""
import sys
sys.path.insert(0, '.')
from capture_deep import decode_frames, parse_tvarlist

def frame_summary(f):
    op = f[0]
    if op == 0x1E and len(f) > 1:
        vals = parse_tvarlist(f[1:])
        if vals and isinstance(vals[0], int):
            return "1E msg%s %s" % (vals[0], vals[1:6])
        return "1E ?"
    if op == 0x10 and len(f) > 1:
        n = f[2] if len(f) > 2 else 0
        return "10 panel n=%s isView=%s" % (n, f[1])
    if op == 0x11:
        return "11 rec_add len=%d" % len(f)
    if op == 0x12:
        return "12 rec_del"
    if op == 0x13:
        return "13 rec_grid"
    if op == 0x2D:
        return "2D hit_fx len=%d" % len(f)
    if op == 0x18:
        return "18 view_add"
    return "%02X len=%d" % (op, len(f))

def main(path, ctx=6):
    frames = decode_frames(open(path, 'rb').read())
    anchors = []
    for i, f in enumerate(frames):
        if f[0] == 0x1E:
            vals = parse_tvarlist(f[1:])
            if vals and vals[0] == 20:
                anchors.append(i)
    print("%s: %d frames, %d msg20 anchors" % (path.split('/')[-1], len(frames), len(anchors)))
    for a in anchors[:3]:
        print("--- anchor #%d ---" % a)
        for j in range(max(0, a - ctx), min(len(frames), a + ctx + 1)):
            mark = ">>" if j == a else "  "
            print("%s #%d %s" % (mark, j, frame_summary(frames[j])))

if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 6)
