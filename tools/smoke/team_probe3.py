# -*- coding: utf-8 -*-
"""组队系统实机探针（第三轮：双会话邀请流）。

A 建队 → A 逐候选 op 发 [op, wide(B名)] 找 Invite（看 B 是否收到帧）→
B 逐候选 op 无参找 Join/Accept（看 A/B 是否收到成员行）→ A 再试踢人 op。
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (  # noqa: E402
    ci, create_role_packet, direct_custom, encode_frame, op, recv_frames, wide,
)

GAME_HOST, GAME_PORT = "127.0.0.1", 19061
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "team_probe3_frames.txt")


def login_packet(blob32):
    def slot(b):
        return struct.pack('<I', len(b) & 0xFFFFFFFF) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(blob32) + slot(bytes(16)) + struct.pack('<I', 0) + slot(bytes(16)) + struct.pack('<I', 0)
    return bytes(w)


def summarize(frames):
    ops = {}
    for f in frames:
        if op(f) not in (0x11, 0x12, 0x13, 0x10, 0x1E, 0x15, 0x18, 0x19, 0x1D):
            continue
        k = '0x%02X' % op(f)
        if op(f) == 0x18 and len(f) >= 5:
            k += '(v=%d)' % struct.unpack('<H', f[1:3])[0]
        if op(f) == 0x1E and len(f) > 3 and f[3] == 2 and len(f) >= 8:
            k += '(msg=%d)' % struct.unpack('<i', f[4:8])[0]
        ops[k] = ops.get(k, 0) + 1
    return dict(sorted(ops.items()))


def main():
    name_a = '甲%d' % (int(time.time()) % 100000)
    name_b = '乙%d' % (int(time.time()) % 100000)
    sa = socket.create_connection((GAME_HOST, GAME_PORT), timeout=5)
    sb = socket.create_connection((GAME_HOST, GAME_PORT), timeout=5)
    cap = []

    def step(sock, tag, pkt, expect=1, timeout=6, quiet=0.9):
        if pkt:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        interesting = [f for f in frames
                       if op(f) in (0x11, 0x12, 0x13, 0x10, 0x1E, 0x15, 0x18, 0x19, 0x1D)]
        cap.extend((tag, f) for f in interesting)
        s = summarize(frames)
        if s:
            print('[%-12s] %3d/%3d %s' % (tag, len(interesting), len(frames), s))
        return frames

    def drain(sock, tag):
        frames = recv_frames(sock, 0, timeout=1.2, quiet=0.6)
        interesting = [f for f in frames
                       if op(f) in (0x11, 0x12, 0x13, 0x10, 0x1E, 0x15, 0x18, 0x19, 0x1D)]
        cap.extend((tag, f) for f in interesting)
        s = summarize(frames)
        if s:
            print('[%-12s] %3d/%3d %s' % (tag, len(interesting), len(frames), s))
        return frames

    for sock, name, tag in ((sa, name_a, 'A-login'), (sb, name_b, 'B-login')):
        blob = os.urandom(16) + bytes(16)
        step(sock, tag, login_packet(blob))
        step(sock, tag, b"\x03")
        pkt = create_role_packet(name=name, book='book1')
        step(sock, tag, pkt, timeout=25)
        step(sock, tag, b"\x04" + pkt[1:], expect=20, timeout=30, quiet=2.0)
        step(sock, tag, b"\x09", expect=50, timeout=60, quiet=2.5)

    print('A=%s B=%s' % (name_a, name_b))
    # A 建队
    step(sa, 'A-o310', direct_custom(0x1E, [ci(310)]))
    # A 逐候选 op 邀请 B（宽串名字）
    for o in (314, 315, 317, 318, 319, 442, 443):
        step(sa, 'A-inv%d' % o, direct_custom(0x1E, [ci(o), wide(name_b)]), timeout=3, quiet=0.8)
        drain(sb, 'B-recv%d' % o)
    # B 逐候选 op 尝试加入（无参）
    for o in (311, 314, 315, 316, 317, 318, 319, 443, 444, 388, 326):
        step(sb, 'B-join%d' % o, direct_custom(0x1E, [ci(o)]), timeout=3, quiet=0.8)
        drain(sa, 'A-recvj%d' % o)
    # A 踢人候选（宽串名字）
    for o in (314, 315, 317, 442, 443):
        step(sa, 'A-kick%d' % o, direct_custom(0x1E, [ci(o), wide(name_b)]), timeout=3, quiet=0.8)
        drain(sb, 'B-recvk%d' % o)
    # 清场：双方离队
    step(sa, 'A-312', direct_custom(0x1E, [ci(312)]), timeout=3, quiet=0.8)
    step(sb, 'B-312', direct_custom(0x1E, [ci(312)]), timeout=3, quiet=0.8)

    sa.close()
    sb.close()
    with open(OUT, 'w', encoding='utf-8') as fh:
        for tag, f in cap:
            fh.write('%s %s\n' % (tag, f.hex()))
    print('wrote %d frames -> %s' % (len(cap), OUT))


if __name__ == '__main__':
    main()
