# -*- coding: utf-8 -*-
"""原始字节级抓取 + 924 i64 tag 候选探测（一次会话完成项2验证与项4钉死）。"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import ci, create_role_packet, decode_frames, encode_frame, op  # noqa: E402

HOST, PORT = "127.0.0.1", 19061
RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'go_raw_wire.bin')


def u32(v):
    return struct.pack('<I', v & 0xFFFFFFFF)


def login_packet():
    def slot(b):
        return u32(len(b)) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(b'sim-login-client\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0')
    w += slot(bytes(16)) + u32(0) + slot(bytes(16)) + u32(0)
    return bytes(w)


def recv_frames(sock, expect_min=1, timeout=25.0, quiet=1.5, raw_out=None):
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = last = time.time()
    while time.time() - start < timeout:
        try:
            data = sock.recv(65536)
            if not data:
                break
            last = time.time()
            if raw_out is not None:
                raw_out.write(data)
                raw_out.flush()
            try:
                got, rest = decode_frames(buf + data)
            except ValueError as e:
                print("  !! decode error: %s; drop %d buffered bytes" % (e, len(buf)))
                buf, got, rest = b"", [], b""
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
    print("connected")

    def step(name, pkt, expect=1, timeout=30, quiet=1.5):
        if pkt:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        ops = [op(f) for f in frames]
        print("[%-8s] %3d frames  %s" % (name, len(frames),
              {hex(o): ops.count(o) for o in sorted(set(ops))}))
        return frames

    with open(RAW, 'wb') as raw_out:
        def rstep(name, pkt, expect=1, timeout=30, quiet=1.5):
            if pkt:
                sock.sendall(encode_frame(pkt))
            frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet, raw_out=raw_out)
            ops = [op(f) for f in frames]
            print("[%-8s] %3d frames  %s" % (name, len(frames),
                  {hex(o): ops.count(o) for o in sorted(set(ops))}))
            return frames

        f = rstep("login", login_packet())
        has_role = bool(f) and len(f[0]) > 0x25 and f[0][0x25] == 1
        rstep("world", b"\x03")
        create_pkt = create_role_packet()
        if not has_role:
            rstep("create", create_pkt)
        rstep("choose", b"\x04" + create_pkt[1:], expect=20, timeout=25, quiet=2.0)
        rstep("ready", b"\x09", expect=50, timeout=45, quiet=2.5)
        raw_out.flush()

        # 924 i64 tag 候选：[int32:924, <tag>:i64 tick]
        tick = int(time.time() * 1000)
        cands = {
            'tag03': b'\x03' + struct.pack('<q', tick),
            'tag04': b'\x04' + struct.pack('<q', tick),
            'tag05': b'\x05' + struct.pack('<q', tick),
            'tag08': b'\x08' + struct.pack('<q', tick),
            'tag09': b'\x09' + struct.pack('<q', tick),
            'tag02w8': b'\x02' + struct.pack('<q', tick),
        }
        for name, v in cands.items():
            w = bytearray(b"\x1E") + struct.pack("<H", 2)
            w += ci(924) + v
            rstep("924" + name, bytes(w), timeout=3, quiet=0.8)

        # 924 走 0x0A 信封 + GM(2) 走 0x1E 直发（路径归属补测）
        tick = int(time.time() * 1000)
        env = bytearray(b"\x0A") + bytearray(20) + struct.pack("<H", 2)
        env += ci(924) + b'\x03' + struct.pack('<q', tick)
        rstep("924env", bytes(env), timeout=3, quiet=0.8)
        gm = bytearray(b"\x1E") + struct.pack("<H", 2)
        gm += ci(2) + ci(1)
        rstep("gm1E", bytes(gm), timeout=3, quiet=0.8)

        # 心跳钉死：三种 ping 布局各配 2s 观察窗（对照官服抓包 12B 布局）
        tick = int(time.time()) & 0xFFFFFFFF
        rstep("ping1B", b"\x18", timeout=2, quiet=1.8)
        rstep("ping5B", b"\x18" + u32(tick), timeout=2, quiet=1.8)
        rstep("ping12B", b"\x18" + u32(tick) + b"\x02" + u32(tick) + b"\x00" * 4,
              timeout=2, quiet=1.8)
        rstep("after2s", None, timeout=2, quiet=1.8)

    sock.close()
    print("raw wire saved:", RAW)


if __name__ == "__main__":
    main()
