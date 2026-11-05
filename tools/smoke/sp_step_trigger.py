# -*- coding: utf-8 -*-
"""52795·SP 全量变化步进触发源穷举：
收集所有携带 SP 的 0x10 帧（单属性+面板），按帧序取值变化点，
统计每个变化区间内的 出招(msg20)/打中怪(msg24)/自己被打/169buff 种类。
用法: python -I sp_step_trigger.py <s2c.bin>
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

PLAYER = 'obj(0x11000001,0x7a5c0b)'


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


def main():
    path = sys.argv[1]
    frames = _CD.load(path)
    tbl = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(tbl)
    sp_idx = next(i for i, (n, _t) in enumerate(entries) if n == 'SP')

    # 事件流: (帧号, kind, detail)
    events = []
    last_sp = None
    for n, fr in enumerate(frames):
        if not fr:
            continue
        if fr[0] == 0x10 and len(fr) >= 12:
            pc = struct.unpack_from('<H', fr, 10)[0]
            off = 12
            val = None
            single = (pc == 1 and len(fr) == 18)
            for _ in range(pc):
                if off + 2 > len(fr):
                    break
                idx = struct.unpack_from('<H', fr, off)[0]
                off += 2
                try:
                    v, off = read_prop(fr, off, entries[idx][1])
                except Exception:
                    break
                if idx == sp_idx:
                    val = v
            if val is not None and val != last_sp:
                events.append((n, 'sp', val, '单帧' if single else 'pc%d面板' % pc))
                last_sp = val
        elif fr[0] in (0x1E, 0x27):
            try:
                vals = _CD.parse_tvarlist(fr[1:])
            except Exception:
                continue
            if not vals or not isinstance(vals[0], int):
                continue
            if vals[0] == 20 and len(vals) > 3 and vals[1] == PLAYER and isinstance(vals[2], str):
                events.append((n, 'cast', vals[2], ''))
            elif vals[0] == 24 and len(vals) > 2 and vals[1] == PLAYER:
                events.append((n, 'taken', vals[2] if isinstance(vals[2], int) else -1, ''))
            elif vals[0] == 24 and len(vals) > 2 and vals[1] != PLAYER:
                events.append((n, 'dealt', vals[2] if isinstance(vals[2], int) else -1, ''))
            elif vals[0] == 169 and len(vals) > 2 and isinstance(vals[2], str):
                events.append((n, 'buff', vals[2], vals[1]))

    # 变化区间统计
    sp_positions = [i for i, e in enumerate(events) if e[1] == 'sp']
    buckets = {}
    for a, b in zip(sp_positions, sp_positions[1:]):
        va, vb = events[a][2], events[b][2]
        delta = vb - va
        stats = Counter()
        casts = []
        for j in range(a + 1, b):
            k = events[j][1]
            stats[k] += 1
            if k == 'cast':
                casts.append(events[j][2])
            if k == 'buff' and 'wuji' in str(events[j][2]).lower():
                stats['buff_wuji'] += 1
        key = delta
        ent = buckets.setdefault(key, [0, Counter(), Counter()])
        ent[0] += 1
        ent[1].update(dict(stats))
        ent[2].update(casts)

    print('SP 变化区间按 Δ 分组（区间数 | 中间事件均值）:')
    for delta in sorted(buckets):
        cnt, tot, cast_hist = buckets[delta]
        avg = {k: round(v / cnt, 2) for k, v in tot.most_common()}
        print('  Δ%+4d x%-3d 事件/区间: %s' % (delta, cnt, avg))
        if cast_hist:
            print('        区间内出招: %s' % dict(cast_hist.most_common(4)))

    # 打印前 40 个变化点的直接上下文
    print('\n前 40 个 SP 变化点上下文:')
    for i in sp_positions[:40]:
        n, _, val, form = events[i]
        prev = events[i - 1] if i > 0 else None
        nxt = events[i + 1] if i + 1 < len(events) else None
        print('   #%d SP=%d(%s)  前:%s  后:%s' % (
            n, val, form,
            '%s%s' % (prev[1], prev[2]) if prev else '-',
            '%s%s' % (nxt[1], nxt[2]) if nxt else '-'))


if __name__ == '__main__':
    main()
