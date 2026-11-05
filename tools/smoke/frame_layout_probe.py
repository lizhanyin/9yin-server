# -*- coding: utf-8 -*-
"""57378·三处布局疑点：
 (a) 0x18 view40 残留大小分布 + 残留内容
 (b) 0x0D AddObject 帧结构探查（找真正的属性块位置）
 (c) 0x10 #1809 残留6字节帧
用法: python -I frame_layout_probe.py <captures_dir>
"""
import importlib.util
import os
import struct
import sys
from collections import Counter

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)


def read_prop(fr, off, ptype):
    if ptype == 1:
        return fr[off], off + 1
    if ptype == 2:
        return struct.unpack_from('<H', fr, off)[0], off + 2
    if ptype == 3:
        return struct.unpack_from('<i', fr, off)[0], off + 4
    if ptype == 4:
        return struct.unpack_from('<q', fr, off)[0], off + 8
    if ptype == 5:
        return round(struct.unpack_from('<f', fr, off)[0], 3), off + 4
    if ptype == 6:
        return round(struct.unpack_from('<d', fr, off)[0], 3), off + 8
    if ptype == 9:
        return 'obj', off + 8
    if ptype == 7:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].decode('utf-16-le', 'replace'), off + n
    return None, off


def walk_props(fr, entries, count, pc_off):
    if pc_off + 2 > len(fr):
        return None, pc_off, None
    pc = struct.unpack_from('<H', fr, pc_off)[0]
    off = pc_off + 2
    row = {}
    for _ in range(pc):
        if off + 2 > len(fr):
            return None, off, pc
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= count:
            return None, off, pc
        name, ptype = entries[idx]
        try:
            val, off = read_prop(fr, off, ptype)
        except Exception:
            return None, off, pc
        row[name] = val
    return row, off, pc


def main():
    base = sys.argv[1]
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)

    # (a) 0x18 view40 残留分布
    dist = Counter()
    samples = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x18 or len(fr) < 7:
            continue
        if struct.unpack_from('<H', fr, 1)[0] != 40:
            continue
        row, off, pc = walk_props(fr, entries, count, 5)
        if row is None:
            dist['FAIL'] += 1
            continue
        left = len(fr) - off
        dist[left] += 1
        if left > 0 and len(samples) < 6:
            samples.append((n, len(fr), pc, off, left, fr[off:].hex()))
    print('0x18 view40 残留字节分布: %s' % dict(dist))
    for n, ln, pc, off, left, tail in samples:
        print('   #%d len=%d pc=%d 走到off=%d 残%d 尾=%s' % (n, ln, pc, off, left, tail))

    # (b) 0x0D 结构：dump 头部 + 在整个帧里搜 ConfigID 字符串与属性块痕迹
    print('\n0x0D 帧总数=%d' % sum(1 for f in s2c if f and f[0] == 0x0D))
    od = [f for f in s2c if f and f[0] == 0x0D]
    fr = od[0]
    print('首条 0x0D #%d len=%d 头64B:' % (s2c.index(fr), len(fr)))
    for i in range(0, 64, 16):
        print('   +%02d %s' % (i, fr[i:i + 16].hex(' ')))
    # 搜 gbk 串
    for pat in (b'CS_', b'Npc_', b'npc', b'jn_', b'ConfigID'):
        pos = fr.find(pat)
        if pos >= 0:
            print('   串 %r @+%d 上下文: %s' % (pat, pos, fr[max(0, pos - 8):pos + 40]))
    # 找 u16 pc 使得 walk 精确吃满：遍历所有偏移尝试
    goods = []
    for off_try in range(1, min(len(fr) - 4, 120)):
        row, off_end, pc = walk_props(fr, entries, count, off_try)
        if row is not None and off_end == len(fr) and pc >= 3:
            goods.append((off_try, pc, len(row)))
    print('   能精确吃满的 pc 偏移候选: %s' % (goods[:8] if goods else '无'))

    # 所有 0x0D 帧都试一遍最大公约起点
    if goods:
        pc_off = goods[0][0]
        hit = Counter()
        for n, f in enumerate(s2c):
            if not f or f[0] != 0x0D or len(f) <= pc_off:
                continue
            row, off_end, pc = walk_props(f, entries, count, pc_off)
            if row is None or off_end != len(f):
                hit['unaligned'] += 1
                continue
            hit['exact'] += 1
            for k in ('SP', 'CanUse', 'InParry', 'MaxSP'):
                if k in row:
                    hit['含' + k] += 1
        print('   以 pc@%d 复核全部 0x0D: %s' % (pc_off, dict(hit)))

    # (c) #1809
    fr = s2c[1809]
    row, off, pc = walk_props(fr, entries, count, 10)
    print('\n0x10 #1809 len=%d pc=%d 走到off=%d 尾=%s' % (len(fr), pc, off, fr[off:].hex(' ')))
    print('   头32B: %s' % fr[:32].hex(' '))
    if row:
        print('   已解析属性: %s' % row)


if __name__ == '__main__':
    main()
