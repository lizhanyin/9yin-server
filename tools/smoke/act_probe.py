# -*- coding: utf-8 -*-
"""部署服演武链探针：合法登录→建角→进场景→1006 演武循环→解码线帧。

用法: python -I act_probe.py [port]  (默认 19061)
登录包: 0x02 | 24B 头 | u32 len + 账号槽 | u32 len + 密钥槽（LoginIdentity.parse 兼容）
"""
import socket
import struct
import sys
import time

sys.path.insert(0, r'G:\pojie\ai\9yin-server\tools\smoke')

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


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
    frames, i, start = [], 0, 0
    while i < len(buf):
        if buf[i] == 0xEE:
            if i + 1 >= len(buf):
                break
            if buf[i + 1] == 0x00:
                i += 2
                continue
            if buf[i + 1] == 0xEE:
                body = bytearray()
                j = start
                while j < i:
                    if buf[j] == 0xEE:
                        body.append(0xEE)
                        j += 2
                    else:
                        body.append(buf[j])
                        j += 1
                frames.append(xor(bytes(body)))
                i += 2
                start = i
                continue
            raise ValueError('bad escape')
        i += 1
    return frames, bytes(buf[start:])


def recv_frames(sock, timeout=4.0, quiet=0.7):
    sock.settimeout(quiet)
    buf, frames = b'', []
    t0, last = time.time(), time.time()
    while time.time() - t0 < timeout:
        try:
            data = sock.recv(262144)
        except socket.timeout:
            if frames and time.time() - last > quiet:
                break
            continue
        if not data:
            break
        last = time.time()
        buf += data
        got, buf = decode_frames(buf)
        frames += got
    return frames


def ci(v):
    return b'\x02' + struct.pack('<i', v)


def cf(v):
    return b'\x04' + struct.pack('<f', v)


def activity(msg_values):
    # 0x0A 信封：21B 头（含 opcode）+ u16 个数 + 值（msgId 为第一个值）
    w = bytearray(b'\x0A') + bytearray(20) + struct.pack('<H', len(msg_values))
    for v in msg_values:
        w += v
    return bytes(w)


def login_packet(account=b'probeactAAAAAAAA', secret=b'probesecretAAAA1',
                 login_string=b'probeloginstrAA1'):
    # 25B 头（byte0=0x02，账号槽长度 u32@0x15）+ 16B 对齐的账号/密钥/登录串槽
    # + clientType + loginType——LoginIdentity.parse 全量校验（槽长必须是 16 的倍数）。
    assert len(account) % 16 == 0 and len(secret) % 16 == 0 and len(login_string) % 16 == 0
    w = bytearray(b'') + bytearray(20)
    w += struct.pack('<I', len(account)) + account
    w += struct.pack('<I', len(secret)) + secret
    w += struct.pack('<I', 1)  # clientType
    w += struct.pack('<I', len(login_string)) + login_string
    w += struct.pack('<I', 1)  # loginType
    return bytes(w)

def create_role_packet(name='探针侠', book='book1'):
    w = bytearray(b'\x05') + struct.pack('<I', 0)
    w += name.encode('utf-16-le') + b'\x00\x00'
    while len(w) < 0x49:
        w.append(0)
    w += b'\x06' + struct.pack('<I', len(book) + 1) + book.encode('gbk') + b'\x00'
    return bytes(w)


TABLE = (r'G:\pojie\ai\9yin-server\nineyin\src\main\resources'
         r'\nineyin\protocol\official_property_table.txt')
INTEREST = {463: 'LogicState', 758: 'FacultyState', 759: 'FacultyStyle',
            760: 'FacultyName', 773: 'FillSpeed', 774: 'NorFacultyAdd',
            775: 'TeamFacultyState', 777: 'TeamFacultyValue', 778: 'TeamFacultyLast',
            790: 'ActDayValue', 791: 'ActCostSuiYin', 795: 'ActDayValueLast',
            796: 'ActDayValueUsed', 797: 'ActDayValueMax', 798: 'Faculty'}


def load_table():
    entries = []
    for line in open(TABLE, encoding='ascii'):
        p = line.strip().split('\t')
        if len(p) == 3:
            entries.append((int(p[0]), p[1], int(p[2])))
    return {i: t for i, n, t in entries}, {i: n for i, n, t in entries}


def fmt10(f, ty, names):
    n = struct.unpack_from('<H', f, 10)[0]
    off, parts = 12, []
    sizes = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 9: 8}
    for _ in range(n):
        if off + 2 > len(f):
            break
        idx = struct.unpack_from('<H', f, off)[0]
        off += 2
        t = ty.get(idx, 3)
        if t in (7, 8):
            ln = struct.unpack_from('<I', f, off)[0]
            off += 4 + ln
            v = '(str)'
        else:
            sz = sizes.get(t, 4)
            v = f[off] if sz == 1 else int.from_bytes(f[off:off + sz], 'little', signed=True)
            off += sz
        if idx in INTEREST:
            parts.append('%s=%s' % (names.get(idx, idx), v))
    return 'n=%d %s' % (n, ' '.join(parts))


def fmt1e(f):
    if f[0] != 0x1E or len(f) < 8:
        return ''
    cnt = struct.unpack_from('<H', f, 1)[0]
    vals, off = [], 3
    for _ in range(cnt):
        if off >= len(f):
            break
        tag = f[off]
        off += 1
        if tag == 2:
            vals.append(struct.unpack_from('<i', f, off)[0])
            off += 4
        elif tag == 4:
            vals.append(round(struct.unpack_from('<f', f, off)[0], 1))
            off += 4
        else:
            break
    return 'custom %s' % vals


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 19061
    ty, names = load_table()
    sock = socket.create_connection(('127.0.0.1', port), timeout=5)

    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 3)
    f0 = frames[0] if frames else b''
    roles = f0[0x25] if len(f0) > 0x25 else -1
    print('login: op=%s roles=%s' % (hex(f0[0]) if f0 else 'none', roles))
    sock.sendall(encode_frame(b'\x03'))
    recv_frames(sock, 2)
    if roles == 0:
        sock.sendall(encode_frame(create_role_packet()))
        frames = recv_frames(sock, 3)
        print('create: %s' % ([hex(f[0]) for f in frames],))
    sock.sendall(encode_frame(b'\x04'))
    frames = recv_frames(sock, 8)
    print('choose: %s' % [hex(f[0]) for f in frames][:14])
    sock.sendall(encode_frame(b'\x09'))
    frames = recv_frames(sock, 10)
    print('ready: %d frames' % len(frames))

    def step(label, payload, wait=2.5):
        sock.sendall(encode_frame(payload))
        got = recv_frames(sock, wait)
        print('--- %s ---' % label)
        for f in got:
            op = f[0]
            if op == 0x10:
                print('   0x10 len=%d %s' % (len(f), fmt10(f, ty, names)))
            elif op in (0x1E, 0x27):
                print('   %s' % fmt1e(f))
            elif op == 0x18:
                print('   0x18 view=%d len=%d' % (struct.unpack_from('<H', f, 1)[0], len(f)))
            else:
                print('   op=0x%02X len=%d' % (op, len(f)))
        return got

    step('1006/24 表单刷新', activity([ci(24)]))
    step('1006/21 演武开始', activity([ci(21)]))
    step('1006/23 打卡(card0 碎银)', activity([ci(23), cf(0), cf(1)]))
    step('1006/23 打卡(card1 碎银)', activity([ci(23), cf(1), cf(1)]))
    step('1006/22 退出', activity([ci(22), cf(0)]))
    sock.close()


if __name__ == '__main__':
    main()
