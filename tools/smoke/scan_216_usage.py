# -*- coding: utf-8 -*-
"""扫描 c2s 抓包里的 msg216 USE_QINGGONG 事件序列：id/flag/时间间隔——
判定客户端按住 Shift（冲刺）时 216 的真实上报节奏（begin 重发？end 配对？）。

用法: python -I scan_216_usage.py <c2s.bin> ...
"""
import struct
import sys

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


def parse_tvarlist(payload):
    """count u16 + [tag value]*；返回值列表或 None。"""
    if len(payload) < 2:
        return None
    count = struct.unpack_from('<H', payload)[0]
    if count == 0 or count > 1024:
        return None
    rest = payload[2:]
    vals = []
    for _ in range(count):
        if not rest:
            return None
        tag = rest[0]
        rest = rest[1:]
        if tag == 2:
            vals.append(struct.unpack('<i', rest[:4])[0]); rest = rest[4:]
        elif tag == 3:
            vals.append(struct.unpack('<q', rest[:8])[0]); rest = rest[8:]
        elif tag in (4, 5):
            vals.append(round(struct.unpack('<f' if tag == 4 else '<d', rest[:4 if tag == 4 else 8])[0], 3))
            rest = rest[4:] if tag == 4 else rest[8:]
        elif tag in (6, 7):
            slen = struct.unpack('<I', rest[:4])[0]; rest = rest[4:]
            raw = rest[:slen]
            rest = rest[slen:]
            try:
                vals.append(raw[:-1].decode('utf-8' if tag == 6 else 'utf-16-le', errors='replace'))
            except Exception:
                vals.append(repr(raw[:20]))
        elif tag == 8:
            vals.append(struct.unpack('<Q', rest[:8])[0]); rest = rest[8:]
        else:
            return None
        if len(rest) == 0 and len(vals) < count:
            return None
    return vals


def scan(path):
    frames = decode_frames(open(path, 'rb').read())
    events = []
    for idx, frame in enumerate(frames):
        if not frame or len(frame) < 3:
            continue
        op = frame[0]
        vals = None
        if op == 0x0A and len(frame) >= 23:
            vals = parse_tvarlist(frame[21:])
        elif op == 0x1E and len(frame) >= 1:
            vals = parse_tvarlist(frame[1:])
        if not vals or not isinstance(vals[0], int) or vals[0] != 216:
            continue
        qg_id = vals[1] if len(vals) > 1 and isinstance(vals[1], str) else '?'
        flag = vals[2] if len(vals) > 2 and isinstance(vals[2], int) else '?'
        events.append((idx, qg_id, flag))
    return events


def main():
    for path in sys.argv[1:]:
        events = scan(path)
        if not events:
            continue
        print(f'== {path.split(chr(92))[-1]}: {len(events)} 条 216')
        # 逐 id 统计 flag 序列
        from collections import defaultdict
        by_id = defaultdict(list)
        for idx, qg_id, flag in events:
            by_id[qg_id].append((idx, flag))
        for qg_id, seq in by_id.items():
            flags = ''.join(str(f) for _, f in seq[:40])
            gaps = [seq[i + 1][0] - seq[i][0] for i in range(len(seq) - 1)]
            print(f'  {qg_id}: n={len(seq)} flags={flags}{"..." if len(seq) > 40 else ""} 帧距(中位)={sorted(gaps)[len(gaps)//2] if gaps else "-"} min={min(gaps) if gaps else "-"}')


if __name__ == '__main__':
    main()
