# -*- coding: utf-8 -*-
"""customizing 705 下发探针:登录→选角→ready,抓 S2C 705 的串并打印关键位。"""
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
create_role_packet = _SC.create_role_packet
op = _SC.op

import base64

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061


def custom_msgid_s2c(frame):
    if (frame[0] & 0xFF) in (0x1E, 0x27) and len(frame) >= 8 and frame[3] == 2:
        return struct.unpack("<i", frame[4:8])[0]
    return None


def parse_str_value(frame):
    """705 帧体:msgId int 后跟 tag7 宽串或 tag6 窄串(config 是 UTF-8 窄串)。"""
    i = 8
    while i < len(frame):
        tag = frame[i]
        if tag == 0x06:
            n = struct.unpack("<I", frame[i + 1:i + 5])[0]
            raw = frame[i + 5:i + 5 + n]
            if len(raw) == n:
                return raw[:-1].decode("utf-8", "replace")
            return None
        if tag == 0x07:
            n = struct.unpack("<I", frame[i + 1:i + 5])[0]
            raw = frame[i + 5:i + 5 + n]
            if len(raw) == n:
                return raw[:-2].decode("utf-16-le", "replace")
            return None
        if tag == 0x02:
            i += 5
            continue
        i += 1
    return None


def main():
    sock = socket_create()
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if not frames:
        print("FAIL no login reply")
        return
    role_count = frames[0][0x25] if len(frames[0]) > 0x25 else -1
    print("login 0x04 role_count=%d" % role_count)
    if role_count == 0:
        sock.sendall(encode_frame(create_role_packet("个性冒烟侠")))
        recv_frames(sock, 1, timeout=10)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    recv_frames(sock, 14, timeout=15)
    # ready + 一点动作时间
    sock.sendall(encode_frame(b"\x09"))
    frames1 = recv_frames(sock, 5, timeout=6)
    sock.sendall(encode_frame(_SC.move_packet(340.16, 47.98, 657.28, 2.588)))
    frames2 = recv_frames(sock, 30, timeout=12, quiet=2.0)
    allf = frames1 + frames2
    found = []
    seq = 0
    for f in allf:
        seq += 1
        mid = custom_msgid_s2c(f)
        if mid == 705:
            s = parse_str_value(f)
            found.append((seq, s))
    print("705 frames received: %d" % len(found))
    for seq, s in found:
        if s:
            print("  [%d] len=%d [85]=%d [89]=%d hex[:20]=%s" % (
                seq, len(s), ord(s[85]), ord(s[89]), s[:10].encode('utf-8').hex()))
        else:
            print("  [%d] (unparsed)" % seq)
    if not found:
        print("NO 705 pushed (no archive for this role, or push path broken)")
    # 0x10 属性帧索引 198（ShortcutKey 键位映射）
    kb = []
    for f in allf:
        if (f[0] & 0xFF) != 0x10 or len(f) < 16:
            continue
        count = struct.unpack("<H", f[10:12])[0]
        i = 12
        for _ in range(count):
            idx = struct.unpack("<H", f[i:i + 2])[0]
            if idx == 198:
                n = struct.unpack("<I", f[i + 2:i + 6])[0]
                raw = f[i + 6:i + 6 + n]
                kb.append(raw[:40])
            break
    print("ShortcutKey(198) property frames: %d" % len(kb))
    for raw in kb:
        print("  bind head:", raw)
    sock.close()


def socket_create():
    import socket
    return socket.create_connection((HOST, PORT), timeout=6)


if __name__ == "__main__":
    main()
