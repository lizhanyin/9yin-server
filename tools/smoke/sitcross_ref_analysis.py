# -*- coding: utf-8 -*-
"""参考服打坐抓包分析：LogicState 时间线 + 240 往返数据。
用法: python -I sitcross_ref_analysis.py <s2c.bin> <c2s.bin>
对齐 docs/go-source-protocol-notes.md §6.2 0x10 布局与 §4 wiretype 表。
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

SZ = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8, 7: None, 8: None, 9: 8}  # 4/6 均 8B double（实测 188 面板对齐）


def parse_profile(buf, off, names, types, limit):
    """按 0x09 表解析属性档案，返回 (props, next_off)。索引越界/长度不符返回 None。"""
    props = {}
    for _ in range(limit):
        if off + 2 > len(buf):
            return None
        idx = struct.unpack_from('<H', buf, off)[0]
        off += 2
        if idx >= len(names):
            return None
        t = types[idx]
        if t == 7:  # string: u32 len(含NUL) + bytes + NUL
            if off + 4 > len(buf):
                return None
            ln = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if ln < 1 or off + ln > len(buf):
                return None
            props[names[idx]] = buf[off:off + ln].rstrip(b'\x00').decode('gbk', 'replace')
            off += ln
        elif t == 8:  # widestring: u32 字节长(含2B NUL) + UTF16LE + u16 0
            if off + 4 > len(buf):
                return None
            ln = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if ln < 2 or off + ln > len(buf):
                return None
            props[names[idx]] = buf[off:off + ln - 2].decode('utf-16-le', 'replace')
            off += ln
        else:
            sz = SZ.get(t)
            if sz is None or off + sz > len(buf):
                return None
            raw = buf[off:off + sz]
            off += sz
            if t == 1:
                props[names[idx]] = raw[0]
            elif t == 2:
                props[names[idx]] = struct.unpack('<H', raw)[0]
            elif t == 3:
                props[names[idx]] = struct.unpack('<i', raw)[0]
            elif t == 5:
                props[names[idx]] = round(struct.unpack('<f', raw)[0], 3)
            elif t in (4, 6):
                props[names[idx]] = round(struct.unpack('<d', raw)[0], 3)
            elif t == 9:
                o, w = struct.unpack('<II', raw)
                props[names[idx]] = 'obj(0x%x,0x%x)' % (o, w)
    return props, off


def main():
    s2c_path, c2s_path = sys.argv[1], sys.argv[2]
    s2c = _CD.load(s2c_path)
    c2s = _CD.load(c2s_path)
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    names = [n for n, _ in entries]
    types = [t for _, t in entries]

    # 玩家对象 = 0x10/0x0D 帧里出现最多的 obj
    from collections import Counter
    objs = Counter()
    for fr in s2c:
        if fr and fr[0] == 0x10 and len(fr) >= 12 and fr[1] == 0:
            objs[struct.unpack_from('<I', fr, 2)[0]] += 1
    player_obj = objs.most_common(1)[0][0]
    print('player obj=0x%x 帧数=%d' % (player_obj, objs[player_obj]))

    # LogicState 变化时间线（玩家 0x10 档案帧）
    prev = {}
    print('\n== LogicState 变化帧（玩家 0x10）==')
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) < 12 or fr[1] != 0:
            continue
        if struct.unpack_from('<I', fr, 2)[0] != player_obj:
            continue
        cnt = struct.unpack_from('<H', fr, 10)[0]
        parsed = parse_profile(fr, 12, names, types, cnt)
        if parsed is None:
            continue
        props, end = parsed
        if end != len(fr):
            continue  # 尾部不齐＝非本格式帧
        ls = props.get('LogicState')
        if ls != prev.get('LogicState'):
            diff = {k: v for k, v in props.items() if prev.get(k) != v}
            print('#%d props=%d LogicState=%s diff=%s' % (n, cnt, ls, diff))
        prev.update(props)

    # 240/211 附近 s2c 自定义回复计数
    print('\n== C2S 240 序列 ==')
    for idx, seq, mid, vals in _CD.c2s_customs(c2s):
        if mid == 240:
            print('c2s#%d seq=%d [240] %s' % (idx, seq, vals[1:]))


if __name__ == '__main__':
    main()
