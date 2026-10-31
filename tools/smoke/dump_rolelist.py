# -*- coding: utf-8 -*-
"""dump 参考抓包里的 0x04 ServerPlayerRoles：角色条目 6 字段 + rolePara 36 段逐段打印。

用法：python -I dump_rolelist.py <s2c.bin> [...]
"""
import struct
import sys


def load_frames(path):
    frames = []
    with open(path, 'rb') as fh:
        while True:
            head = fh.read(6)
            if len(head) < 6:
                break
            ln, idx = struct.unpack('<IH', head)
            frames.append((idx, fh.read(ln)))
    return frames


def read_typed(frame, pos):
    """类型化字段：u8 type + 值。返回 (type, value, new_pos)。"""
    typ = frame[pos]
    pos += 1
    if typ == 2:
        val = struct.unpack('<i', frame[pos:pos + 4])[0]
        return typ, val, pos + 4
    if typ == 5:
        val = struct.unpack('<d', frame[pos:pos + 8])[0]
        return typ, val, pos + 8
    if typ == 7:
        slen = struct.unpack('<I', frame[pos:pos + 4])[0]
        units = struct.unpack('<%dH' % ((slen // 2) - 1), frame[pos + 4:pos + 2 + slen])
        val = ''.join(chr(u) for u in units)
        return typ, val, pos + 4 + slen
    raise ValueError('unknown type %d at %d' % (typ, pos - 1))


def dump(path):
    print('===== %s' % path)
    for idx, f in load_frames(path):
        if not f or f[0] != 0x04:
            continue
        n_roles = struct.unpack('<I', f[0x25:0x29])[0]
        print('frame seq=%d len=%d roles=%d' % (idx, len(f), n_roles))
        pos = 0x29
        for r in range(n_roles):
            nfields = struct.unpack('<H', f[pos:pos + 2])[0]
            pos += 2
            print('  role %d: %d fields' % (r, nfields))
            role_para = None
            for k in range(nfields):
                typ, val, pos = read_typed(f, pos)
                print('    f%d type=%d val=%r' % (k, typ, val))
                if typ == 7 and isinstance(val, str) and val.count(',') >= 20:
                    role_para = val
            if role_para:
                parts = role_para.split(',')
                print('    rolePara %d 段:' % len(parts))
                for i, p in enumerate(parts):
                    print('      [%2d] %r' % (i, p))
        print('  tail %d bytes: %s' % (len(f) - pos, f[pos:pos + 64].hex()))


if __name__ == '__main__':
    for p in sys.argv[1:]:
        dump(p)
