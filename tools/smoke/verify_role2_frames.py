# -*- coding: utf-8 -*-
"""角色2新库存格式端到端验证（View1纸娃娃 + 0x19版本帧 + 17视图 + 全量技能）。"""
import socket
import struct
import sys
import time

sys.path.insert(0, 'G:/pojie/ai/9yin-server/tools/smoke')
from smoke_client import encode_frame, decode_frames, login_packet, op  # noqa: E402

HOST, PORT = "127.0.0.1", 19061
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


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
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    check("login role=1", frames and op(frames[0]) == 0x04 and frames[0][0x25] == 1)
    sock.sendall(encode_frame(b"\x03"))
    recv_frames(sock, 1)
    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 20, timeout=30)
    ops = [op(f) for f in frames]
    entry_idx = ops.index(0x0B)

    pre = ops[:entry_idx]
    check("17 view creates (0x15) before entry", pre.count(0x15) == 17,
          "got %d" % pre.count(0x15))
    itembags = [f for f, o in zip(frames[:entry_idx], pre) if o == 0x18 and b"ITEMBAG_006" in f]
    check("4 ITEMBAG_006 objects", len(itembags) == 4, "got %d" % len(itembags))

    post = frames[entry_idx:]
    dolls = [f for f in post if f[0] == 0x18 and len(f) > 3
             and struct.unpack("<H", f[1:3])[0] == 1]
    check("View 1 doll objects == 11", len(dolls) == 11, "got %d" % len(dolls))
    check("doll slot1 festival helmet", any(
        struct.unpack("<H", f[3:5])[0] == 1 and b"festival_helmet_b02" in f for f in dolls))
    check("doll weapon @slot22", any(
        struct.unpack("<H", f[3:5])[0] == 22 and b"blade_battlefield_40201_new" in f
        for f in dolls))
    check("doll carries Photo icon path", any(b"icon\\equip\\" in f for f in dolls))
    check("doll carries UniqueID (1891=0x765)", any(
        b"\x63\x07" in f for f in dolls))

    v19 = [f for f in post if f[0] == 0x19]
    check("0x19 version frames == 138 (69 items x2)", len(v19) == 138,
          "got %d" % len(v19))
    bag_adds = [f for f in post if f[0] == 0x18
                and struct.unpack("<H", f[1:3])[0] in (2, 121, 123, 125)]
    check("bag item objects == 69", len(bag_adds) == 69, "got %d" % len(bag_adds))
    check("bag has yaopin_00200", any(b"yaopin_00200" in f for f in bag_adds))

    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 300, timeout=45, quiet=2.5)
    ops = [op(f) for f in frames]
    skill40 = [f for f, o in zip(frames, ops)
               if o == 0x18 and len(f) > 3 and struct.unpack("<H", f[1:3])[0] == 40]
    check("View 40 skills == 17465", len(skill40) == 17465, "got %d" % len(skill40))
    check("first skill adv_38_01", b"adv_38_01" in skill40[0])

    sock.close()
    fails = [r for r in results if not r[1]]
    print("\n== NEW-FORMAT VERIFY %s: %d/%d ==" % ("FAILED" if fails else "OK",
                                                   len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
