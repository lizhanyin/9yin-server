# -*- coding: utf-8 -*-
"""本地抓包打坐交互全景 dump：c2s 全 custom + s2c 240/102/LogicState/HP/MP 相关帧。
用法: python -I sitcross_local_dump.py <c2s.bin> <s2c.bin>
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

SZ = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8, 7: None, 8: None, 9: 8}


def parse_profile(buf, off, names, types, limit):
    props = {}
    for _ in range(limit):
        if off + 2 > len(buf):
            return None
        idx = struct.unpack_from('<H', buf, off)[0]
        off += 2
        if idx >= len(names):
            return None
        t = types[idx]
        if t == 7:
            if off + 4 > len(buf):
                return None
            ln = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if ln < 1 or off + ln > len(buf):
                return None
            props[names[idx]] = buf[off:off + ln].rstrip(b'\x00').decode('gbk', 'replace')
            off += ln
        elif t == 8:
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
    if off != len(buf):
        return None
    return props


def main():
    c2s_path, s2c_path = sys.argv[1], sys.argv[2]
    c2s = _CD.load(c2s_path)
    s2c = _CD.load(s2c_path)

    print('== C2S 全部 0x0A custom 帧（解析失败也列原始 hex 前 40B）==')
    nparsed = 0
    for idx, fr in enumerate(c2s):
        if not fr or fr[0] != 0x0A or len(fr) < 24:
            continue
        seq = struct.unpack_from('<I', fr, 17)[0]
        vals = _CD.parse_tvarlist(fr[21:])
        if vals and isinstance(vals[0], int):
            nparsed += 1
            print('c2s#%-4d seq=%-5d msgId=%-4d vals=%s' % (idx, seq, vals[0], vals[1:]))
        else:
            print('c2s#%-4d seq=%-5d RAW=%s' % (idx, seq, fr[21:61].hex()))
    print('(解析成功 %d 帧)' % nparsed)

    # s2c: 0x09 表
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    names = [n for n, _ in entries]
    types = [t for _, t in entries]

    # 玩家对象
    from collections import Counter
    objs = Counter()
    for fr in s2c:
        if fr and fr[0] == 0x10 and len(fr) >= 12 and fr[1] == 0:
            objs[struct.unpack_from('<I', fr, 2)[0]] += 1
    player_obj = objs.most_common(1)[0][0]
    print('\nplayer obj=0x%x' % player_obj)

    # s2c: s2c customs 中 240 相关 + 玩家 0x10 帧中 LogicState/HP/MP/State/HPUpSpeed 相关变化
    print('\n== S2C 0x1E/0x27 custom 全部（msgId 排序同现）==')
    for idx, op, mid, vals in _CD.s2c_customs(s2c):
        if mid in (240, 20, 24, 26):
            print('s2c#%-4d op=0x%02X msgId=%d vals=%s' % (idx, op, mid, vals))

    WATCH = ('LogicState', 'HP', 'MP', 'MaxHP', 'MaxMP', 'HPUpSpeed', 'MPUpSpeed',
             'HPHeartSpeed', 'State', 'SP', 'QingGongPoint', 'CurSkillID', 'Ene', 'Sat')
    print('\n== 玩家 0x10 帧含 WATCH 属性变化 ==')
    prev = {}
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) < 12 or fr[1] != 0:
            continue
        if struct.unpack_from('<I', fr, 2)[0] != player_obj:
            continue
        cnt = struct.unpack_from('<H', fr, 10)[0]
        props = parse_profile(fr, 12, names, types, cnt)
        if props is None:
            continue
        diff = {k: v for k, v in props.items()
                if k in WATCH and prev.get(k) != v}
        if diff and n > 30:  # 跳过登录首帧
            print('s2c#%-5d props=%-3d %s' % (n, cnt, diff))
        prev.update(props)


if __name__ == '__main__':
    main()
