# -*- coding: utf-8 -*-
"""57378·最终对齐：InParry/BuffInParry/msg54 ↔ CanUse翻转；行8(怒招)/行11 全属性；414/705/209。
用法: python -I rage_parity_probe.py <captures_dir>
"""
import importlib.util
import os
import struct
import sys

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


def parse_props(fr, entries, count, prop_off):
    row, off = {}, prop_off
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
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    idx_of = {}
    for i, (n, _t) in enumerate(entries):
        idx_of.setdefault(n, i)
    player = 0x11000001

    # ---- 行8 / 行11 / 行1 View40 全属性 ----
    rowprops = {}
    for fr in s2c:
        if fr and fr[0] == 0x18 and len(fr) >= 7 and struct.unpack_from('<H', fr, 1)[0] == 40:
            obj = struct.unpack_from('<H', fr, 3)[0]
            if obj in (1, 8, 11):
                rowprops[obj] = parse_props(fr, entries, count, 5)
    for r in (1, 8, 11):
        print('== View40 行%d 全属性 ==' % r)
        for k, v in rowprops.get(r, {}).items():
            print('   %-28s = %r' % (k, v))

    # ---- 统一时间轴：InParry属性 / 169 buff / CanUse翻转 / msg20出招 ----
    flips = []
    last_cu = {}
    parry_tl = []
    inparry_state = None
    for n, fr in enumerate(s2c):
        if not fr:
            continue
        if fr[0] == 0x10 and len(fr) >= 12:
            isview, obj = fr[1], struct.unpack_from('<I', fr, 2)[0]
            props = parse_props(fr, entries, count, 10)
            if isview == 1 and obj == 40 and 'CanUse' in props:
                owner = struct.unpack_from('<I', fr, 6)[0]
                if owner in last_cu and last_cu[owner] != props['CanUse']:
                    flips.append((n, 'CanUse 行%d: %d→%d' % (owner, last_cu[owner], props['CanUse'])))
                last_cu[owner] = props['CanUse']
            elif isview == 0 and obj == player and 'InParry' in props:
                if props['InParry'] != inparry_state:
                    inparry_state = props['InParry']
                    parry_tl.append((n, 'InParry属性=%r' % inparry_state))
        elif fr[0] in (0x1E, 0x27):
            try:
                vals = _CD.parse_tvarlist(fr[1:])
            except Exception:
                continue
            if not vals or not isinstance(vals[0], int):
                continue
            if vals[0] == 169 and len(vals) > 2 and isinstance(vals[2], str) \
                    and ('arry' in vals[2] or 'buf_' in vals[2]):
                parry_tl.append((n, '169 buff %s %s' % (vals[1], vals[2])))
            elif vals[0] == 20 and len(vals) > 2 and isinstance(vals[2], str):
                parry_tl.append((n, 'msg20 出招 %s' % vals[2]))
            elif vals[0] in (414, 705, 209, 419, 25, 24):
                parry_tl.append((n, 'msg%d %s' % (vals[0], vals[:6])))

    merged = sorted(flips + parry_tl)
    print('\n== 统一时间轴（CanUse翻转 / InParry / 169buff / msg20 / 414/705/209）==')
    for n, d in merged:
        print('   #%d %s' % (n, d))


if __name__ == '__main__':
    main()
