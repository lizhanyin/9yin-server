# -*- coding: utf-8 -*-
"""官服怒气回复·逐阶段帧取证：把 55866 会话里所有携带 SP 的帧全找出来（0x10 单帧/多属性帧、0x0D 批帧、
其余类型含 SP 索引或名字），并与战斗事件(出招msg20/命中msg24/受伤msg18/0x2D/CD 0x11/169/View40/LogicState)
按帧序对齐，输出阶段化时间轴。
用法: python -I rage_stage_frames.py <s2c.bin> [--all-frames]
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

_SPEC2 = importlib.util.spec_from_file_location(
    'view40_forensics', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'view40_forensics.py'))
_V4 = importlib.util.module_from_spec(_SPEC2)
_SPEC2.loader.exec_module(_V4)


def props_of_0x10(fr, entries):
    """0x10 属性帧: [10B头][u16 count]([u16 idx][typed value])* → [(idx, val)] 或 None"""
    if len(fr) < 12:
        return None
    pc = struct.unpack_from('<H', fr, 10)[0]
    off = 12
    out = []
    for _ in range(pc):
        if off + 2 > len(fr):
            return None
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= len(entries):
            return None
        ptype = entries[idx][1]
        try:
            val, off = _V4.read_prop(fr, off, ptype)
        except Exception:
            return None
        if val is None:
            return None
        out.append((idx, val))
    return out if off <= len(fr) else out


def main():
    path = sys.argv[1]
    show_all = '--all-frames' in sys.argv
    frames = _CD.load(path)
    tbl = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(tbl)
    sp_idx = next(i for i, (n, _t) in enumerate(entries) if n == 'SP')
    ls_idx = next((i for i, (n, _t) in enumerate(entries) if n == 'LogicState'), None)
    print('SP idx=%d  LogicState idx=%s  表项=%d' % (sp_idx, ls_idx, count))

    events = []  # (帧号, 标签, 详情, 携带帧hex或None)

    for n, fr in enumerate(frames):
        if not fr:
            continue
        tag = fr[0]
        if tag == 0x10:
            props = props_of_0x10(fr, entries)
            if props:
                named = {entries[i][0]: v for i, v in props}
                if 'SP' in named:
                    events.append((n, 'SP', 'SP=%r %s' % (named['SP'], '+'.join(
                        '%s=%r' % (entries[i][0], v) for i, v in props if entries[i][0] != 'SP')),
                        fr.hex()))
                elif ls_idx is not None and ls_idx in [i for i, _ in props]:
                    events.append((n, 'LogicState', str(named.get('LogicState')), fr.hex()))
        elif tag == 0x0D:
            if b'SP\x00' in fr:
                events.append((n, '0x0D含SP', fr[:80].hex(), None))
        elif tag in (0x1E, 0x27):
            try:
                vals = _CD.parse_tvarlist(fr[1:])
            except Exception:
                continue
            if not vals or not isinstance(vals[0], int):
                continue
            if vals[0] == 20 and len(vals) > 3 and isinstance(vals[2], str):
                tgt = vals[3] if len(vals) > 3 else '?'
                events.append((n, 'cast', '%s → %s' % (vals[2], tgt), None))
            elif vals[0] == 24 and len(vals) > 2:
                events.append((n, 'msg24', 'c1=%s 受=%s dmg=%s' % (
                    vals[2], vals[1], vals[3] if len(vals) > 3 else '?'), None))
            elif vals[0] == 18:
                events.append((n, 'msg18', str(vals[2] if len(vals) > 2 else vals), None))
            elif vals[0] == 169:
                events.append((n, 'msg169', str(vals[1:5]), None))
        elif tag == 0x2D:
            events.append((n, '0x2D', '', None))
        elif tag == 0x11 and len(fr) >= 16:
            view = struct.unpack_from('<I', fr, 1)[0]
            row = struct.unpack_from('<I', fr, 5)[0]
            if view in (109,):  # cooldown_rec
                cat, s, e = struct.unpack_from('<iqq', fr, 9)
                events.append((n, 'CD', 'cat=%d %d→%d (%dms)' % (cat, s, e, e - s), None))
        elif tag == 0x18:
            view = struct.unpack_from('<H', fr, 1)[0]
            if view == 40:
                row, _, _ = _V4.parse_row(fr, entries, count)
                events.append((n, 'View40', ' '.join('%s=%r' % kv for kv in list(row.items())[:6]), None))

    print('事件数=%d' % len(events))
    prev_sp = None
    for n, kind, detail, hx in events:
        mark = ''
        if kind == 'SP':
            try:
                val = int(detail.split('SP=')[1].split()[0])
                if prev_sp is not None:
                    mark = '  Δ%+d' % (val - prev_sp)
                prev_sp = val
            except Exception:
                pass
        print('#%-7d %-9s %-58s%s' % (n, kind, detail[:58], mark))
        if show_all and hx:
            print('           hex=%s' % hx)


if __name__ == '__main__':
    main()
