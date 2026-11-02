# -*- coding: utf-8 -*-
"""解码抓包中 View40(已学技能容器) 各行属性，重点核对 CoolDownTime 官方值。

用法: python -I view40_forensics.py <s2c.bin> [viewId=40]
帧布局: 0x18 | u16 view | u16 objIdx | u16 propCount | (u16 propIdx + 0x09表线型值)*
依赖同目录 capture_deep.py（XOR 解码 + 0x09 属性表解析）。
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)


def read_prop(frame, off, ptype):
    """按 0x09 表线型读值（combat_forensics.prop_type_reader 同款映射）。"""
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
    if ptype == 6:
        return round(struct.unpack_from('<d', frame, off)[0], 3), off + 8
    if ptype == 7:  # 窄串 u32len+NUL
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        raw = frame[off:off + n]
        return raw.rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:  # 宽串 u32len
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        raw = frame[off:off + n]
        return raw.decode('utf-16-le', 'replace'), off + n
    if ptype == 9:  # object 8B
        oid, owner = struct.unpack_from('<II', frame, off)
        return 'obj(%#x,%#x)' % (oid, owner), off + 8
    return None, off


def parse_row(fr, entries, count):
    """解析单个 0x18 行帧 → dict(name→value)。"""
    prop_count = struct.unpack_from('<H', fr, 5)[0]
    off = 7
    row = {}
    while off + 2 <= len(fr) and len(row) < prop_count:
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
    return row, prop_count, off == len(fr)


def main():
    path = sys.argv[1]
    view_id = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    frames = _CD.load(path)

    table = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    idx_to_name = {i: n for i, (n, t) in enumerate(entries)}

    for fr in frames:
        if fr and fr[0] == 0x15 and len(fr) >= 5:
            v, cap = struct.unpack_from('<HH', fr, 1)
            if v == view_id:
                print('create_view view=%d cap=%d' % (v, cap))
    adds = [f for f in frames if f and f[0] == 0x18 and len(f) >= 7
            and struct.unpack_from('<H', f, 1)[0] == view_id]
    print('view_add rows on view %d: x%d' % (view_id, len(adds)))
    if not adds:
        return

    # CoolDownTime 属性在 0x09 表中的下标（用于快速核对）
    cd_idx = [i for i, (n, t) in enumerate(entries) if n == 'CoolDownTime']
    print('CoolDownTime 0x09 表下标:', cd_idx)

    # 首行全属性
    row, pc, ok = parse_row(adds[0], entries, count)
    print('首行解析 %s 属性 %d/%d: %s' % ('OK' if ok else 'FAIL', len(row), pc, idx_to_name.get(0)))
    for name, val in row.items():
        print('   %s = %r' % (name, val))

    print('\n== 全行 ConfigID / CoolDownTime / Level / MaxLevel ==')
    bad = 0
    for fr in adds:
        row, pc, ok = parse_row(fr, entries, count)
        if not ok:
            bad += 1
        cfg = row.get('ConfigID', '?')
        print('   %-24s CoolDownTime=%-8r Level=%-4r MaxLevel=%-4r %s'
              % (str(cfg), row.get('CoolDownTime'), row.get('Level'),
                 row.get('MaxLevel'), '' if ok else '<PARSE_FAIL>'))
    print('解析失败行数:', bad)


if __name__ == '__main__':
    main()
