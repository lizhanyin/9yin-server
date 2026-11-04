# -*- coding: utf-8 -*-
"""打坐线上探针：登录→选角→ready→发 240(1)→收 6s→发 240(2)→收 3s，
dump 全部回帧（opcode/len + 玩家 0x10 属性名表），用于与参考服 52905 #5451 对拍。

用法: python -I sitcross_wire_probe.py [host] [port]
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
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
ROLE_NAME = '打坐探针侠'

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

SZ = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8, 9: 8}


def parse_profile(buf, off, names, types, limit):
    props = {}
    order = []
    for _ in range(limit):
        if off + 2 > len(buf):
            return None, None
        idx = struct.unpack_from('<H', buf, off)[0]
        off += 2
        if idx >= len(names):
            return None, None
        t = types[idx]
        key = names[idx]
        order.append(key)
        if t == 7:
            ln = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if ln < 1 or off + ln > len(buf):
                return None, None
            props[key] = buf[off:off + ln].rstrip(b'\x00').decode('gbk', 'replace')
            off += ln
        elif t == 8:
            ln = struct.unpack_from('<I', buf, off)[0]
            off += 4
            if ln < 2 or off + ln > len(buf):
                return None, None
            props[key] = buf[off:off + ln - 2].decode('utf-16-le', 'replace')
            off += ln
        else:
            sz = SZ.get(t)
            if sz is None or off + sz > len(buf):
                return None, None
            raw = buf[off:off + sz]
            off += sz
            if t == 1:
                props[key] = raw[0]
            elif t == 2:
                props[key] = struct.unpack('<H', raw)[0]
            elif t == 3:
                props[key] = struct.unpack('<i', raw)[0]
            elif t == 5:
                props[key] = round(struct.unpack('<f', raw)[0], 3)
            elif t in (4, 6):
                props[key] = round(struct.unpack('<d', raw)[0], 3)
            elif t == 9:
                o, w = struct.unpack('<II', raw)
                props[key] = 'obj(0x%x,0x%x)' % (o, w)
    if off != len(buf):
        return None, None
    return props, order


def custom240(v):
    return _SC.activity_custom([_SC.ci(240), _SC.ci(v)])


def dump(frames, table, tag):
    names, types = table
    objs = Counter()
    for f in frames:
        if f and op(f) == 0x10 and len(f) >= 12 and f[1] == 0:
            objs[struct.unpack_from('<I', f, 2)[0]] += 1
    player = objs.most_common(1)[0][0] if objs else None
    print('-- %s: %d 帧 (player=0x%x)' % (tag, len(frames), player or 0))
    for i, f in enumerate(frames):
        line = '  [%02d] op=0x%02X len=%d' % (i, op(f), len(f))
        if f and op(f) == 0x10 and len(f) >= 12 and f[1] == 0 and struct.unpack_from('<I', f, 2)[0] == player:
            cnt = struct.unpack_from('<H', f, 10)[0]
            props, order = parse_profile(f, 12, names, types, cnt)
            if props is None:
                line += ' PLAYER props=%d <解析失败>' % cnt
            else:
                brief = {k: props[k] for k in ('LogicState', 'HP', 'MP', 'HPUpSpeed', 'MPUpSpeed', 'QingGongPoint') if k in props}
                line += ' PLAYER props=%d %s' % (cnt, brief)
                if cnt > 100:
                    line += ' 前8序=%s' % order[:8]
        print(line)


def main():
    sock = socket.create_connection((HOST, PORT), timeout=8)
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if not frames:
        print('FAIL no login reply')
        return
    role_count = frames[0][0x25] if len(frames[0]) > 0x25 else -1
    print('login 0x04 role_count=%d' % role_count)
    if role_count == 0:
        sock.sendall(encode_frame(_SC.create_role_packet(ROLE_NAME)))
        recv_frames(sock, 1, timeout=10)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    frames = recv_frames(sock, 14, timeout=15)
    print('choose chain ops=%s' % [hex(op(f)) for f in frames[:18]])
    table_frame = next((f for f in frames if op(f) == 0x09), None)
    sock.sendall(encode_frame(b"\x09"))
    recv_frames(sock, 5, timeout=8)
    sock.sendall(encode_frame(_SC.move_packet(340.16, 47.98, 657.28, 2.588)))
    ready = recv_frames(sock, 30, timeout=25, quiet=3.0)
    if table_frame is None:
        table_frame = next((f for f in ready if op(f) == 0x09), None)
    if table_frame is None:
        print('FAIL no 0x09 table')
        return
    cnt = struct.unpack_from('<H', table_frame, 1)[0]
    entries = []
    off = 3
    for _ in range(cnt):
        end = table_frame.index(b'\x00', off)
        entries.append(table_frame[off:end].decode('gbk', 'replace'))
        off = end + 2
    names = entries
    types = [table_frame[off + 2 * i] for i in range(cnt)] if False else None
    # 类型字节紧跟名字区后（parse_09 方言）：用 capture_deep 重解析保证口径一致
    import importlib.util as _iu
    _spec2 = _iu.spec_from_file_location('capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
    _CD = _iu.module_from_spec(_spec2)
    _spec2.loader.exec_module(_CD)
    _, entries2, _2 = _CD.parse_09(table_frame)
    names = [n for n, _t in entries2]
    types = [t for _n, t in entries2]
    print('table entries=%d' % len(names))
    print('ready frames=%d' % len(ready))

    print('== 发送 240(1) ==')
    sock.sendall(encode_frame(custom240(1)))
    f1 = recv_frames(sock, 40, timeout=10, quiet=1.8)
    dump(f1, (names, types), '240(1) 后 10s 窗口')

    print('== 发送 240(2) ==')
    sock.sendall(encode_frame(custom240(2)))
    f2 = recv_frames(sock, 10, timeout=6, quiet=1.5)
    dump(f2, (names, types), '240(2) 后 6s 窗口')

    print('== 再发 240(2) ==')
    sock.sendall(encode_frame(custom240(2)))
    f3 = recv_frames(sock, 5, timeout=4, quiet=1.2)
    dump(f3, (names, types), '第二帧 240(2) 后')
    sock.close()


if __name__ == '__main__':
    main()
