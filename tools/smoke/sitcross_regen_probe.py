# -*- coding: utf-8 -*-
"""打坐回复效果探针：216 花轻功→240(1) 坐下→收 12s，验证轻功心跳回复与瘦帧。
用法: python -I sitcross_regen_probe.py [host] [port]
"""
import base64
import importlib.util
import os
import socket
import struct
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames
op = _SC.op

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

SZ = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8, 9: 8}


def cs(s):
    b = s.encode("utf-8")
    return b"\x06" + struct.pack("<I", len(b) + 1) + b + b"\x00"


def main():
    sock = socket.create_connection((HOST, PORT), timeout=8)
    sock.sendall(encode_frame(LOGIN_PLAIN))
    recv_frames(sock, 1, timeout=10)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    frames = recv_frames(sock, 14, timeout=15)
    table_frame = next((f for f in frames if op(f) == 0x09), None)
    sock.sendall(encode_frame(b"\x09"))
    recv_frames(sock, 5, timeout=8)
    sock.sendall(encode_frame(_SC.move_packet(340.16, 47.98, 657.28, 2.588)))
    recv_frames(sock, 30, timeout=25, quiet=3.0)
    _spec2 = importlib.util.spec_from_file_location(
        'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
    _CD = importlib.util.module_from_spec(_spec2)
    _spec2.loader.exec_module(_CD)
    _, entries, _ = _CD.parse_09(table_frame)
    names = [n for n, _ in entries]

    def decode_player_props(f):
        """返回玩家 0x10 帧属性 dict（obj=1）。"""
        if op(f) != 0x10 or len(f) < 12 or f[1] != 0:
            return None
        if struct.unpack_from('<I', f, 2)[0] != 1:
            return None
        types = [t for _, t in entries]
        cnt = struct.unpack_from('<H', f, 10)[0]
        off = 12
        props = {}
        for _ in range(cnt):
            idx = struct.unpack_from('<H', f, off)[0]
            off += 2
            t = types[idx]
            if t == 7:
                ln = struct.unpack_from('<I', f, off)[0]
                off += 4
                props[names[idx]] = f[off:off + ln].rstrip(b'\x00')
                off += ln
            elif t == 8:
                ln = struct.unpack_from('<I', f, off)[0]
                off += 4 + ln
            else:
                sz = SZ.get(t, 0)
                raw = f[off:off + sz]
                off += sz
                props[names[idx]] = {'i': struct.unpack('<i', raw[:4])[0] if len(raw) >= 4 else raw.hex()}
        return props

    print('== 216 雁行功 begin（花 13 点） ==')
    sock.sendall(encode_frame(_SC.activity_custom(
        [_SC.ci(216), cs('qinggong_7'), _SC.ci(1)])))
    f1 = recv_frames(sock, 10, timeout=4, quiet=1.2)
    for f in f1:
        p = decode_player_props(f)
        if p and 'QingGongPoint' in p:
            print('  轻功点 -> %s' % p['QingGongPoint'])
        elif op(f) == 0x1E:
            vals = _CD.parse_tvarlist(f[1:])
            if vals:
                print('  custom msgId=%s' % vals[0])

    print('== 240(1) 坐下 ==')
    sock.sendall(encode_frame(_SC.activity_custom([_SC.ci(240), _SC.ci(1)])))
    f2 = recv_frames(sock, 60, timeout=14, quiet=2.0)
    qg_seen = []
    lean_seen = []
    storm = 0
    for f in f2:
        p = decode_player_props(f)
        if p is not None:
            keys = set(p.keys())
            if 'QingGongPoint' in keys and len(keys) <= 4:
                qg_seen.append(p['QingGongPoint'])
            if 'HPRatio' in keys and 'MPRatio' in keys and len(keys) <= 6:
                lean_seen.append(p)
    print('  收帧 %d；轻功心跳序列=%s' % (len(f2), qg_seen))
    print('  回复瘦帧(HP/MP)=%d 条' % len(lean_seen))
    for p in lean_seen[:6]:
        print('   ', p)
    sock.close()


if __name__ == '__main__':
    main()
