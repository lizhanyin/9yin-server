# -*- coding: utf-8 -*-
"""扫描官方 s2c 抓包 0x10 帧（正确方言：idx u16 LE + 表线型值，无 tag 字节）。
统计玩家对象各属性索引的使用与样本值。

用法: python -I scan_prop_index2.py <s2c.bin> <official_property_table.txt>
"""
import struct
import sys
from collections import Counter

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


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


def load_table(path):
    entries = {}
    for line in open(path, 'r', encoding='ascii'):
        parts = line.strip().split('\t')
        if len(parts) == 3:
            entries[int(parts[0])] = (parts[1], int(parts[2]))
    return entries


SIZE = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4}


def parse_props(frame, table):
    obj = struct.unpack_from('<I', frame, 2)[0]
    owner = struct.unpack_from('<I', frame, 6)[0]
    count = struct.unpack_from('<H', frame, 10)[0]
    off = 12
    props = []
    for _ in range(count):
        if off + 2 > len(frame):
            return obj, owner, props, 'TRUNC'
        idx = struct.unpack_from('<H', frame, off)[0]
        off += 2
        entry = table.get(idx)
        if entry is None:
            return obj, owner, props, 'UNKIDX%d' % idx
        name, tcode = entry
        if tcode in SIZE:
            size = SIZE[tcode]
            if off + size > len(frame):
                return obj, owner, props, 'TRUNC'
            raw = frame[off:off + size]
            off += size
            if tcode == 1:
                val = raw[0]
            elif tcode == 2:
                val = struct.unpack('<H', raw)[0]
            elif tcode == 3:
                val = struct.unpack('<i', raw)[0]
            elif tcode == 4:
                val = struct.unpack('<q', raw)[0]
            else:
                val = round(struct.unpack('<f', raw)[0], 3)
        elif tcode in (7, 8):
            if off + 4 > len(frame):
                return obj, owner, props, 'TRUNC'
            slen = struct.unpack_from('<I', frame, off)[0]
            off += 4
            raw = frame[off:off + slen]
            off += slen
            try:
                val = raw[:-2].decode('utf-16-le' if tcode == 8 else 'gbk', errors='replace') if slen else ''
            except Exception:
                val = repr(raw[:20])
        elif tcode == 9:
            if off + 8 > len(frame):
                return obj, owner, props, 'TRUNC'
            val = struct.unpack_from('<Q', frame, off)[0]
            off += 8
        else:
            return obj, owner, props, 'TYPE%d' % tcode
        props.append((idx, name, val))
    return obj, owner, props, 'OK' if off == len(frame) else 'TAIL%d' % (len(frame) - off)


def main():
    s2c = sys.argv[1]
    table = load_table(sys.argv[2])
    frames = decode_frames(open(s2c, 'rb').read())
    status = Counter()
    cnt = Counter()
    samples = {}
    for f in frames:
        if not f or f[0] != 0x10 or len(f) < 12:
            continue
        obj, owner, props, st = parse_props(f, table)
        status[st] += 1
        if not st.startswith(('OK', 'TAIL')):
            continue
        for idx, name, val in props:
            cnt[(name, idx)] += 1
            samples.setdefault((name, idx), []).append(val)
    print('parse status:', dict(status))
    watch = ('QingGongPoint', 'MaxQingGongPoint', 'GJQingGongPoint', 'MaxGJQingGongPoint',
             'MaxQingGongPointAdd', 'ParryValue', 'MaxParry', 'MaxParryAdd', 'ParryUpSpeed',
             'ParryUpSpeedAdd', 'InParry', 'InManualParry', 'ParryRatio', 'ParryType',
             'HPUpSpeed', 'MPUpSpeed', 'HPUpSpeedAdd', 'MPUpSpeedAdd', 'HPHeartSpeed',
             'MPHeartSpeed', 'HP', 'MP', 'MaxHP', 'MaxMP', 'HitHP', 'LogicState', 'State')
    for (name, idx), n in sorted(cnt.items()):
        if name in watch:
            vals = samples[(name, idx)]
            print(f'  {name:22s} idx={idx:4d} n={n:3d} samples={vals[:5]}')


if __name__ == '__main__':
    main()
