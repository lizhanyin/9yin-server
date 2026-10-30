# -*- coding: utf-8 -*-
"""扩容包（BAGPACK）端到端验证：装备→DB校验→续期→addtoolbox→到期扫描→锚点清除。

前置：新 jar 已启动（role_bagpacks 表自建 + 存量种子），工具连 127.0.0.1:19061。
自带账号现场建角（冒烟侠），角色 ID 动态发现；用材料页（page=3，种子不覆盖，
行为可预测）。总时长约 2 分钟（含 60s 到期调度等待）。
"""
import socket
import struct
import subprocess
import sys
import time

sys.path.insert(0, 'G:/pojie/ai/9yin-server/tools/smoke')
from smoke_client import (encode_frame, decode_frames, login_packet, create_role_packet,
                          op, activity_custom, ci)  # noqa: E402

HOST, PORT = "127.0.0.1", 19061
MYSQL = 'G:/pojie/ai/mysql/bin/mysql.exe'
ROLE_NAME = '冒烟侠'
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def db(sql):
    return subprocess.run(
        [MYSQL, '-unineyin', '-pnineyin123', '-h127.0.0.1', '-P3306', '-D', 'nineyin',
         '--default-character-set=utf8mb4', '-N', '-e', sql],
        capture_output=True, text=True, encoding='utf-8').stdout.strip()


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


def le16(f, off):
    return struct.unpack('<H', f[off:off + 2])[0]


def main():
    # 前置：表已建（服务端启动自建）
    table = db("SELECT COUNT(*) FROM role_bagpacks")
    if table == '' or not table.isdigit():
        check("pre: role_bagpacks table (new jar started?)", False, table)
        return summary()
    check("pre: role_bagpacks table ready", True, table + " rows")

    # ---- 1) 登录（无角色则现场建角） ----
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    check("login reply 0x04", frames and op(frames[0]) == 0x04)
    if frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        frames = recv_frames(sock, 1)
        check("create role -> 0x04 count=1",
              frames and op(frames[0]) == 0x04 and frames[0][0x25] == 1)
    role = db("SELECT role_id FROM roles WHERE name='%s'" % ROLE_NAME)
    if not role.isdigit():
        check("role discovered", False, role)
        return summary()
    check("role discovered id=%s" % role, True)
    # 新角色无锚点（种子只给存量角色）；清理上次残留保证可重复
    db("DELETE FROM role_bagpacks WHERE role_id=%s AND page=3" % role)
    db("DELETE FROM role_bag_items WHERE role_id=%s AND view_id=3" % role)

    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 20, timeout=30)
    check("choose chain has 0x0B", any(op(f) == 0x0B for f in frames))
    anchors = [f for f in frames if op(f) == 0x18 and le16(f, 1) in (3, 122, 124, 126)]
    check("choose: 0 anchor objects for fresh role", len(anchors) == 0, str(len(anchors)))
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 100, timeout=40, quiet=2.5)
    check("ready ok", len(frames) > 50, str(len(frames)))

    # ---- 2) 种包 + 装备：30 [123, 1, 124, 2]（材料页→材料扩展页） ----
    db("INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, view_id) "
       "SELECT %s, COALESCE(MAX(seq),0)+1, 1, 'STUFFBAG_003_c', 31, 1, 3 "
       "FROM role_bag_items WHERE role_id=%s" % (role, role))
    # live 快照在登录时已建：重进场景（0x0C）或重登让它带上种子行 —— 直接重登最稳
    sock.close()
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    recv_frames(sock, 1)
    sock.sendall(encode_frame(b"\x04"))
    recv_frames(sock, 5, timeout=20)
    sock.sendall(encode_frame(b"\x09"))
    recv_frames(sock, 50, timeout=30, quiet=2)

    sock.sendall(encode_frame(activity_custom(
        [ci(30), ci(123), ci(1), ci(124), ci(2)])))
    frames = recv_frames(sock, 5, timeout=8)
    removes = [f for f in frames if op(f) == 0x19 and le16(f, 1) == 124]
    adds = [f for f in frames if op(f) == 0x18 and le16(f, 1) == 124]
    check("equip: anchor remove x2 on view124", len(removes) == 2, str(len(removes)))
    check("equip: anchor add on view124",
          len(adds) == 1 and b'STUFFBAG_003_c' in adds[0], str(len(adds)))
    time.sleep(1)
    row = db("SELECT anchor, boost, TIMESTAMPDIFF(HOUR, UTC_TIMESTAMP(6), expires_at) "
             "FROM role_bagpacks WHERE role_id=%s AND page=3" % role)
    check("db: bagpack anchor=1 boost=18 ~168h", row.startswith('1\t18\t'), row)
    gone = db("SELECT COUNT(*) FROM role_bag_items WHERE role_id=%s AND view_id=3 AND slot=1" % role)
    check("db: bag item consumed", gone == '0', gone)

    # ---- 3) 续期：再种一个同 config → 重登带上快照 → 再发 30 ----
    db("INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, view_id) "
       "SELECT %s, COALESCE(MAX(seq),0)+1, 1, 'STUFFBAG_003_c', 31, 1, 3 "
       "FROM role_bag_items WHERE role_id=%s" % (role, role))
    sock.close()
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    recv_frames(sock, 1)
    sock.sendall(encode_frame(b""))
    recv_frames(sock, 5, timeout=20)
    sock.sendall(encode_frame(b"	"))
    recv_frames(sock, 50, timeout=30, quiet=2)
    sock.sendall(encode_frame(activity_custom(
        [ci(30), ci(123), ci(1), ci(124), ci(2)])))
    recv_frames(sock, 3, timeout=8)
    time.sleep(1)
    row2 = db("SELECT anchor, TIMESTAMPDIFF(HOUR, UTC_TIMESTAMP(6), expires_at) "
              "FROM role_bagpacks WHERE role_id=%s AND page=3" % role)
    check("renew: same anchor=1, ~336h (168x2)", row2.startswith('1\t3'), row2)

    # ---- 4) addtoolbox：普通材料入扩展格 20（容量 18+18=36） ----
    db("INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, view_id) "
       "SELECT %s, COALESCE(MAX(seq),0)+1, 2, 'ronghe_10001', 3, 1, 3 "
       "FROM role_bag_items WHERE role_id=%s" % (role, role))
    sock.close()
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    recv_frames(sock, 1)
    sock.sendall(encode_frame(b"\x04"))
    recv_frames(sock, 5, timeout=20)
    sock.sendall(encode_frame(b"\x09"))
    recv_frames(sock, 50, timeout=30, quiet=2)
    sock.sendall(encode_frame(activity_custom(
        [ci(30), ci(123), ci(2), ci(124), ci(20)])))
    frames = recv_frames(sock, 3, timeout=8)
    check("addtoolbox: frames back", len(frames) >= 2, str(len(frames)))
    time.sleep(1)
    moved = db("SELECT slot, view_id FROM role_bag_items WHERE role_id=%s "
               "AND config_id='ronghe_10001'" % role)
    check("addtoolbox: material at slot 20 view cat 3", moved.startswith('20\t3'), moved)

    # ---- 5) 到期扫描：伪造过期 → 等 60s 调度 → 锚点清除、物品原地保留 ----
    db("UPDATE role_bagpacks SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE) "
       "WHERE role_id=%s AND page=3" % role)
    print("  等待 60s 到期扫描调度器 ...")
    frames = recv_frames(sock, 2, timeout=75, quiet=2)
    expired_removes = [f for f in frames if op(f) == 0x19 and le16(f, 1) == 124]
    check("expire: scheduler pushed anchor removes on view124",
          len(expired_removes) >= 1, str(len(expired_removes)))
    time.sleep(1)
    left = db("SELECT COUNT(*) FROM role_bagpacks WHERE role_id=%s AND page=3" % role)
    check("db: expired bagpack row deleted", left == '0', left)
    kept = db("SELECT COUNT(*) FROM role_bag_items WHERE role_id=%s "
              "AND config_id='ronghe_10001' AND slot=20" % role)
    check("db: extension item kept in place (原地保留)", kept == '1', kept)

    sock.close()
    # 清理（保留冒烟侠角色供复跑）
    db("DELETE FROM role_bag_items WHERE role_id=%s AND view_id=3" % role)
    summary()


def summary():
    fails = [r for r in results if not r[1]]
    print("\n== BAGPACK VERIFY %s: %d/%d ==" % ("FAILED" if fails else "OK",
                                                len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
