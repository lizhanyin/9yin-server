# -*- coding: utf-8 -*-
"""组队系统实机探针（第二轮：op-first 布局钉死后重扫）。

布局（decodeNativeTeamRequest 反汇编钉死）：
- op = 首值（外层 custom switch 将 308-319/326/388/442-445 一并路由至 handleTeamCustom）
- {310,311,312,444} = 恰 1 值（仅 op）；{313,316,445} = 2 值 [op, 名字(宽串)]
- 其余 op 先按 1 值试，无响应再补 int 参数轮
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (  # noqa: E402
    ci, create_role_packet, decode_frames, direct_custom, encode_frame,
    op, recv_frames, wide,
)

GAME_HOST, GAME_PORT = "127.0.0.1", 19061
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "team_probe2_frames.txt")
COMPANION = "江湖伙伴·与子偕老"


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
        interesting = [f for f in frames
                       if op(f) in (0x11, 0x12, 0x13, 0x10, 0x1E, 0x15, 0x18, 0x19, 0x1D)]
        cap.extend((name, f) for f in interesting)
        ops = {}
        for f in interesting:
            k = '0x%02X' % op(f)
            if op(f) == 0x11 and len(f) >= 12:
                k += '(ri=%d)' % struct.unpack('<H', f[10:12])[0]
            if op(f) == 0x1E and len(f) > 3 and f[3] == 2 and len(f) >= 8:
                k += '(msg=%d)' % struct.unpack('<i', f[4:8])[0]
            ops[k] = ops.get(k, 0) + 1
        print('[%-10s] %3d/%3d %s' % (name, len(interesting), len(frames), dict(sorted(ops.items()))))
        return frames

    step('login', login_packet(blob))
    step('world', b"\x03")
    pkt = create_role_packet(name='队探%d' % (int(time.time()) % 100000), book='book1')
    step('create', pkt, timeout=25)
    step('choose', b"\x04" + pkt[1:], expect=20, timeout=30, quiet=2.0)
    step('ready', b"\x09", expect=50, timeout=60, quiet=2.5)

    # 1 值 op：310 建队在前
    for o in (310, 311, 312, 444, 308, 309, 314, 315, 317, 318, 319, 326, 388, 442, 443):
        step('o%d' % o, direct_custom(0x1E, [ci(o)]), timeout=3.5, quiet=0.9)
    # 2 值名字 op（宽串）
    for o in (313, 316, 445):
        step('n%d' % o, direct_custom(0x1E, [ci(o), wide(COMPANION)]), timeout=4, quiet=1.0)
    # 带 1 个 int 参的重试（无响应的 op）
    for o in (314, 315, 317, 318, 319, 442, 443):
        step('i%d' % o, direct_custom(0x1E, [ci(o), ci(0)]), timeout=3, quiet=0.8)

    sock.close()
    with open(OUT, 'w', encoding='utf-8') as fh:
        for name, f in cap:
            fh.write('%s %s\n' % (name, f.hex()))
    print('wrote %d frames -> %s' % (len(cap), OUT))


if __name__ == '__main__':
    main()
