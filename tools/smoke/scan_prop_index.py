# -*- coding: utf-8 -*-
"""扫描官方 s2c 抓包 0x10/0x0D 帧里的属性索引使用情况：
确认 QingGongPoint/ParryValue/MaxParry/HPUpSpeed 等到底用哪个索引下发（表内同名多处）。

用法: python -I scan_prop_index.py <s2c.bin> <official_property_table.txt> [grep值...]
"""
import struct
import sys
from collections import Counter, defaultdict

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
    entries = []
    for line in open(path, 'r', encoding='ascii'):
        parts = line.strip().split('\t')
        if len(parts) == 3:
            entries.append((int(parts[0]), parts[1], int(parts[2])))
    return entries


WIRE = {1: ('B', 1), 2: ('H', 2), 3: ('i', 4), 4: ('q', 8), 5: ('f', 4)}


def parse_props(payload):
    """0x10 属性区: count u16 + (idx u16, tag u8, value)*。tag 与线型码一致，7/8/9 带长度。"""
    props = []
    off = 0
    count = struct.unpack_from('<H', payload, off)[0]
    off += 2
    for _ in range(count):
        if off + 3 > len(payload):
            break
        idx, tag = struct.unpack_from('<HB', payload, off)
        off += 3
        if tag in WIRE:
            fmt, size = WIRE[tag]
            val = struct.unpack_from('<' + fmt, payload, off)[0]
            off += size
            props.append((idx, tag, val))
        elif tag in (7, 8):
            slen = struct.unpack_from('<I', payload, off)[0]
            off += 4
            raw = payload[off:off + slen]
            off += slen
            try:
                text = raw.decode('utf-16-le' if tag == 8 else 'gbk', errors='replace')
            except Exception:
                text = repr(raw)
            props.append((idx, tag, text))
        elif tag == 9:
            val = struct.unpack_from('<Q', payload, off)[0]
            off += 8
            props.append((idx, tag, val))
        else:
            break
    return props


def main():
    s2c = sys.argv[1]
    table_path = sys.argv[2]
    wanted = set(sys.argv[3:])
    entries = load_table(table_path)
    by_idx = {e[0]: e[1] for e in entries}
    # 同名多索引
    names = defaultdict(list)
    for idx, name, _t in entries:
        names[name].append(idx)

    usage = Counter()
    samples = defaultdict(list)
    for frame in decode_frames(open(s2c, 'rb').read()):
        if not frame:
            continue
        op = frame[0]
        if op == 0x10:
            obj = struct.unpack_from('<I', frame, 2)[0]
            owner = struct.unpack_from('<I', frame, 6)[0]
            props = parse_props(frame[12:])
            for idx, tag, val in props:
                usage[(obj, idx)] += 1
                if len(samples[idx]) < 6:
                    samples[idx].append((val, tag))
    print("== 玩家对象(1,1) 0x10 索引使用（前 80）:")
    for (obj, idx), n in sorted(usage.items(), key=lambda kv: -kv[1]):
        if obj != 1:
            continue
        name = by_idx.get(idx, '?')
        print(f"  idx={idx:4d} {name:26s} n={n:3d} samples={samples[idx][:4]}")

    print("\n== 关注名（同名多索引处）:")
    for name in ['QingGongPoint', 'MaxQingGongPoint', 'MaxQingGongPointAdd', 'GJQingGongPoint',
                 'MaxGJQingGongPoint', 'ParryValue', 'MaxParry', 'MaxParryAdd', 'ParryUpSpeed',
                 'ParryUpSpeedAdd', 'InParry', 'InManualParry', 'HPUpSpeed', 'MPUpSpeed',
                 'HPHeartSpeed', 'MPHeartSpeed', 'HP', 'MP', 'MaxHP', 'MaxMP']:
        indexes = names.get(name, [])
        seen = [(i, usage.get((1, i), 0)) for i in indexes]
        print(f"  {name:22s} 表内索引={indexes} 玩家帧计数={seen}")

    if wanted:
        print("\n== 值过滤:")
        for (obj, idx), n in sorted(usage.items()):
            vals = [s[0] for s in samples.get(idx, [])]
            if any(str(v) in wanted for v in vals):
                print(f"  obj={obj} idx={idx} {by_idx.get(idx,'?')} n={n} samples={samples[idx]}")


if __name__ == '__main__':
    main()
