"""巡逻 0x21 ServerAllDest 真机验证探针。

登录 → 选角 → ready → activity 物化 → 录 20 秒下行,统计 0x21 批量广播:
帧数 / 记录数 / 速度分布 / 移动标志分布。官方节拍 = 每秒一拍。
用法: python patrol_probe.py [port]
"""
import socket
import struct
import sys
import time
import collections

sys.path.insert(0, '.')
from act_probe import (  # noqa: E402
    encode_frame, login_packet, create_role_packet, recv_frames,
    activity, ci)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 19061
    # 独立随机账号 → 新角色出生 born03(253 walkers),保证视口内有巡逻 NPC。
    import random
    suffix = random.randint(0, 10 ** 8)
    account = ('probeptl%010d' % suffix)[:16].encode()
    secret = ('ptlsecret%010d' % suffix)[:16].encode()
    sock = socket.create_connection(('127.0.0.1', port), timeout=5)
    sock.sendall(encode_frame(login_packet(account, secret)))
    frames = recv_frames(sock, 3)
    f0 = frames[0] if frames else b''
    roles = f0[0x25] if len(f0) > 0x25 else -1
    print('login: roles=%s account=%s' % (roles, account.decode()))
    sock.sendall(encode_frame(b'\x03'))
    recv_frames(sock, 2)
    if roles == 0:
        sock.sendall(encode_frame(create_role_packet(
            name='巡探%06d' % (suffix % 1000000))))
        recv_frames(sock, 3)
        print('created')
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    recv_frames(sock, 8)
    print('chosen')
    sock.sendall(encode_frame(b'\x09'))
    recv_frames(sock, 8)
    print('ready')

    # 物化(0x0A 活动信封)后巡逻首拍 5s + 每秒一拍;录 20s。
    sock.sendall(encode_frame(activity([ci(15)])))
    deadline = time.time() + 20
    buf = b''
    recs = []
    while time.time() < deadline:
        try:
            sock.settimeout(max(0.5, deadline - time.time()))
            chunk = sock.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
    # 切帧:EE EE 边界,沿用 act_probe.decode_frames 的密钥流逻辑(返回 (frames, rest))
    from act_probe import decode_frames
    frames = [f for f in decode_frames(buf)[0] if f]
    ops = collections.Counter(f[0] for f in frames)
    print('20s frames=%d ops=%s' % (len(frames), {hex(k): v for k, v in sorted(ops.items())}))
    for f in frames:
        if f[0] != 0x21 or len(f) < 3:
            continue
        n = struct.unpack_from('<H', f, 1)[0]
        for i in range(n):
            off = 3 + i * 44
            if off + 44 > len(f):
                continue
            oid, owner = struct.unpack_from('<II', f, off)
            vals = struct.unpack_from('<8f', f, off + 8)
            flags = struct.unpack_from('<I', f, off + 40)[0]
            recs.append((oid, vals[0], vals[2], vals[3], vals[4], flags))
    print('0x21 records=%d distinct_npc=%d' % (len(recs), len(set(r[0] for r in recs))))
    speeds = collections.Counter(round(r[4], 2) for r in recs)
    flags = collections.Counter(r[5] for r in recs)
    print('speed dist:', dict(speeds.most_common(8)))
    print('flag dist:', dict(flags))
    for r in recs[:8]:
        print('  id=%d dest=(%.1f, %.1f) orient=%.2f spd=%.2f flags=%d'
              % (r[0], r[1], r[2], r[3], r[4], r[5]))
    sock.close()


if __name__ == '__main__':
    main()
