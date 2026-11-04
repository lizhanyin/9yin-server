# -*- coding: utf-8 -*-
"""57378 参考服抓包·怒招可用性(CanUse)取证：
(1) View40 行全集 + AConsumeSP>0 的怒招行
(2) 统一时间线：c2s msg211 出招 / SP 变化 / LogicState / 0x2D 命中 / View40 CanUse 推送
(3) 批结构：每次 CanUse 批覆盖哪些行、值、与 SP 阈值的关系、批间隔
用法: python -I rage_canuse_57378.py <captures_dir>
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
        oid, owner = struct.unpack_from('<II', fr, off)
        return 'obj(%#x,%#x)' % (oid, owner), off + 8
    if ptype == 7:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].decode('utf-16-le', 'replace'), off + n
    return None, off


def parse_row(fr, entries, count, prop_off=5):
    """0x18 view_add（pc@5,props@7）或 0x10 body（pc@10,props@12）：u16 pc + (u16 idx + val)*"""
    row = {}
    off = prop_off
    if off + 2 > len(fr):
        return row
    pc = struct.unpack_from('<H', fr, off)[0]
    off += 2
    for _ in range(pc):
        if off + 2 > len(fr):
            break
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
    prefix = sys.argv[2] if len(sys.argv) > 2 else '127-0-0-1-57378'
    s2c = _CD.load(os.path.join(base, prefix + '_s2c.bin'))
    c2s = _CD.load(os.path.join(base, prefix + '_c2s.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    idx_of = {}
    for i, (n, _t) in enumerate(entries):
        idx_of.setdefault(n, i)

    # ---- View40 行：row -> 属性 ----
    rowprops = {}
    for fr in s2c:
        if fr and fr[0] == 0x18 and len(fr) >= 7 and struct.unpack_from('<H', fr, 1)[0] == 40:
            obj = struct.unpack_from('<H', fr, 3)[0]
            rowprops[obj] = parse_row(fr, entries, count)
    print('View40 行数=%d' % len(rowprops))
    rage_rows = {}
    for r, props in sorted(rowprops.items()):
        ac = {k: v for k, v in props.items() if k.startswith('AConsume')}
        if any((v or 0) > 0 for v in ac.values() if isinstance(v, int)) or ac:
            rage_rows[r] = props
    print('带 AConsume* 行 x%d:' % len(rage_rows))
    for r, props in sorted(rage_rows.items()):
        ac = {k: v for k, v in props.items() if k.startswith('AConsume')}
        print('   行%-4d ConfigID=%-8s %s' % (r, props.get('ConfigID'), ac))
    if not rage_rows:
        # 兜底：打印全行属性名
        names = Counter()
        for props in rowprops.values():
            names.update(props.keys())
        print('   （无 AConsume 行）View40 全属性名:', dict(names.most_common(40)))

    sp_idx = idx_of.get('SP')
    ls_idx = idx_of.get('LogicState')
    canuse_idx = idx_of.get('CanUse')
    print('下标 SP=%s LogicState=%s CanUse=%s' % (sp_idx, ls_idx, canuse_idx))

    # ---- 玩家对象 id（0x10 帧里 SP 变化的那个对象）----
    # 先统计所有 0x10 isView=0 帧 objId 出现次数
    obj_hist = Counter()
    for fr in s2c:
        if fr and fr[0] == 0x10 and fr[1] == 0 and len(fr) >= 12:
            obj_hist[struct.unpack_from('<I', fr, 2)[0]] += 1
    player_obj = obj_hist.most_common(1)[0][0] if obj_hist else None
    print('0x10 isView=0 objId top:', obj_hist.most_common(4), '→ 玩家=%s' % player_obj)

    # ---- 统一时间线 ----
    casts = {}  # c2s 帧号 -> skillId
    for idx, seq, mid, vals in _CD.c2s_customs(c2s):
        if mid == 211 and len(vals) > 1:
            casts[idx] = vals[1]
    print('c2s msg211 出招 x%d: %s' % (len(casts), sorted(casts.items())[:40]))

    events = []          # (s2c 帧号, 类型, 描述)
    canuse_pushes = []   # (帧号, 行, 值)
    sp_state = None
    ls_state = None
    for n, fr in enumerate(s2c):
        if not fr:
            continue
        if fr[0] == 0x10 and len(fr) >= 12:
            isview = fr[1]
            obj = struct.unpack_from('<I', fr, 2)[0]
            owner = struct.unpack_from('<I', fr, 6)[0]
            props = parse_row(fr, entries, count, prop_off=10)
            if isview == 1 and obj == 40 and 'CanUse' in props:
                canuse_pushes.append((n, owner, props['CanUse']))
                events.append((n, 'CanUse 行%d(cfg=%s)=%d' % (
                    owner, rowprops.get(owner, {}).get('ConfigID', '?'), props['CanUse'])))
                # 同帧其它属性一并记录
                extra = {k: v for k, v in props.items() if k != 'CanUse'}
                if extra:
                    events.append((n, '   ↳ 同帧附带 %s' % extra))
                continue
            if 'SP' in props and obj == player_obj:
                if props['SP'] != sp_state:
                    sp_state = props['SP']
                    events.append((n, 'SP→%d' % sp_state))
            if 'LogicState' in props and obj == player_obj:
                if props['LogicState'] != ls_state:
                    ls_state = props['LogicState']
                    events.append((n, 'LogicState→%d' % ls_state))
        elif fr[0] == 0x2D:
            events.append((n, '0x2D 命中特效'))
        elif fr[0] == 0x13 and len(fr) >= 15:
            events.append((n, '0x13 动作/攻击'))

    print('\n== CanUse 推送统计 ==')
    print('总数=%d' % len(canuse_pushes))
    by_row = Counter(r for _, r, _ in canuse_pushes)
    print('按行:', {('%d(cfg=%s)' % (r, rowprops.get(r, {}).get('ConfigID', '?'))): c
                    for r, c in by_row.most_common()})
    vals = Counter(v for _, _, v in canuse_pushes)
    print('值分布:', dict(vals))

    # 批切分（相邻帧 <=5）
    batches = []
    for t in canuse_pushes:
        if batches and t[0] - batches[-1][-1][0] <= 5:
            batches[-1].append(t)
        else:
            batches.append([t])
    print('\n批数=%d  批间隔(帧): %s' % (
        len(batches), [batches[i + 1][0][0] - batches[i][0][0] for i in range(len(batches) - 1)][:40]))
    for i, b in enumerate(batches):
        rows = sorted(set(r for _, r, _ in b))
        val_by_row = {}
        for _, r, v in b:
            val_by_row.setdefault(r, []).append(v)
        rage_in = [r for r in rows if r in rage_rows]
        desc = []
        for r in rows:
            vals_r = val_by_row[r]
            cfg = rowprops.get(r, {}).get('ConfigID', '?')
            tag = '怒' if r in rage_rows else '  '
            desc.append('%s行%d(cfg=%s)=%s' % (tag, r, cfg, vals_r if len(set(vals_r)) > 1 else vals_r[0]))
        print('批#%d @帧%d 大小=%d 行数=%d 怒招行=%d' % (i, b[0][0], len(b), len(rows), len(rage_in)))
        if i < 60 or rage_in:
            for d in desc[:12]:
                print('   %s' % d)
            if len(desc) > 12:
                print('   ...+%d 行' % (len(desc) - 12))

    # ---- SP 变化 ↔ CanUse 推送 距离 ----
    print('\n== 时间线（CanUse/SP/LogicState/0x2D，压缩 0x13）==')
    last13 = -10
    shown = 0
    for n, desc in events:
        if desc.startswith('0x13'):
            if n - last13 > 1:
                print('   #%d %s' % (n, desc))
            last13 = n
            continue
        print('   #%d %s' % (n, desc))
        shown += 1
        if shown > 400:
            print('   ...(截断)')
            break


if __name__ == '__main__':
    main()
