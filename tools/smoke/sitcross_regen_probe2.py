# -*- coding: utf-8 -*-
"""打坐回复效果端到端探针 v2：选NPC→211 出招耗蓝→240(1) 坐→验证回蓝瘦帧+面板速度。
用法: python -I sitcross_regen_probe2.py [host] [port]
"""
import base64
import importlib.util
import os
import socket
import struct
import sys

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


def narrow(s):
    b = s.encode("utf-8")
    return b"\x06" + struct.pack("<I", len(b) + 1) + b + b"\x00"


def f32(v):
    return b"\x04" + struct.pack("<f", v)


def main():
    _spec2 = importlib.util.spec_from_file_location(
        'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
    _CD = importlib.util.module_from_spec(_spec2)
    _spec2.loader.exec_module(_CD)

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
    frames2 = recv_frames(sock, 60, timeout=25, quiet=3.0)
    _, entries, _ = _CD.parse_09(table_frame)
    names = [n for n, _ in entries]
    types = [t for _, t in entries]

    npc_oid = 0
    for f in frames + frames2:
        if op(f) == 0x0D:
            oid = struct.unpack_from('<I', f, 1)[0]
            if oid != 1:
                npc_oid = oid
                break
    print('NPC oid=0x%x' % npc_oid)

    def decode(f):
        if op(f) != 0x10 or len(f) < 12 or f[1] != 0:
            return None
        if struct.unpack_from('<I', f, 2)[0] != 1:
            return None
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
                if t == 1:
                    props[names[idx]] = raw[0]
                elif t == 2:
                    props[names[idx]] = struct.unpack('<H', raw)[0]
                elif t in (3,):
                    props[names[idx]] = struct.unpack('<i', raw)[0]
                elif t == 5:
                    props[names[idx]] = round(struct.unpack('<f', raw)[0], 2)
                elif t in (4, 6):
                    props[names[idx]] = round(struct.unpack('<d', raw)[0], 2)
        return props

    # 选目标 + 出招
    sock.sendall(encode_frame(_SC.select_packet(1, npc_oid, 1, 0)))
    recv_frames(sock, 2, timeout=3)
    sock.sendall(encode_frame(_SC.activity_custom(
        [_SC.ci(211), narrow('CS_wd_tjq01'), f32(340.16), f32(47.98), f32(657.28), f32(1.0)])))
    cf = recv_frames(sock, 8, timeout=6, quiet=1.2)
    for f in cf:
        p = decode(f)
        if p and 'MP' in p:
            print('出招后面板 MP=%s SP=%s' % (p.get('MP'), p.get('SP')))

    # 坐下
    sock.sendall(encode_frame(_SC.activity_custom([_SC.ci(240), _SC.ci(1)])))
    f2 = recv_frames(sock, 80, timeout=14, quiet=2.5)
    print('坐后 %d 帧' % len(f2))
    for f in f2:
        p = decode(f)
        if p is None:
            continue
        keys = set(p)
        if 'HPUpSpeed' in keys and 'LogicState' in keys:
            print('  坐下面板: LogicState=%s HP=%s/%s MP=%s/%s HPUpSpeed=%s MPUpSpeed=%s HPHeartSpeed=%s' % (
                p.get('LogicState'), p.get('HP'), p.get('MaxHP'), p.get('MP'), p.get('MaxMP'),
                p.get('HPUpSpeed'), p.get('MPUpSpeed'), p.get('HPHeartSpeed')))
        elif 'MPRatio' in keys and len(keys) <= 6:
            print('  回复瘦帧: %s' % {k: p[k] for k in ('HP', 'MP', 'HPRatio', 'MPRatio') if k in p})
        elif 'QingGongPoint' in keys and len(keys) <= 4:
            print('  轻功心跳: QG=%s' % p['QingGongPoint'])
    sock.close()


if __name__ == '__main__':
    main()
