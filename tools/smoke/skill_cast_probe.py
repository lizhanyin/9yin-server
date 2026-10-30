# -*- coding: utf-8 -*-
"""211 USE_SKILL 注入探针：绕过真实客户端直接向 Java 服务端发出招请求，
验证服务端出招链路（accept/reject + 回帧），用于隔离客户端侧问题。

用法: python -I skill_cast_probe.py CS_wd_tjq07 [CS_wd_tjq08 ...]
前置: 服务端已启动(19061)，冒烟侠角色存在（无则自动建）。
"""
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (encode_frame, decode_frames, login_packet, create_role_packet,
                          activity_custom, ci, narrow, op)

HOST, PORT = "127.0.0.1", 19061
ROLE_NAME = '冒烟侠'


def recv_frames(sock, expect_min=1, timeout=25.0, quiet=1.5):
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
    skills = sys.argv[1:] or ["CS_wd_tjq07", "CS_wd_tjq08"]
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    print("login reply op=%s rolecount=%s" % (
        hex(op(frames[0])) if frames else None,
        frames[0][0x25] if frames else None))
    if frames and frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        frames = recv_frames(sock, 1)
        print("create role -> count=%s" % (frames[0][0x25] if frames else None))
    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 5, timeout=20)
    print("choose frames=%d has0x0B=%s" % (len(frames), any(op(f) == 0x0B for f in frames)))
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 50, timeout=30, quiet=2.0)
    print("ready frames=%d" % len(frames))

    for skill in skills:
        print("---- cast %s ----" % skill)
        sock.sendall(encode_frame(activity_custom([ci(211), narrow(skill)])))
        frames = recv_frames(sock, 3, timeout=6, quiet=1.0)
        print("resp frames=%d ops=%s" % (len(frames), [hex(op(f)) for f in frames[:8]]))
        time.sleep(1.5)

    sock.close()


if __name__ == "__main__":
    main()
