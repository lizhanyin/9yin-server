# -*- coding: utf-8 -*-
"""快捷键下发探针：登录→选角→0x09 ready→统计 shortcut_rec(record=93) 行帧。

用于验证 Java 端 role_shortcuts DB 下发链：ready 后长收帧，解析 0x11
recordAddCells 帧中 record index==93 的行（三列：int index, string kind, string id）。
冒烟账号无 DB 快捷键时应看到 starter 分支（心佛掌/太极拳）；有 DB 行时应逐行下发。
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
login_packet = _SC.login_packet
create_role_packet = _SC.create_role_packet
op = _SC.op

import base64

# 与 skill_cast_verify 同源的新版登录包（SHA256 账号摘要域）
LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061
ROLE_NAME = '快捷冒烟侠'

RECORD_SHORTCUT = 93


def parse_record_row(frame):
    """解析 0x11 recordAddCells：返回 (record_index, cells) 或 (record_index, None)。

    布局（RecordMessages.recordAddCells）：
    [0]=0x11 [1]=0 [2:6]objectId [6:10]ownerId [10:12]recordIndex [12:14]=0 [14:16]=1
    列区无 tag，按官方 schema 定长：type1=u8 2=u16 3=u32 4=u64 7=u32 len + utf8 + NUL。
    列类型未知时退化为尽力解析（93 按经验 cols [3,7,7]）。
    """
    if len(frame) < 16:
        return None
    rec = struct.unpack("<H", frame[10:12])[0]
    if rec == RECORD_SHORTCUT:
        i = 16
        cells = []
        try:
            cells.append(struct.unpack("<I", frame[i:i + 4])[0])
            i += 4
            for _ in range(2):
                n = struct.unpack("<I", frame[i:i + 4])[0]
                i += 4
                raw = frame[i:i + n]
                i += n
                cells.append(raw[:-1].decode("utf-8", "replace"))
            return rec, cells
        except Exception:
            return rec, None
    return rec, None


def main():
    sock = socket_create()
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if not frames:
        print("FAIL no login reply")
        return
    role_count = frames[0][0x25] if len(frames[0]) > 0x25 else -1
    print("login reply 0x04 role_count=%d" % role_count)
    if role_count == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        recv_frames(sock, 1, timeout=10)
    # 选角（0x54 全零包，同 skill_cast_verify）
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    frames = recv_frames(sock, 14, timeout=15)
    ops_seq = [op(f) for f in frames]
    print("choose chain n=%d ops=%s" % (len(ops_seq), ops_seq[:18]))
    # ClientReady
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 5, timeout=8)
    # 移动包触发物化 + 长收 ready 后推送
    sock.sendall(encode_frame(_SC.move_packet(340.16, 47.98, 657.28, 2.588)))
    frames2 = recv_frames(sock, 30, timeout=25, quiet=3.0)
    allf = frames + frames2
    print("ready+move frames=%d" % len(allf))

    rows = []
    rec_hist = {}
    raw93 = []
    for f in allf:
        if op(f) != 0x11:
            continue
        parsed = parse_record_row(f)
        if not parsed:
            rec_hist["short"] = rec_hist.get("short", 0) + 1
            continue
        rec, cells = parsed
        rec_hist[rec] = rec_hist.get(rec, 0) + 1
        if rec == RECORD_SHORTCUT:
            rows.append(cells)
            if len(raw93) < 3:
                raw93.append(f.hex())
    print("0x11 record histogram = %s" % dict(sorted(rec_hist.items(), key=lambda kv: -kv[1])[:15]))
    for h in raw93:
        print("  raw93:", h)
    print("shortcut_rec(93) rows = %d" % len(rows))
    for c in rows[:20]:
        print("  row:", c)
    if len(rows) > 20:
        print("  ... (%d more)" % (len(rows) - 20))
    sock.close()


def socket_create():
    import socket
    return socket.create_connection((HOST, PORT), timeout=6)


if __name__ == "__main__":
    main()
