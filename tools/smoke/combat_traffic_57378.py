# -*- coding: utf-8 -*-
"""57378 战斗传输分析：会话时间线 / CanUse 心跳批 / SP 演进 / 出招链帧序。
用法: python -I combat_traffic_57378.py <dir>
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
    if ptype == 5:
        return round(struct.unpack_from('<f', fr, off)[0], 3), off + 4
    if ptype == 7:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        raw = fr[off:off + n]
        return raw.rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].decode('utf-16-le', 'replace'), off + n
    return None, off


def parse_row(fr, entries, count):
    pc = struct.unpack_from('<H', fr, 5)[0]
    off = 7
    row = {}
    while off + 2 <= len(fr) and len(row) < pc:
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= count:
            break
        name, ptype = entries[idx]
        try:
            val, off = read_prop(fr, off, ptype)
        except Exception:
            break
        row[name] = val
    return row


def main():
    base = sys.argv[1]
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    c2s = _CD.load(os.path.join(base, '127-0-0-1-57378_c2s.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    name_of = lambda i: entries[i][0] if i < count else '?%d' % i

    # ---- 行号→ConfigID ----
    rowcfg = {}
    for fr in s2c:
        if fr and fr[0] == 0x18 and len(fr) >= 7 and struct.unpack_from('<H', fr, 1)[0] == 40:
            obj = struct.unpack_from('<H', fr, 3)[0]
            r = parse_row(fr, entries, count)
            rowcfg[obj] = r.get('ConfigID', '?')
    print('View40 rows=%d' % len(rowcfg))

    # ---- c2s 概览 ----
    print('\n== C2S ==')
    chist = Counter()
    for fr in c2s:
        if not fr:
            continue
        chist[hex(fr[0])] += 1
    print('c2s opcodes:', dict(chist))
    # custom 帧首值(=msgId)
    customs = Counter()
    for fr in c2s:
        if not fr or fr[0] != 0x1E or len(fr) < 8:
            continue
        # 0x1E | u16 len | tag u8 | u32 val
        tag, val = fr[3], struct.unpack_from('<I', fr, 4)[0]
        if tag == 2:
            customs[val] += 1
    print('c2s custom msgId top:', customs.most_common(12))

    # ---- SP 演进 + LogicState + CanUse 批 ----
    sp_idx = next(i for i, (n, t) in enumerate(entries) if n == 'SP')
    ls_idx = next(i for i, (n, t) in enumerate(entries) if n == 'LogicState')
    print('\n== 战斗时间线 ==')
    events = []
    canuse_batches = []
    cur_batch = []
    sp_val = None
    for n, fr in enumerate(s2c):
        if not fr:
            continue
        if fr[0] == 0x10:
            # 15B isView CanUse
            if len(fr) == 15 and fr[1] == 1:
                view, row = struct.unpack_from('<II', fr, 2)
                if view == 40 and struct.unpack_from('<H', fr, 10)[0] == 1 \
                        and struct.unpack_from('<H', fr, 12)[0] == next(
                            i for i, (nm, _) in enumerate(entries) if nm == 'CanUse'):
                    val = fr[14]
                    if row == min(rowcfg) if rowcfg else False:
                        pass
                    cur_batch.append((n, row, val))
                    events.append((n, 'CanUse r%d(%s)=%d' % (row, rowcfg.get(row, '?'), val)))
                continue
            # SP 单属性/面板帧
            body = fr[1:]
            for off in range(1, len(fr) - 5):
                if struct.unpack_from('<H', fr, off)[0] == sp_idx:
                    v = struct.unpack_from('<i', fr, off + 2)[0]
                    if v != sp_val:
                        sp_val = v
                        events.append((n, 'SP→%d' % v))
                    break
            for off in range(1, min(len(fr) - 5, 100)):
                if struct.unpack_from('<H', fr, off)[0] == ls_idx:
                    v = fr[off + 2]
                    events.append((n, 'LogicState=%d (len=%d)' % (v, len(fr))))
                    break
        elif fr[0] == 0x13:
            events.append((n, '0x13 攻击/动作 len=%d' % len(fr)))
        elif fr[0] == 0x2D:
            events.append((n, '0x2D 受击特效'))
        elif fr[0] == 0x1E:
            # custom: 首值 msgId
            try:
                tag, val = fr[3], struct.unpack_from('<I', fr, 4)[0]
                if tag == 2 and val in (24, 18, 205, 169, 20, 337, 211, 294, 414):
                    events.append((n, 'custom msg%d len=%d' % (val, len(fr))))
            except Exception:
                pass
    # CanUse 批切分（行回到最小行号）
    for e in cur_batch:
        pass
    if rowcfg:
        first = min(rowcfg)
        for t in cur_batch:
            if t[1] == first and len(cur_batch) > 0:
                pass
    # 简化：按相邻帧号 <=3 切批
    batches = []
    for t in cur_batch:
        if batches and t[0] - batches[-1][-1][0] <= 3:
            batches[-1].append(t)
        else:
            batches.append([t])
    print('CanUse 推送总数=%d 批数=%d' % (len(cur_batch), len(batches)))
    for b in batches[:30]:
        vals = Counter(v for _, _, v in b)
        rows = sorted(set(r for _, r, _ in b))
        print('  批 @#%d 行数=%d 值分布=%s 行范围=%s..%s' % (
            b[0][0], len(b), dict(vals), rows[0], rows[-1]))

    print('\n== 事件流（前 120 条）==')
    shown = 0
    last13 = -10
    for n, desc in events:
        # 压缩连续 0x13
        if desc.startswith('0x13') and n - last13 <= 1:
            last13 = n
            continue
        if desc.startswith('0x13'):
            last13 = n
        print('  #%d %s' % (n, desc))
        shown += 1
        if shown >= 120:
            print('  ...（共 %d 事件）' % len(events))
            break


if __name__ == '__main__':
    main()
