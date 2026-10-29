# -*- coding: utf-8 -*-
"""参考 exe（F盘新版）全链路抓包：登录→[建角]→选角→Ready。帧存 bin+索引。"""
import socket
import struct
import sys
import time

sys.path.insert(0, 'G:/pojie/ai/9yin-server/tools/smoke')
from smoke_client import encode_frame, decode_frames, create_role_packet, op  # noqa: E402

HOST, PORT = "127.0.0.1", 19161
OUT = 'C:/Users/lzy/AppData/Local/Temp/ref_capture'


def u32(v):
    return struct.pack('<I', v)


def login_packet_v2():
    def slot(b):
        return u32(len(b)) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(bytes(32))
    w += slot(bytes(16))
    w += u32(0)
    w += slot(bytes(16))
    w += u32(0)
    return bytes(w)


def recv_frames(sock, expect_min=1, timeout=25.0, quiet=1.2):
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = time.time()
    last = time.time()
    while time.time() - start < timeout:
        try:
            data = sock.recv(65536)
            if not data:
                break
            last = time.time()
            got, rest = decode_frames(buf + data)
            frames.extend(got)
            buf = rest
        except socket.timeout:
            if len(frames) >= expect_min and time.time() - last >= quiet:
                break
        except OSError:
            break
    return frames


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)
    all_frames = []

    def step(name, pkt, expect=1, timeout=25):
        sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout)
        print(f"{name}: {len(frames)} frames")
        all_frames.extend(frames)
        return frames

    create_pkt = create_role_packet()
    choose_pkt = b"\x04" + create_pkt[1:]      # 新版选角：与建角同构 ≥84B
    ready_pkt = b"\x09" + bytes(83)            # Ready 同样要求长度

    f = step("login", login_packet_v2())
    has_role = bool(f) and len(f[0]) > 0x25 and f[0][0x25] == 1
    print("  has_role:", has_role)

    step("worldinfo", b"\x03")
    if not has_role:
        step("create", create_pkt)
    f = step("choose", choose_pkt, expect=20, timeout=30)
    if f:
        ops = [op(x) for x in f]
        print("  choose ops:", {hex(o): ops.count(o) for o in sorted(set(ops))})
    f = step("ready", ready_pkt, expect=50, timeout=40)
    if f:
        ops = [op(x) for x in f]
        print("  ready ops:", {hex(o): ops.count(o) for o in sorted(set(ops))})

    sock.close()
    with open(OUT + '.bin', 'wb') as fh:
        for i, fr in enumerate(all_frames):
            fh.write(struct.pack('<IH', len(fr), i) + fr)
    with open(OUT + '.txt', 'w', encoding='utf-8') as fh:
        for i, fr in enumerate(all_frames):
            fh.write(f"#{i} op=0x{op(fr):02X} len={len(fr)}\n")
    print(f"total {len(all_frames)} frames -> {OUT}.bin/.txt")


if __name__ == "__main__":
    main()
