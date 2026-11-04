# -*- python: utf-8 -*-
"""武器外观探针：登录→选角(\\x04)→解析玩家 0x0D 归档与后续 0x10 帧的
Weapon(396)/ShotWeapon(398)/ActionSet(406)/WeaponMode(1599) 行。
验证 2026-11-04 武器模型不显示修复（引擎读 Weapon@396，非 WeaponMode）。

用法: python -I weapon_visual_probe.py [host] [port]
"""
import base64
import importlib.util
import os
import struct
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location('smoke_client',
                                               os.path.join(_HERE, 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061

ENTRIES = []
for line in open(os.path.join(_HERE, '..', '..', 'nineyin', 'src', 'main', 'resources',
                              'nineyin', 'protocol', 'official_property_table.txt')):
    p = line.strip().split('\t')
    if len(p) == 3:
        ENTRIES.append((p[1], int(p[2])))
SIZES = {1: 1, 2: 2, 3: 4, 5: 4, 4: 8}


def rows(data, off, cap=999):
    out = []
    while off + 2 <= len(data) and len(out) < cap:
        idx = struct.unpack_from('<H', data, off)[0]
        off += 2
        if idx >= len(ENTRIES):
            break
        nm, pt = ENTRIES[idx]
        if pt in SIZES:
            sz = SIZES[pt]
            if off + sz > len(data):
                break
            v = data[off] if pt == 1 else (
                struct.unpack_from('<H' if pt == 2 else '<i' if pt == 3 else '<q' if pt == 4
                                   else '<f', data, off)[0])
            off += sz
        elif pt in (7, 8):
            if off + 4 > len(data):
                break
            ln = struct.unpack_from('<I', data, off)[0]
            off += 4
            if off + ln > len(data):
                break
            v = data[off:off + ln].rstrip(b'\x00').decode('gbk' if pt == 7 else 'utf-16le',
                                                          'replace')
            off += ln
        else:
            break
        out.append((idx, nm, v))
    return out


WANT = ('Weapon', 'ShotWeapon', 'ActionSet', 'WeaponMode')


def main():
    sock = _SC.socket.create_connection((HOST, PORT), timeout=8)
    sock.sendall(_SC.encode_frame(LOGIN_PLAIN))
    frames = _SC.recv_frames(sock, 1, timeout=6)
    if not frames or frames[0][0] != 0x04:
        print('FAIL login 0x04, got', [hex(f[0]) for f in frames][:4])
        return 1
    count = frames[0][0x25]
    print('login ok, role_count=%d' % count)
    # 选角包须 0x54 全零 padding（同 probe_shortcuts/skill_cast_verify 口径）
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(_SC.encode_frame(bytes(choose)))
    frames = _SC.recv_frames(sock, 60, timeout=15, quiet=1.5)
    print('choose chain frames=%d ops=%s' % (len(frames),
          [hex(f[0]) for f in frames[:14]]))
    player_archive = []
    for f in frames:
        if f[0] == 0x0D and len(f) > 0x3F:
            obj = struct.unpack_from('<I', f, 1)[0]
            r = rows(f, 0x3F)
            hits = [(i, nm, v) for i, nm, v in r if nm in WANT]
            print('0x0D obj=%d rows=%d -> %s' % (obj, len(r),
                  [(nm, v) for i, nm, v in hits] or 'no weapon rows'))
            if obj == 1:
                player_archive = hits
        elif f[0] == 0x10 and len(f) > 12:
            obj = struct.unpack_from('<I', f, 2)[0]
            r = rows(f, 12)
            hits = [(i, nm, v) for i, nm, v in r if nm in WANT]
            if hits:
                print('0x10 obj=%d -> %s' % (obj, [(nm, v) for i, nm, v in hits]))
    ok = any(nm == 'Weapon' and v for i, nm, v in player_archive)
    ok = ok and not any(nm == 'WeaponMode' and v for i, nm, v in player_archive)
    print('RESULT:', 'PASS' if ok else 'CHECK-ABOVE')
    sock.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
