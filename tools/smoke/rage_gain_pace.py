# -*- coding: utf-8 -*-
"""52795·官方回怒节奏取证：SP 单帧与 打中怪(msg24/0x2D)/被打/出招(msg20) 的因果对齐。
判定：每次回怒 +5 对应"打中一下"还是"被打一下"还是"出招一次"。
用法: python -I rage_gain_pace.py <s2c.bin>
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

PLAYER = 'obj(0x11000001,0x7a5c0b)'


def main():
    path = sys.argv[1]
    frames = _CD.load(path)
    tbl = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(tbl)
    sp_idx = next(i for i, (n, _t) in enumerate(entries) if n == 'SP')

    events = []  # (帧号, 类型, 详情)
    for n, fr in enumerate(frames):
        if not fr:
            continue
        if fr[0] == 0x10 and len(fr) == 18 and fr[1] == 0:
            # 单属性帧：解析看是不是 SP
            pc = struct.unpack_from('<H', fr, 10)[0]
            if pc == 1 and struct.unpack_from('<H', fr, 12)[0] == sp_idx:
                val = struct.unpack_from('<i', fr, 14)[0]
                events.append((n, 'SP', val))
        elif fr[0] in (0x1E, 0x27):
            try:
                vals = _CD.parse_tvarlist(fr[1:])
            except Exception:
                continue
            if not vals or not isinstance(vals[0], int):
                continue
            if vals[0] == 20 and len(vals) > 3 and vals[1] == PLAYER and isinstance(vals[2], str):
                events.append((n, 'cast', vals[2]))
            elif vals[0] == 24 and len(vals) > 2:
                # 24: [24, 受击者, 伤害, ...] —— 受击者是谁
                tgt = vals[1]
                who = '打中怪' if tgt != PLAYER else '自己被打'
                dmg = vals[2] if isinstance(vals[2], int) else '?'
                events.append((n, 'hit', '%s dmg=%s' % (who, dmg)))
            elif vals[0] == 18:
                events.append((n, 'hurt', str(vals[2])))
        elif fr[0] == 0x2D:
            events.append((n, 'fx2D', ''))

    # 输出对齐窗口：围绕每个 SP 单帧，取前后 12 帧
    sp_at = [i for i, e in enumerate(events) if e[1] == 'SP']
    print('SP 单帧数=%d  事件总数=%d' % (len(sp_at), len(events)))
    shown = 0
    prev_val = None
    for k, i in enumerate(sp_at):
        n, _, val = events[i]
        delta = (val - prev_val) if prev_val is not None else 0
        prev_val = val
        if shown >= 26:
            break
        lo, hi = max(0, i - 8), min(len(events), i + 3)
        def fmt(j):
            kind = events[j][1]
            d = events[j][2]
            if kind == 'SP':
                return '#%d SP=%d' % (events[j][0], d)
            if kind == 'cast':
                return '#%d 出招:%s' % (events[j][0], d)
            if kind == 'hit':
                return '#%d %s' % (events[j][0], d)
            if kind == 'hurt':
                return '#%d hurt:%s' % (events[j][0], d)
            return '#%d 0x2D' % events[j][0]
        ctx = [fmt(j) for j in range(lo, hi)]
        print('--- SP帧 #%d 值=%d (Δ%+d)' % (n, val, delta))
        print('    ' + ' | '.join(ctx))
        shown += 1

    # 统计：相邻 SP 帧之间各类事件计数 vs ΔSP
    print('\n== 相邻 SP 帧间隔内事件统计（ΔSP=+5 的样本）==')
    stats = []
    for a, b in zip(sp_at, sp_at[1:]):
        va, vb = events[a][2], events[b][2]
        if vb - va != 5:
            continue
        hits_dealt = sum(1 for j in range(a + 1, b) if events[j][1] == 'hit' and events[j][2].startswith('打中怪'))
        hits_taken = sum(1 for j in range(a + 1, b) if events[j][1] == 'hit' and events[j][2].startswith('自己被打'))
        casts = sum(1 for j in range(a + 1, b) if events[j][1] == 'cast')
        stats.append((hits_dealt, hits_taken, casts))
    from collections import Counter
    print('Δ=+5 样本数=%d  (打中怪,被打,出招) 分布: %s' % (len(stats), Counter(stats).most_common(8)))


if __name__ == '__main__':
    main()
