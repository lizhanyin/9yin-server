# -*- coding: utf-8 -*-
"""组队系统实机探针（第一轮：单会话 op 扫描）。

对每个候选 op 发 [326, op]（以及 442-445 的 [msgId, msgId] 形态），
逐窗口抓 S2C 响应帧，钉死 op→行为映射与 nativeTeamDataInit/Change 帧形态。
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (  # noqa: E402
    ci, create_role_packet, decode_frames, direct_custom, encode_frame,
    narrow, op, recv_frames,
)

GAME_HOST, GAME_PORT = "127.0.0.1", 19061
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "team_probe_frames.txt")


def login_packet(blob32):
    def slot(b):
        return struct.pack('<I', len(b) & 0xFFFFFFFF) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(blob32) + slot(bytes(16)) + struct.pack('<I', 0) + slot(bytes(16)) + struct.pack('<I', 0)
    return bytes(w)


def main():
    blob = os.urandom(16) + bytes(16)
    sock = socket.create_connection((GAME_HOST, GAME_PORT), timeout=5)
    cap = []

    def step(name, pkt, expect=1, timeout=12, quiet=1.0):
        if pkt:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        interesting = []
        for f in frames:
            if op(f) in (0x11, 0x12, 0x13, 0x10, 0x1E, 0x15, 0x18, 0x19, 0x1D):
                interesting.append(f)
        cap.extend((name, f) for f in interesting)
        ops = {}
        for f in interesting:
            k = 'op=0x%02X' % op(f)
            if op(f) == 0x11 and len(f) >= 12:
                k += '(ri=%d)' % struct.unpack('<H', f[10:12])[0]
            if op(f) == 0x1E and len(f) > 3 and f[3] == 2 and len(f) >= 8:
                k += '(msg=%d)' % struct.unpack('<i', f[4:8])[0]
            ops[k] = ops.get(k, 0) + 1
        print('[%-10s] %3d/%3d frames %s' % (name, len(interesting), len(frames),
                                              dict(sorted(ops.items()))))
        return frames

    step('login', login_packet(blob))
    step('world', b"\x03")
    pkt = create_role_packet(name='队探%d' % (int(time.time()) % 100000), book='book1')
    step('create', pkt, timeout=25)
    step('choose', b"\x04" + pkt[1:], expect=20, timeout=30, quiet=2.0)
    step('ready', b"\x09", expect=50, timeout=60, quiet=2.5)

    # op 扫描：先建队（310=CreateTeam 反汇编钉死）
    step('t310create', direct_custom(0x1E, [ci(326), ci(310)]), timeout=5)
    # 其余 op 逐个发（int 无参形态；名字串 op 后续双会话轮再试）
    for o in (308, 309, 311, 312, 313, 314, 315, 316, 317, 318, 319, 326, 388):
        step('t%d' % o, direct_custom(0x1E, [ci(326), ci(o)]), timeout=3.5, quiet=0.8)
    # 442-445 家族按 [msgId, op=msgId] 与 [326, op] 双形态
    for o in (442, 443, 444, 445):
        step('m%d' % o, direct_custom(0x1E, [ci(o), ci(o)]), timeout=3.5, quiet=0.8)
        step('t%d' % o, direct_custom(0x1E, [ci(326), ci(o)]), timeout=3.5, quiet=0.8)

    sock.close()
    with open(OUT, 'w', encoding='utf-8') as fh:
        for name, f in cap:
            fh.write('%s %s\n' % (name, f.hex()))
    print('wrote %d frames -> %s' % (len(cap), OUT))


if __name__ == '__main__':
    main()
