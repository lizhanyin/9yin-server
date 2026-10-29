# -*- coding: utf-8 -*-
"""抓包对比脚本：login → 建角(固定外观) → 选角，全量回包 hex 落盘。

用法: python capture_chain.py <host> <port> <out.txt>
登录包满足 Go auth.ParseLoginAccountIdentity 的严格格式
(账号/密码/登录串均 16 字节对齐长度槽 + u32 clientType/loginType 尾)，
Java AccountKeys 宽松解析同样接受。外观用迁移角色「商弘」的 12 段原值，
保证两边服务器从同一输入构建 rolePara 与 0x0D。
"""
import hashlib
import os
import socket
import struct
import sys
import time

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])

APPEARANCE = [
    "book5",
    "icon\\players\\boy01_create.png",
    "333333333333333333333333333333333333333333X38\x01",
    "1",
    "obj\\char\\b_hair\\b_hair1",
    "cloth_b0001",
    "pants_b0001",
    "shoes_b0001",
    "newschool_tianya",
    "",
    "2",
    "0",
]
ROLE_NAME = "对比侠%s" % os.urandom(2).hex()


def xor(data):
    return bytes(b ^ KEY[i % 4] for i, b in enumerate(data))


def encode_frame(plain):
    out = bytearray()
    for b in xor(plain):
        out.append(b)
        if b == 0xEE:
            out.append(0x00)
    out += b"\xEE\xEE"
    return bytes(out)


def decode_frames(buf):
    frames, cur, i = [], bytearray(), 0
    while i < len(buf):
        if buf[i] == 0xEE:
            if i + 1 < len(buf) and buf[i + 1] == 0x00:
                cur.append(0xEE)
                i += 2
                continue
            if i + 1 < len(buf) and buf[i + 1] == 0xEE:
                frames.append(bytes(cur))
                cur = bytearray()
                i += 2
                continue
            raise ValueError("bad escape")
        cur.append(buf[i])
        i += 1
    return [xor(f) for f in frames], bytes(cur)


def recv_frames(sock, expect_min=1, timeout=8.0, quiet=1.0):
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = time.time()
    last_data = time.time()
    while time.time() - start < timeout:
        try:
            data = sock.recv(262144)
        except socket.timeout:
            if len(frames) >= expect_min and time.time() - last_data >= quiet:
                break
            continue
        if not data:
            break
        last_data = time.time()
        buf += data
        new_frames, buf = decode_frames(buf)
        frames.extend(new_frames)
    return frames


def u32(v):
    return struct.pack("<I", v & 0xFFFFFFFF)


def narrow(s):
    b = s.encode("gbk")
    return b"\x06" + u32(len(b) + 1) + b + b"\x00"


def login_packet():
    account = os.urandom(16)
    digest = hashlib.sha256(
        b"nineyin/login-account/v1\x00" + u32(len(account)) + account
    ).hexdigest()
    w = bytearray(b"\x02") + bytearray(20)
    w += u32(len(account)) + account
    w += u32(16) + os.urandom(16)          # password slot
    w += u32(1)                             # client type (raw value)
    w += u32(16) + os.urandom(16)           # login string slot
    w += u32(1)                             # login type (final u32)
    return bytes(w), "acct:v1:" + digest


def create_role_packet():
    w = bytearray(b"\x05") + u32(0)
    w += ROLE_NAME.encode("utf-16-le") + b"\x00\x00"
    while len(w) < 0x49:
        w.append(0)
    for s in APPEARANCE:
        w += narrow(s)
    return bytes(w)


def hexdump(b):
    lines = []
    for off in range(0, len(b), 16):
        chunk = b[off:off + 16]
        hexs = " ".join("%02x" % c for c in chunk)
        text = "".join(chr(c) if 32 <= c < 127 else "." for c in chunk)
        lines.append("  %04x  %-47s  %s" % (off, hexs, text))
    return "\n".join(lines)


def dump(out, tag, frames):
    for i, f in enumerate(frames):
        out.write("### %s # %d op=0x%02X len=%d\n" % (tag, i, f[0] & 0xFF, len(f)))
        out.write(hexdump(f) + "\n")
    out.flush()


def main():
    host, port, path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    sock = socket.create_connection((host, port), timeout=5)
    pkt, acct = login_packet()
    print("account=%s" % acct)
    with open(path, "w", encoding="utf-8") as out:
        out.write("# capture host=%s port=%d account=%s\n" % (host, port, acct))
        for tag, packet, expect in (
            ("login", pkt, 1),
            ("worldinfo", b"\x03", 1),
            ("create", create_role_packet(), 1),
            ("choose", b"\x04" + bytearray(83), 14),
        ):
            sock.sendall(encode_frame(packet))
            frames = recv_frames(sock, expect, timeout=9 if tag == "choose" else 6)
            ops = ["0x%02X" % (f[0] & 0xFF) for f in frames]
            print("%s -> %d frames: %s" % (tag, len(frames), ops[:20]))
            dump(out, tag, frames)
            time.sleep(0.2)
    sock.close()


if __name__ == "__main__":
    main()
