# -*- coding: utf-8 -*-
"""msgId=30 移动物品端到端验证：卸下武器(slot22)→DB校验→穿回→DB校验。"""
import socket
import struct
import subprocess
import sys
import time

sys.path.insert(0, 'G:/pojie/ai/9yin-server/tools/smoke')
from smoke_client import (encode_frame, decode_frames, login_packet, op,
                          activity_custom, ci)  # noqa: E402

HOST, PORT = "127.0.0.1", 19061
MYSQL = 'G:/pojie/ai/mysql/bin/mysql.exe'
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def db(sql):
    out = subprocess.run(
        [MYSQL, '-unineyin', '-pnineyin123', '-h127.0.0.1', '-P3306', '-D', 'nineyin',
         '--default-character-set=utf8mb4', '-N', '-e', sql],
        capture_output=True, text=True, encoding='utf-8').stdout.strip()
    return out


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
    equips_before = db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=2 AND slot=22")
    check("pre: weapon in doll slot22", equips_before == '1', equips_before)

    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    check("login role=1", frames and op(frames[0]) == 0x04 and frames[0][0x25] == 1)
    sock.sendall(encode_frame(b"\x03"))
    recv_frames(sock, 1)
    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 20, timeout=30)
    check("choose chain", any(op(f) == 0x0B for f in frames))
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 300, timeout=45, quiet=2.5)
    check("ready ok", len(frames) > 100, str(len(frames)))

    # 卸下武器：30 [1, 22, 121, 0]
    sock.sendall(encode_frame(activity_custom([ci(30), ci(1), ci(22), ci(121), ci(0)])))
    frames = recv_frames(sock, 3, timeout=8)
    ops = [op(f) for f in frames]
    removes = [f for f, o in zip(frames, ops) if o == 0x19]
    adds = [f for f, o in zip(frames, ops) if o == 0x18]
    check("unequip: 0x19 removes >= 2", len(removes) >= 2, str(len(removes)))
    check("unequip: doll slot22 removed",
          any(struct.unpack('<H', f[1:3])[0] == 1 and struct.unpack('<H', f[3:5])[0] == 22
              for f in removes))
    check("unequip: bag add present", len(adds) >= 1, str(len(adds)))
    time.sleep(1)
    doll = db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=2 AND slot=22")
    bag = db("SELECT config_id FROM role_bag_items WHERE role_id=2 "
             "AND view_id=2 ORDER BY seq DESC LIMIT 1")
    check("db: doll slot22 empty", doll == '0', doll)
    check("db: weapon in bag", 'blade_battlefield' in bag, bag)

    # 穿回：30 [121, slot, 1, 22]
    bag_slot = db("SELECT slot FROM role_bag_items WHERE role_id=2 "
                  "AND config_id LIKE 'blade%' AND view_id=2 LIMIT 1")
    sock.sendall(encode_frame(activity_custom(
        [ci(30), ci(121), ci(int(bag_slot)), ci(1), ci(22)])))
    frames = recv_frames(sock, 3, timeout=8)
    ops = [op(f) for f in frames]
    check("re-equip: frames back", len(frames) >= 2, str(len(frames)))
    time.sleep(1)
    doll2 = db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=2 AND slot=22")
    bag2 = db("SELECT COUNT(*) FROM role_bag_items WHERE role_id=2 AND config_id LIKE 'blade%'")
    check("db: weapon back in doll", doll2 == '1', doll2)
    check("db: bag clean", bag2 == '0', bag2)

    sock.close()
    fails = [r for r in results if not r[1]]
    print("\n== MOVE30 VERIFY %s: %d/%d ==" % ("FAILED" if fails else "OK",
                                               len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
