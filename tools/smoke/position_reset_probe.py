# -*- coding: utf-8 -*-
"""空中出招位置重置探针：复刻真机 CS_jh_jysg01 空中三连招 + msg15 移动上报，
收全帧并高亮一切可能移动玩家的回帧（0x1F/0x20/msg337/玩家 0x10 位置类属性）。

用法: python -I position_reset_probe.py [host] [port]   (默认 127.0.0.1:19061)
对照基线 = F 盘参考服抓包 127-0-0-1-50768：
  出招回帧仅 [0x11 CD 行 + 0x10 n=188 + 337(原样回显空中坐标) + 20 + qinggong n=4
  + 清技 n=6 + State=stand n=1]，全程零 0x1F/0x20/位置属性帧。
"""
import base64
import importlib.util
import os
import socket
import struct
import sys
import time

_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames
create_role_packet = _SC.create_role_packet
activity_custom = _SC.activity_custom
ci = _SC.ci
narrow = _SC.narrow
op = _SC.op

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import capture_deep as cd  # noqa: E402  (parse_tvarlist)

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061
ROLE_NAME = '位置探针侠'

# 建角默认出生点（school09_YiHuaGong），空中高度 = 47.98 + 空中偏移
SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_O = 340.16, 47.98, 657.28, 2.588

table = {}


def f32(v):
    return b"\x04" + struct.pack("<f", v)


def cast_packet(skill, x, y, z):
    # 真机 8 浮点 + 空串形态：[skill, x, y, z, 0, 0, 0, 0, ""]
    return activity_custom([ci(211), narrow(skill),
                            f32(x), f32(y), f32(z),
                            f32(0.0), f32(0.0), f32(0.0), f32(0.0), narrow("")])


# 官方 msg15 整帧模板（抓包 127-0-0-1-50768 #40，105B，count=9 完整形态）：
# payload 内 f32 x@39 y@44 z@49（帧绝对偏移），其余（含尾 text/尾 int3）逐字节保留。
MOVE15_TEMPLATE = None  # 由 load_move15_template() 填充


def load_move15_template():
    global MOVE15_TEMPLATE
    import capture_deep as _cd
    path = r'F:/Game/Online/9yin/9yin_server1/captures/local/127-0-0-1-50768_c2s.bin'
    for f in _cd.load(path):
        if op(f) != 0x0A:
            continue
        vals = _cd.parse_tvarlist(f[21:])
        if vals and vals[0] == 15:
            MOVE15_TEMPLATE = bytearray(f)
            return
    raise SystemExit('official msg15 template not found')


def move15(state, x, y, z):
    # 官方帧打点：仅改 state/u32@33 与 x/y/z 三个 f32
    b = bytearray(MOVE15_TEMPLATE)
    b[21 + 2 + 5 + 5] = state & 0xFF
    b[21 + 2 + 5 + 5 + 1] = (state >> 8) & 0xFF
    struct.pack_into('<f', b, 39, x)
    struct.pack_into('<f', b, 44, y)
    struct.pack_into('<f', b, 49, z)
    return bytes(b)


def build_table(frames):
    for f in frames:
        if op(f) == 0x09 and len(f) > 100:
            i = 1
            count = struct.unpack_from('<H', f, i)[0]
            i += 2
            for idx in range(count):
                end = f.index(b'\x00', i)
                table[idx] = (f[i:end].decode('utf-8', 'replace'), f[end + 1])
                i = end + 2
            return count
    return 0


def dec_prop(f, off):
    idx = struct.unpack_from('<H', f, off)[0]
    off += 2
    name, typ = table.get(idx, ('?%d' % idx, 3))
    if typ == 1:
        val, n = f[off], 1
    elif typ == 2:
        val, n = struct.unpack_from('<H', f, off)[0], 2
    elif typ == 3:
        val, n = struct.unpack_from('<i', f, off)[0], 4
    elif typ == 4:
        val, n = struct.unpack_from('<q', f, off)[0], 8
    elif typ == 5:
        val = round(struct.unpack_from('<f', f, off)[0], 3)
        n = 4
    elif typ == 7:
        ln = struct.unpack_from('<I', f, off)[0]
        val = f[off + 4:off + 4 + ln].split(b'\x00')[0].decode('utf-8', 'replace')
        n = 4 + ln
    elif typ == 8:
        ln = struct.unpack_from('<I', f, off)[0]
        val = f[off + 4:off + 4 + ln].decode('utf-16-le', 'replace')
        n = 4 + ln
    elif typ == 9:
        val = 'obj(%08x,%d)' % struct.unpack_from('<II', f, off)
        n = 8
    else:
        val, n = '?', 0
    return off + n, '%s[%d]=%s' % (name, idx, val)


def dec10(f):
    isview = f[1]
    obj, owner = struct.unpack_from('<II', f, 2)
    cnt = struct.unpack_from('<H', f, 10)[0]
    off = 12
    props = []
    for _ in range(cnt):
        if off + 2 > len(f):
            break
        off, p = dec_prop(f, off)
        props.append(p)
    return isview, obj, owner, cnt, props


def fmt(v):
    if isinstance(v, float):
        return round(v, 3)
    if isinstance(v, str) and len(v) > 40:
        return v[:40] + '..'
    return v


def describe(f):
    o = op(f)
    if o == 0x1F:
        obj, owner = struct.unpack_from('<II', f, 1)
        x, y, z, orient = struct.unpack_from('<ffff', f, 9)
        return '!! 0x1F LOCATION obj=%08x owner=%08x pos=(%.2f,%.2f,%.2f) o=%.2f' % (
            obj, owner, x, y, z, orient)
    if o == 0x20:
        return '!! 0x20 MOVING ' + f.hex()
    if o == 0x18:
        view, objidx = struct.unpack_from('<HH', f, 1)
        cnt = struct.unpack_from('<H', f, 5)[0]
        return '?? 0x18 viewAdd view=%d objIdx=%d nprops=%d %s' % (
            view, objidx, cnt, f[7:7 + 48].hex())
    if o == 0x10:
        isview, obj, owner, cnt, props = dec10(f)
        head = '0x10 view=%d obj=%08x n=%d' % (isview, obj, cnt)
        if obj == 1:  # 玩家
            posish = [p for p in props if any(k in p for k in
                      ('Posi', 'MoveSpeed', 'RunSpeed', 'Gravity', 'Jump', 'Rush', 'Drift', 'Climb', 'Speed'))]
            body = ' | '.join(props[:6])
            if len(props) > 6:
                body += ' ...(+%d)' % (len(props) - 6)
            extra = (' || MOVEMENT-PROPS: ' + ' | '.join(posish)) if posish else ''
            return head + ': ' + body + extra
        return head + ': ' + ' | '.join(props)
    if o in (0x1E, 0x27):
        vals = cd.parse_tvarlist(f[1:])
        if vals and isinstance(vals[0], int):
            body = [fmt(v) for v in vals[1:]]
            mark = '!! ' if vals[0] in (337, 169, 15, 20) else ''
            return '%s%#04x custom msgId=%d %s' % (mark, o, vals[0], body)
        return '%#04x custom(unparsed) %s' % (o, f[:32].hex())
    if o in (0x11, 0x12, 0x13, 0x14):
        rec = struct.unpack_from('<H', f, 10)[0] if len(f) >= 12 else -1
        return '%#04x record rec=%d bytes=%d' % (o, rec, len(f))
    return '%#04x bytes=%d' % (o, len(f))


def dump(tag, frames):
    print('---- %s (%d frames) ----' % (tag, len(frames)))
    for f in frames:
        print('  ' + describe(f))


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)
    print('connected %s:%d' % (HOST, PORT))
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if frames and frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        recv_frames(sock, 1, timeout=10)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    login = recv_frames(sock, 14, timeout=15)
    sock.sendall(encode_frame(b"\x09"))
    ready = recv_frames(sock, 5, timeout=8)
    all_login = frames + login + ready
    n = build_table(all_login)
    print('property table entries: %d' % n)

    sock.sendall(encode_frame(_SC.move_packet(SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_O)))
    frames = recv_frames(sock, 30, timeout=20, quiet=2.5)
    dump('materialize window', [f for f in frames if op(f) == 0x0D][:3])

    # ===== 空中三连招（复刻真机节奏：y 一路抬高）=====
    load_move15_template()
    y0 = SPAWN_Y + 1.85
    sock.sendall(encode_frame(cast_packet('CS_jh_jysg01', SPAWN_X, y0, SPAWN_Z)))
    w1 = recv_frames(sock, 999, timeout=1.6, quiet=0.9)
    dump('cast#1 (y=%.2f) replies' % y0, w1)

    sock.sendall(encode_frame(move15(4, SPAWN_X, y0, SPAWN_Z)))
    sock.sendall(encode_frame(move15(7, SPAWN_X, y0, SPAWN_Z)))
    w2 = recv_frames(sock, 999, timeout=1.2, quiet=0.8)
    dump('moves after cast#1', w2)

    time.sleep(0.6)
    y1 = y0 + 0.6
    sock.sendall(encode_frame(cast_packet('CS_jh_jysg01', SPAWN_X, y1, SPAWN_Z)))
    w3 = recv_frames(sock, 999, timeout=1.6, quiet=0.9)
    dump('cast#2 (y=%.2f) replies' % y1, w3)

    sock.sendall(encode_frame(move15(8, SPAWN_X, y1 - 1.0, SPAWN_Z)))
    w4 = recv_frames(sock, 999, timeout=1.2, quiet=0.8)
    dump('moves after cast#2', w4)

    time.sleep(0.4)
    y2 = y1 + 3.4
    sock.sendall(encode_frame(cast_packet('CS_jh_jysg01', SPAWN_X, y2, SPAWN_Z)))
    w5 = recv_frames(sock, 999, timeout=1.6, quiet=0.9)
    dump('cast#3 (y=%.2f) replies' % y2, w5)

    sock.sendall(encode_frame(move15(4, SPAWN_X, y2, SPAWN_Z)))
    w6 = recv_frames(sock, 999, timeout=3.5, quiet=3.0)
    dump('tail (end-of-action window)', w6)

    # 汇总: 一切位置类帧
    everything = w1 + w2 + w3 + w4 + w5 + w6
    locs = [f for f in everything if op(f) in (0x1F, 0x20)]
    print('\n== SUMMARY ==')
    print('position-capable frames (0x1F/0x20): %d' % len(locs))
    for f in locs:
        print('  ' + describe(f))
    customs337 = [f for f in everything if op(f) in (0x1E, 0x27)
                  and cd.parse_tvarlist(f[1:]) and cd.parse_tvarlist(f[1:])[0] == 337]
    print('msg337 count: %d' % len(customs337))
    for f in customs337:
        print('  ' + describe(f))
    stand = [f for f in everything if op(f) == 0x10 and b'stand' in f]
    print('State=stand frames: %d' % len(stand))
    sock.close()


if __name__ == '__main__':
    main()
