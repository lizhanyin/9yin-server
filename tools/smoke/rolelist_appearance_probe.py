# -*- python: utf-8 -*-
"""选角外观探针：登录→抓 0x04 选角帧→按结构解析 rolePara 的 f7/f9/f10/f11
（发型/衣/裤/鞋模型路径），用于验证选角界面外观按显示类型（装备）下发。

用法：python rolelist_appearance_probe.py [host] [port]
"""
import importlib.util
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames

import base64

# 与 probe_shortcuts 同源的新版登录包（SHA256 账号摘要域）
LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061


def u32(frame, off):
    return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8 \
        | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24


def parse_role(frame):
    off = 0x29 + 2 + 1 + 4 + 1 + 4
    off += 1                            # u8(7)
    name_len = u32(frame, off)
    off += 4
    name = frame[off:off + name_len - 2].decode("utf-16-le")
    off += name_len
    off += 1                            # u8(7)
    para_len = u32(frame, off)
    off += 4
    para = frame[off:off + para_len - 2].decode("utf-16-le")
    return name, para


def main():
    sock = _SC.socket.socket()
    sock.connect((HOST, PORT))
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = []
    buf = b""
    while not frames:
        chunk = sock.recv(65536)
        if not chunk:
            print("FAIL connection closed before 0x04")
            return 1
        buf += chunk
        frames, rest = _SC.decode_frames(buf)
    sock.close()

    role_frames = [f for f in frames if f[0] == 0x04 and len(f) > 0x29]
    if not role_frames:
        print("FAIL no role 0x04 frame (role_count=0?)")
        return 1
    name, para = parse_role(role_frames[0])
    fields = para.split(",")
    print("role_name = %s" % name)
    for idx, label in ((7, "hair"), (9, "cloth"), (10, "pants"), (11, "shoes")):
        print("f%-2d %-6s = %s" % (idx, label, fields[idx] if idx < len(fields) else "<short>"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
