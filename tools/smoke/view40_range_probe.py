# -*- coding: utf-8 -*-
"""View40 技能行射程/形状参数对拍探针（2026-11-05「最大射程全是50」排障）。

用法（-I 隔离）:
  python -I view40_range_probe.py <s2c.bin> [更多s2c.bin ...]

解析 0x18 view_add(view=40) 行，按 0x09 表线型解出全属性，输出每行
ConfigID + TargetShapePara1/2 + HitShapePara1/2 + PrepareTime 等射程族，
并统计形状参数分布。帧格式同 capture_deep.py（XOR CC3B6DFA + EE 转义）。

0x18 布局: 0x18 | u16 view | u16 objIdx | u16 propCount | (u16 propIdx + 线型值)*
线型: 1=i8 2=i16 3=i32 4=i64 5=f32 7=str(u32len+bytes+NUL) 8=wide(u32len+UTF16LE+0000) 9=obj(8B)
"""
import struct
import sys
from collections import Counter

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])
TABLE = r"G:\pojie\ai\9yin-server\nineyin\src\main\resources\nineyin\protocol\official_property_table.txt"


def decode_frames(buf):
    frames, body = [], bytearray()
    i = 0
    while i < len(buf):
        if buf[i] == 0xEE and i + 1 < len(buf):
            nxt = buf[i + 1]
            if nxt == 0x00:
                body.append(0xEE)
                i += 2
            elif nxt == 0xEE:
                frames.append(bytes(b ^ KEY[j % 4] for j, b in enumerate(body)))
                body = bytearray()
                i += 2
            else:
                break
        else:
            body.append(buf[i])
            i += 1
    return frames


def load_table():
    """官方表 idx<TAB>name<TAB>type → (names_by_idx, types_by_idx)。"""
    names, types = {}, {}
    for line in open(TABLE, encoding='ascii'):
        parts = line.strip().split('\t')
        if len(parts) == 3:
            idx, name, code = int(parts[0]), parts[1], int(parts[2])
            names[idx] = name
            types[idx] = code
    return names, types


def read_value(buf, off, code):
    """按线型读一个值 → (value, newOff)；长度不符返回 (None, None)。"""
    try:
        if code == 1:
            return struct.unpack_from('<b', buf, off)[0], off + 1
        if code == 2:
            return struct.unpack_from('<h', buf, off)[0], off + 2
        if code == 3:
            return struct.unpack_from('<i', buf, off)[0], off + 4
        if code == 4:
            return struct.unpack_from('<q', buf, off)[0], off + 8
        if code == 5:
            return struct.unpack_from('<f', buf, off)[0], off + 4
        if code == 7:
            n = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if n < 1 or off + n > len(buf):
                return None, None
            return buf[off:off + n - 1].decode('latin-1'), off + n
        if code == 8:
            n = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if n < 2 or off + n > len(buf):
                return None, None
            return buf[off:off + n - 2].decode('utf-16-le', errors='replace'), off + n
        if code == 9:
            return struct.unpack_from('<q', buf, off)[0], off + 8
    except struct.error:
        return None, None
    return None, None


def parse_view_add(fr, names, types):
    """0x18 帧 → (view, objIdx, {name: value})；布局不符返回 None。"""
    if not fr or fr[0] != 0x18 or len(fr) < 7:
        return None
    view, obj_idx, count = struct.unpack_from('<HHH', fr, 1)
    props, off = {}, 7
    for _ in range(count):
        if off + 2 > len(fr):
            return None
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        code = types.get(idx)
        if code is None:
            return None
        value, off = read_value(fr, off, code)
        if value is None:
            return None
        props[names.get(idx, str(idx))] = value
    return view, obj_idx, props


INTEREST = ('ConfigID', 'StaticData', 'Level', 'PrepareTime',
            'TargetShapePara1', 'TargetShapePara2',
            'HitShapePara1', 'HitShapePara2')


def probe(paths):
    names, types = load_table()
    for path in paths:
        frames = decode_frames(open(path, 'rb').read())
        rows = []
        for fr in frames:
            parsed = parse_view_add(fr, names, types)
            if parsed and parsed[0] == 40:
                rows.append(parsed[2])
        print(f'== {path}: View40 rows={len(rows)}')
        for props in rows:
            cells = [f'{k}={props[k]!r}' for k in INTEREST if k in props]
            missing = [k for k in ('TargetShapePara2', 'HitShapePara2') if k not in props]
            tag = f'  [{",".join(missing)}缺失]' if missing else ''
            print('  ' + ' '.join(cells) + tag)
        for field in ('TargetShapePara2', 'HitShapePara2', 'TargetShapePara1', 'HitShapePara1'):
            vals = Counter(props.get(field) for props in rows)
            print(f'   {field} 分布: {dict(vals.most_common(8))}')


if __name__ == '__main__':
    probe(sys.argv[1:])
