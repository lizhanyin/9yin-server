# -*- coding: utf-8 -*-
"""真机反馈五问题取证：LogicState 时间线 / view40 帧 / SP 属性 / 玩家全量帧完整属性。

用法: python -I combat_forensics2.py <s2c.bin>
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)
load, parse_09, s2c_customs = _CD.load, _CD.parse_09, _CD.s2c_customs

OFFICIAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                        'nineyin', 'src', 'main', 'resources',
                        'nineyin', 'protocol', 'official_property_table.txt')


def read_prop(frame, off, ptype):
    if ptype == 1:
        return frame[off], off + 1
    if ptype == 2:
        return struct.unpack_from('<H', frame, off)[0], off + 2
    if ptype == 3:
        return struct.unpack_from('<i', frame, off)[0], off + 4
    if ptype == 4:
        return struct.unpack_from('<q', frame, off)[0], off + 8
    if ptype == 5:
        return round(struct.unpack_from('<f', frame, off)[0], 3), off + 4
    if ptype == 7:
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        return frame[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        return frame[off:off + n].decode('utf-16-le', 'replace'), off + n
    if ptype == 9:
        oid, owner = struct.unpack_from('<II', frame, off)
        return f'obj({oid:#x},{owner:#x})', off + 8
    raise ValueError(ptype)


def frame_props(frame, entries):
    """0x10 帧：isView@1 obj@2 owner@6 count@10 (u16 idx + 值)*。"""
    is_view = frame[1]
    obj, owner, n = struct.unpack_from('<IIH', frame, 2)
    off = 12
    out = []
    for _ in range(n):
        idx = struct.unpack_from('<H', frame, off)[0]
        off += 2
        name, ptype = entries[idx] if idx < len(entries) else ('?', 0)
        value, off = read_prop(frame, off, ptype)
        out.append((idx, name, value))
    return is_view, obj, owner, out


def main():
    path = sys.argv[1]
    frames = load(path)
    table_frame = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = parse_09(table_frame)

    # 1. SP/MaxSP 类属性名（官方表）
    print('== 官方表 SP/NuQi/Max 相关属性 ==')
    official = []
    for line in open(OFFICIAL, encoding='ascii', errors='replace'):
        parts = line.strip().split('\t')
        if len(parts) == 3:
            official.append((int(parts[0]), parts[1], int(parts[2])))
    for i, (idx, name, t) in enumerate(official):
        low = name.lower()
        if 'sp' == low or low.startswith('maxsp') or 'nuqi' in low or low in ('sp',):
            print(f'   official[{i}] idx={idx} {name} type={t}')

    # 2. 玩家 0x10 帧 LogicState(idx463)/CantUseSkill/SP 时间线
    print('\n== 玩家 0x10 帧 LogicState/CantUseSkill/SP 时间线 ==')
    customs = list(s2c_customs(frames))
    msg20_idx = [i for i, op, mid, _ in customs if mid == 20]
    first20 = msg20_idx[0] if msg20_idx else -1
    for idx, fr in enumerate(frames):
        if not fr or fr[0] != 0x10 or len(fr) < 14 or fr[1] != 0:
            continue
        obj = struct.unpack_from('<I', fr, 2)[0]
        if obj != 0x11000001:
            continue
        n = struct.unpack_from('<H', fr, 10)[0]
        if n < 3:
            continue
        try:
            _, _, _, props = frame_props(fr, entries)
        except Exception:
            continue
        d = {name: value for _, name, value in props}
        if 'LogicState' in d or 'SP' in d or 'CantUseSkill' in d:
            keys = {k: d[k] for k in ('LogicState', 'CantUseSkill', 'SP', 'MaxSP') if k in d}
            tag = 'PRE-CAST' if idx < first20 else ('CAST#1 @%d' % first20 if abs(idx - first20) < 30 else 'post')
            print(f'   #{idx:<5} n={n:<4} {tag:<12} {keys}')

    # 3. 全量玩家帧(#3982) 所有属性名（找 SP/MaxSP/CanUse）
    print('\n== 全量玩家帧属性名全集（首个 n>100 帧）==')
    for idx, fr in enumerate(frames):
        if not fr or fr[0] != 0x10 or len(fr) < 14:
            continue
        n = struct.unpack_from('<H', fr, 10)[0]
        obj = struct.unpack_from('<I', fr, 2)[0]
        if n > 100 and obj == 0x11000001:
            _, _, _, props = frame_props(fr, entries)
            names = [f'{i}:{nm}' for i, nm, v in props]
            print('   first 60:', names[:60])
            print('   60-130 :', names[60:130])
            print('   130-end:', names[130:])
            spvals = [(nm, v) for _, nm, v in props if nm and ('SP' in nm or 'CanUse' in nm)]
            print('   SP/CanUse:', spvals)
            break

    # 4. view 帧（isView=1）：obj=40 技能视图的属性分布
    print('\n== isView=1 0x10 帧统计 ==')
    from collections import Counter
    view_objs = Counter()
    view_props = Counter()
    samples = {}
    for idx, fr in enumerate(frames):
        if not fr or fr[0] != 0x10 or len(fr) < 14 or fr[1] != 1:
            continue
        obj = struct.unpack_from('<I', fr, 2)[0]
        n = struct.unpack_from('<H', fr, 10)[0]
        view_objs[(obj, n)] += 1
        try:
            _, _, _, props = frame_props(fr, entries)
        except Exception:
            continue
        for pidx, nm, v in props:
            view_props[(obj, pidx, nm)] += 1
        samples.setdefault(obj, []).append((idx, props))
    for (obj, n), cnt in view_objs.most_common(12):
        print(f'   obj={obj} n={n} x{cnt}')
    print('   -- 属性分布（前 20）--')
    for (obj, pidx, nm), cnt in view_props.most_common(20):
        print(f'      obj={obj} idx={pidx} {nm} x{cnt}')
    for obj, items in samples.items():
        if obj == 40:
            print('   -- obj=40 样例（首 6 帧，含值）--')
            for idx, props in items[:6]:
                print(f'      #{idx} {[(i, nm, v) for i, nm, v in props]}')
            break

    # 5. view40 帧与 msg20/cast 的时间关系（第一窗口）
    print('\n== view40 帧时间线（首个 msg20 前后各 20 帧）==')
    shown = 0
    for idx, fr in enumerate(frames):
        if not fr or fr[0] != 0x10 or len(fr) < 14 or fr[1] != 1:
            continue
        obj = struct.unpack_from('<I', fr, 2)[0]
        if obj != 40:
            continue
        if abs(idx - first20) <= 25 and shown < 14:
            try:
                _, _, _, props = frame_props(fr, entries)
                print(f'   #{idx} ({"+" if idx >= first20 else "-"}{abs(idx - first20)}) '
                      f'{[(i, nm, v) for i, nm, v in props]}')
                shown += 1
            except Exception:
                pass


if __name__ == '__main__':
    main()
