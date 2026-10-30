# -*- coding: utf-8 -*-
"""邮件系统实机探针：GM send_test_mail 造真实邮件 → 抓 RecvLetterRec 行格式与
350/353/355/356/357/359/361/365 各 op 的 S2C 响应帧。

前置：Go 参考服已运行（-listen 19061 / -gm-listen 19062）。
输出：mail_probe_frames.txt（step + 帧十六进制，离线分析用）。
"""
import json
import os
import socket
import struct
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (  # noqa: E402
    activity_custom, ci, create_role_packet, decode_frames, direct_custom,
    encode_frame, narrow, op, recv_frames,
)

GAME_HOST, GAME_PORT = "127.0.0.1", 19061
GM_BASE = "http://127.0.0.1:19062"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mail_probe_frames.txt")


def login_packet(blob32):
    def slot(b):
        return struct.pack('<I', len(b) & 0xFFFFFFFF) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(blob32) + slot(bytes(16)) + struct.pack('<I', 0) + slot(bytes(16)) + struct.pack('<I', 0)
    return bytes(w)


class Cap:
    def __init__(self):
        self.frames = []  # (step, frame)

    def add(self, step, frames):
        for f in frames:
            self.frames.append((step, f))

    def dump(self):
        with open(OUT, 'w', encoding='utf-8') as fh:
            for step, f in self.frames:
                fh.write('%s %s\n' % (step, f.hex()))
        print('wrote %d frames -> %s' % (len(self.frames), OUT))


def gm_sessions():
    with urllib.request.urlopen(GM_BASE + '/api/sessions', timeout=5) as r:
        return json.loads(r.read().decode('utf-8'))


def gm_command(session_id, payload):
    req = urllib.request.Request(
        GM_BASE + '/api/sessions/%s/command' % session_id,
        data=json.dumps(payload).encode('utf-8'),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read().decode('utf-8'))


def record_rows(frames, rec_idx):
    """抽出 0x11 指定记录表的原始行（供粗看/抽 serial）。"""
    out = []
    for f in frames:
        if op(f) == 0x11 and len(f) >= 16:
            if struct.unpack('<H', f[10:12])[0] == rec_idx:
                out.append(f)
    return out


def scan_serials(blob):
    """从行字节里扫 'sys-' 前缀串。"""
    serials = []
    i = blob.find(b'sys-')
    while i >= 0:
        j = i
        while j < len(blob) and blob[j] not in (0, 0x3B):
            j += 1
        s = blob[i:j].decode('ascii', errors='replace')
        if s not in serials:
            serials.append(s)
        i = blob.find(b'sys-', j)
    return serials


def main():
    blob = os.urandom(16) + bytes(16)
    sock = socket.create_connection((GAME_HOST, GAME_PORT), timeout=5)
    cap = Cap()

    def step(name, pkt, expect=1, timeout=15, quiet=1.2):
        if pkt:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        cap.add(name, frames)
        ops = {}
        for f in frames:
            ops[hex(op(f))] = ops.get(hex(op(f)), 0) + 1
        print('[%-12s] %3d frames %s' % (name, len(frames), dict(sorted(ops.items()))))
        return frames

    step('login', login_packet(blob))
    step('world', b"\x03")
    step('create', create_role_packet(name='邮探%d' % (int(time.time()) % 100000), book='book1'), timeout=25)
    step('choose', b"\x04" + create_role_packet(name='邮探%d' % (int(time.time()) % 100000), book='book1')[1:],
         expect=20, timeout=30, quiet=2.0)
    step('ready', b"\x09", expect=50, timeout=60, quiet=2.5)

    sess = gm_sessions()
    if not sess:
        print('!! GM 面板无在线会话，终止')
        return
    sid = sess[0]['sessionId']
    print('gm session:', sid)
    r = gm_command(sid, {'action': 'send_test_mail', 'itemConfig': 'yaopin_001', 'amount': 2})
    print('send_test_mail ->', r)
    gm_frames = step('gm-mail', None, expect=1, timeout=5, quiet=1.5)

    # 350 打开/拉列表
    frames = step('m350', direct_custom(0x1E, [ci(350)]), timeout=6, quiet=1.5)
    rows = record_rows(frames + gm_frames, 17)
    print('RecvLetterRec rows:', len(rows))
    serials = []
    for f in rows:
        serials += scan_serials(f)
    print('serials:', serials[:4])
    serial = serials[0] if serials else 'sys-unknown'

    # 357 selection（int 字段；先试探单 int）
    step('m357', direct_custom(0x1E, [ci(357), ci(0)]), timeout=5, quiet=1.2)
    # 353 已读
    step('m353', direct_custom(0x1E, [ci(353), narrow(serial)]), timeout=5, quiet=1.2)
    # 355 领附件
    step('m355', direct_custom(0x1E, [ci(355), narrow(serial)]), timeout=6, quiet=1.5)
    # 359 领附件（第二编号）
    step('m359', direct_custom(0x1E, [ci(359), narrow(serial)]), timeout=5, quiet=1.2)
    # 356 删除
    step('m356', direct_custom(0x1E, [ci(356), narrow(serial)]), timeout=5, quiet=1.2)
    # 361 删除（第二编号）
    step('m361', direct_custom(0x1E, [ci(361), narrow(serial)]), timeout=5, quiet=1.2)
    # 365 刷新
    step('m365', direct_custom(0x1E, [ci(365)]), timeout=5, quiet=1.2)

    sock.close()
    cap.dump()


if __name__ == '__main__':
    main()
