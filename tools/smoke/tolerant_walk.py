# -*- coding: utf-8 -*-
"""容错帧游走：EE EE=终止符，EE 00=转义，EE xx(其他)=按字面 EE 处理（1 字节）。
验证 Go 服 View40 洪泛未转义 EE 假设：容错模式下违例周边帧应全部解为合法 opcode。"""
import struct
import sys

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def xor(data):
    return bytes(b ^ KEY[i % 4] for i, b in enumerate(data))


def walk_tolerant(data):
    frames, cur, i, tolerated = [], bytearray(), 0, 0
    while i < len(data):
        b = data[i]
        if b == 0xEE:
            if i + 1 >= len(data):
                break                       # 尾部孤立 EE
            nxt = data[i + 1]
            if nxt == 0x00:                 # 正常转义
                cur.append(0xEE)
                i += 2
                continue
            if nxt == 0xEE:                 # 帧终止
                frames.append(bytes(cur))
                cur = bytearray()
                i += 2
                continue
            cur.append(0xEE)                # 容错：未转义的字面 EE
            tolerated += 1
            i += 1
            continue
        cur.append(b)
        i += 1
    return frames, tolerated


def walk_strict(data):
    frames, cur, i = [], bytearray(), 0
    while i < len(data):
        b = data[i]
        if b == 0xEE:
            if i + 1 >= len(data):
                break
            if data[i + 1] == 0x00:
                cur.append(0xEE)
                i += 2
                continue
            if data[i + 1] == 0xEE:
                frames.append(bytes(cur))
                cur = bytearray()
                i += 2
                continue
            raise ValueError('bad escape @%d' % i)
        cur.append(b)
        i += 1
    return frames


def main():
    data = open(sys.argv[1] if len(sys.argv) > 1 else 'go_raw_wire.bin', 'rb').read()
    frames, tolerated = walk_tolerant(data)
    print('wire=%dB tolerant frames=%d tolerated_unescaped_EE=%d' % (len(data), len(frames), tolerated))
    plains = [xor(f) for f in frames]
    ops = [p[0] for p in plains if p]
    hist = {hex(o): ops.count(o) for o in sorted(set(ops))}
    print('op histogram:', hist)
    bad = [hex(o) for o in set(ops) if o > 0x40]
    print('suspicious opcodes (>0x40):', bad)

    # 对比：严格模式在首个违例处停止
    try:
        sf = walk_strict(data)
        print('strict frames before first violation:', len(sf))
    except ValueError as e:
        print('strict walk died at:', e)

    # 检查违例处的帧：tolerant 解出的帧里是否还有非标准 opcode 的帧（真伪影应归零/极少）
    weird = [(i, p) for i, p in enumerate(plains) if p and p[0] not in (
        0x01, 0x02, 0x04, 0x05, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11,
        0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
        0x1F, 0x20, 0x21, 0x27, 0x2D, 0x38)]
    print('frames with non-catalog opcodes:', len(weird))
    for i, p in weird[:5]:
        print('  #%d op=0x%02X len=%d %s' % (i, p[0], len(p), p[:24].hex()))


if __name__ == '__main__':
    main()
