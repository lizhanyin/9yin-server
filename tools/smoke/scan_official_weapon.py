# -*- coding: utf-8 -*-
"""扫官方抓包 artifacts 里 0x0D/0x10 帧的 Weapon/ShotWeapon/ActionSet 属性值。
用法: python -I scan_official_weapon.py [glob...]
默认扫 F:/Game/Online/9yin/9yin_server/artifacts/capture-*/out/frames.log
"""
import glob
import re
import struct
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ENTRIES = []
for line in open(r'G:/pojie/ai/9yin-server/nineyin/src/main/resources/'
                 r'nineyin/protocol/official_property_table.txt'):
    p = line.strip().split('\t')
    if len(p) == 3:
        ENTRIES.append((p[1], int(p[2])))
COUNT = len(ENTRIES)
SIZES = {1: 1, 2: 2, 3: 4, 5: 4, 4: 8}


def rows(data, off, cap=999):
    out = []
    while off + 2 <= len(data) and len(out) < cap:
        idx = struct.unpack_from('<H', data, off)[0]
        off += 2
        if idx >= COUNT:
            break
        nm, pt = ENTRIES[idx]
        if pt in SIZES:
            sz = SIZES[pt]
            if off + sz > len(data):
                break
            if pt == 1:
                v = data[off]
            elif pt == 2:
                v = struct.unpack_from('<H', data, off)[0]
            elif pt == 3:
                v = struct.unpack_from('<i', data, off)[0]
            elif pt == 4:
                v = struct.unpack_from('<q', data, off)[0]
            else:
                v = round(struct.unpack_from('<f', data, off)[0], 3)
            off += sz
        elif pt in (7, 8):
            if off + 4 > len(data):
                break
            ln = struct.unpack_from('<I', data, off)[0]
            off += 4
            if off + ln > len(data):
                break
            raw = data[off:off + ln]
            v = raw.rstrip(b'\x00').decode('gbk' if pt == 7 else 'utf-16le', 'replace')
            off += ln
        else:
            break
        out.append((idx, nm, pt, v))
    return out


PAT = re.compile(r'\[S2C[^\]]*\] op=0x([0-9A-Fa-f]{2}) len=\d+ body=([0-9a-f]+)')


def main():
    paths = sys.argv[1:]
    if not paths:
        paths = sorted(glob.glob(
            r'F:/Game/Online/9yin/9yin_server/artifacts/capture-*/out/frames.log'))
    total = 0
    for path in paths:
        tag = path.replace('\\', '/').split('/')[-3]
        found = []
        for line in open(path, errors='replace'):
            m = PAT.search(line)
            if not m:
                continue
            op = int(m.group(1), 16)
            body = bytes.fromhex(m.group(2))
            if op == 0x0D and len(body) > 0x3F:
                frm, r = '0x0D', rows(body, 0x3F)
            elif op == 0x10 and len(body) > 12:
                frm, r = '0x10', rows(body, 12)
            else:
                continue
            for i, nm, pt, v in r:
                if nm in ('Weapon', 'ShotWeapon', 'ActionSet'):
                    found.append((frm, nm, v, [x[1] for x in r][:16]))
                    break
        print(tag, 'frames with weapon/actionset rows:', len(found))
        for frm, nm, v, names in found[:8]:
            print('  ', frm, nm, '=', repr(v)[:64], 'co-rows:', names)
        total += len(found)
    print('TOTAL', total)


if __name__ == '__main__':
    main()
