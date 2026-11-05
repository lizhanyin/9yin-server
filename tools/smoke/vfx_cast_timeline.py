# -*- coding: utf-8 -*-
"""出招特效排障：列出一场会话全部玩家 msg20 出招簇（前后 12 帧摘要），
对比会话早期与后期出招簇的帧构成差异。

用法: python -I -X utf8 vfx_cast_timeline.py <c2s.bin> <s2c.bin>
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture_deep import decode_frames, parse_tvarlist

def tval(v):
    s = str(v)
    return s if len(s) <= 38 else s[:35] + '...'


def frame_summary(f):
    op = f[0]
    if op == 0x1E and len(f) > 1:
        vals = parse_tvarlist(f[1:])
        if vals and isinstance(vals[0], int):
            return "1E m%-4s %s" % (vals[0], ' '.join(tval(x) for x in vals[1:5]))
        return "1E ?"
    if op == 0x27 and len(f) > 1:
        vals = parse_tvarlist(f[1:])
        return "27 m%s %s" % (vals[0] if vals else '?', ' '.join(tval(x) for x in (vals or [])[1:4]))
    if op == 0x10 and len(f) > 2:
        return "10 panel n=%s isView=%s" % (f[2], f[1])
    if op == 0x11:
        return "11 add len=%d" % len(f)
    if op == 0x12:
        return "12 del"
    if op == 0x13:
        return "13 grid"
    if op == 0x2D:
        return "2D hit_fx len=%d" % len(f)
    if op == 0x18:
        return "18 view_add len=%d" % len(f)
    return "%02X len=%d" % (op, len(f))


def main(c2s_path, s2c_path):
    c2s = decode_frames(open(c2s_path, 'rb').read())
    s2c = decode_frames(open(s2c_path, 'rb').read())
    casts = []  # (c2s_idx, skill, aim)
    for i, f in enumerate(c2s):
        if f[0] != 0x0A:
            continue
        vals = parse_tvarlist(f[21:])
        if vals and vals[0] == 211:
            casts.append((i, vals[1], vals[10] if len(vals) > 10 else None))
    anchors = []
    for i, f in enumerate(s2c):
        if f[0] == 0x1E:
            vals = parse_tvarlist(f[1:])
            if vals and vals[0] == 20:
                anchors.append((i, vals[1], vals[2]))
    player_casts = [(i, skill, tgt) for (i, caster, skill, *rest) in [(a[0], a[1], a[2], None) for a in anchors]
                    for (i, skill, tgt) in [(i, skill, None)] if not str(skill).startswith('jn_')]
    print("c2s 211 x%d  s2c msg20 x%d (player-skilled %d)" % (len(casts), len(anchors), len(player_casts)))
    print("== cast timeline (player msg20 anchors) ==")
    for n, (a, skill, _) in enumerate(player_casts):
        print("--- cast#%d anchor#%d %s ---" % (n, a, tval(skill)))
        for j in range(max(0, a - 4), min(len(s2c), a + 12)):
            mark = ">>" if j == a else "  "
            print("%s #%d %s" % (mark, j, frame_summary(s2c[j])))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
