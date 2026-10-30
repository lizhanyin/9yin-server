# -*- coding: utf-8 -*-
"""商店全链探针：选中商店 NPC → 菜单 → 开店（805000000）→ msgId 70 购买 →
捕获购买后的 S2C（钉死库存刷新帧，§3.13 待办）。"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (activity_custom, ci, create_role_packet, decode_frames,  # noqa: E402
                          encode_frame, narrow, op, select_packet)

HOST, PORT = "127.0.0.1", 19061
NPC_OID = 3580          # khd_146（shop_khd_ywb，item_khd_001 pos=1 价 10000 碎银）
FUNC_SHOP = 805000000


def u32(v):
    return struct.pack('<I', v & 0xFFFFFFFF)


def login_packet():
    def slot(b):
        return u32(len(b)) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(b'sim-login-client\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0')
    w += slot(bytes(16)) + u32(0) + slot(bytes(16)) + u32(0)
    return bytes(w)


def recv_frames(sock, expect_min=0, timeout=20.0, quiet=1.5):
    sock.settimeout(quiet)
    buf, frames = b"", []
    start = last = time.time()
    while time.time() - start < timeout:
        try:
            data = sock.recv(65536)
            if not data:
                break
            last = time.time()
            try:
                got, rest = decode_frames(buf + data)
            except ValueError as e:
                print("  !! decode error: %s" % e)
                buf, got, rest = b"", [], b""
            frames.extend(got)
            buf = rest
        except socket.timeout:
            if len(frames) >= expect_min and time.time() - last >= quiet:
                break
        except OSError:
            break
    return frames


def dump(name, frames, full=False):
    from collections import Counter
    ops = [op(f) for f in frames]
    print("[%-9s] %3d 帧 %s" % (name, len(frames),
          dict(sorted(Counter(ops).items()))))
    if full:
        for i, f in enumerate(frames):
            print('    #%d op=0x%02X len=%-4d %s' % (i, op(f), len(f), f[:48].hex()))
    with open('shop_probe_frames.txt', 'a', encoding='utf-8') as fh:
        for f in frames:
            fh.write('%s %s\n' % (name, f.hex()))


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)

    def step(name, pkt=None, expect=0, timeout=20, quiet=1.2, full=False):
        if pkt is not None:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        dump(name, frames, full)
        return frames

    step("login", login_packet(), 1, timeout=15)
    step("world", b"\x03", 1, timeout=10)
    create_pkt = create_role_packet()
    step("choose", b"\x04" + create_pkt[1:], 20, timeout=25, quiet=2.0)
    step("ready", b"\x09", 50, timeout=45, quiet=2.5)

    # 1) 点击选中商店 NPC → 期待帧对话菜单
    step("click", select_packet(1, NPC_OID, 1, 0), 1, timeout=6, quiet=1.0)
    # 2) 菜单动作：开商店
    step("openshop", select_packet(2, NPC_OID, 1, FUNC_SHOP), 1, timeout=8, quiet=1.5, full=True)
    # 3) 购买 [70, shopid, page, pos, amount]（page 0 基 / pos 1 基——两次拒绝日志钉死）
    step("buy", activity_custom([ci(70), narrow("shop_khd_ywb"), ci(0), ci(1), ci(1)]),
         1, timeout=6, quiet=1.5, full=True)
    # 4) 购买后静置窗口（收余波）
    step("after", None, 0, timeout=4, quiet=3.5, full=True)

    sock.close()


if __name__ == "__main__":
    main()
