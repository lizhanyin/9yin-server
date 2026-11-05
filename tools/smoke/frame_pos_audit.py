# -*- coding: utf-8 -*-
"""57378·帧位置/偏移严格校验：
 (1) 解码层：EE-escape 流是否完整消费文件（无尾巴、无丢帧）
 (2) 0x10 行：pc@10 对齐解析能否精确吃满整帧（逐帧报告残留）
 (3) 0x18 view40 行：pc@5 对齐解析能否精确吃满
 (4) 0x0D AddObject：0x3F 起的属性块对齐解析 + 里面有没有 SP/CanUse/InParry
     —— 即"漏看的位置"
 (5) 6 个 CanUse 翻转帧的原始 hex
用法: python -I frame_pos_audit.py <captures_dir>
"""
import importlib.util
import os
import struct
import sys
from collections import Counter

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def read_prop(fr, off, ptype):
    if ptype == 1:
        return fr[off], off + 1
    if ptype == 2:
        return struct.unpack_from('<H', fr, off)[0], off + 2
    if ptype == 3:
        return struct.unpack_from('<i', fr, off)[0], off + 4
    if ptype == 4:
        return struct.unpack_from('<q', fr, off)[0], off + 8
    if ptype == 5:
        return round(struct.unpack_from('<f', fr, off)[0], 3), off + 4
    if ptype == 6:
        return round(struct.unpack_from('<d', fr, off)[0], 3), off + 8
    if ptype == 9:
        return 'obj', off + 8
    if ptype == 7:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].decode('utf-16-le', 'replace'), off + n
    return None, off


def walk_props(fr, entries, count, pc_off):
    """返回 (rows, end_off, pc) —— 严格走完 pc 个属性后的偏移"""
    if pc_off + 2 > len(fr):
        return None, pc_off, None
    pc = struct.unpack_from('<H', fr, pc_off)[0]
    off = pc_off + 2
    row = {}
    for _ in range(pc):
        if off + 2 > len(fr):
            return None, off, pc
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= count:
            return None, off, pc
        name, ptype = entries[idx]
        try:
            val, off = read_prop(fr, off, ptype)
        except Exception:
            return None, off, pc
        row[name] = val
    return row, off, pc


def decode_coverage(path):
    """复刻 decode：统计文件字节是否全部被帧边界消费。"""
    buf = open(path, 'rb').read()
    i, frames, body_bytes, esc = 0, 0, 0, 0
    while i < len(buf):
        b = buf[i]
        if b == 0xEE:
            if i + 1 >= len(buf):
                return len(buf), frames, i, 'trailing 1 byte after last escape'
            nxt = buf[i + 1]
            if nxt == 0x00:
                body_bytes += 1
                i += 2
            elif nxt == 0xEE:
                frames += 1
                i += 2
            else:
                return len(buf), frames, i, 'BAD escape EE %02X at %d' % (nxt, i)
        else:
            body_bytes += 1
            i += 1
    return len(buf), frames, i, 'clean eof'


def main():
    base = sys.argv[1]
    for side in ('s2c', 'c2s'):
        path = os.path.join(base, '127-0-0-1-57378_%s.bin' % side)
        total, nfr, endpos, status = decode_coverage(path)
        print('[%s] 文件=%dB 帧数=%d 结束位=%d 状态=%s' % (side, total, nfr, endpos, status))

    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    idx_of = {}
    for i, (n, _t) in enumerate(entries):
        idx_of.setdefault(n, i)

    # (2) 0x10 对齐校验
    stat = Counter()
    residue = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) < 12:
            continue
        row, off, pc = walk_props(fr, entries, count, 10)
        isview = fr[1]
        if row is None:
            stat['parse_fail'] += 1
            residue.append((n, 'FAIL', len(fr), pc))
            continue
        left = len(fr) - off
        if left == 0:
            stat['exact'] += 1
        else:
            stat['residue+%d' % min(left, 9)] += 1
            if len(residue) < 12:
                residue.append((n, 'residue=%d' % left, len(fr), pc))
    print('\n0x10 帧对齐校验: %s' % dict(stat))
    for r in residue:
        print('   #%s %s len=%s pc=%s' % r)

    # (3) 0x18 view40 对齐校验
    stat18 = Counter()
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x18 or len(fr) < 7:
            continue
        if struct.unpack_from('<H', fr, 1)[0] != 40:
            continue
        row, off, pc = walk_props(fr, entries, count, 5)
        if row is None:
            stat18['parse_fail'] += 1
        elif off == len(fr):
            stat18['exact'] += 1
        else:
            stat18['residue'] += 1
    print('0x18 view40 行对齐校验: %s' % dict(stat18))

    # (4) 0x0D AddObject 属性块 @0x3F —— 找 SP/CanUse/InParry/MaxSP
    print('\n0x0D 帧（AddObject）内属性块扫描：')
    hits = Counter()
    sample = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x0D or len(fr) <= 0x3F:
            continue
        row, off, pc = walk_props(fr, entries, count, 0x3F)
        if row is None:
            sample.append((n, len(fr), 'FAIL@0x3F'))
            continue
        keys = set(row) & {'SP', 'MaxSP', 'CanUse', 'InParry', 'MP', 'LogicState'}
        if keys:
            hits[tuple(sorted(keys))] += 1
            if len(sample) < 10:
                sample.append((n, len(fr), 'pc=%d off=%d/%d 含:%s SP=%r' % (
                    pc, off, len(fr), sorted(keys), row.get('SP'))))
        elif off != len(fr) and len(sample) < 10:
            sample.append((n, len(fr), 'pc=%d off=%d 残%d 无关注属性' % (pc, off, len(fr) - off)))
    print('  命中组合:', dict(hits) if hits else '（0x0D 属性块中无 SP/CanUse/InParry）')
    for s in sample:
        print('   #%d len=%d %s' % s)

    # (5) 翻转帧原始 hex
    print('\nCanUse 翻转帧原始字节：')
    flips = ((1886, 0), (1997, 1), (2863, 0), (3055, 1), (4052, 0), (4094, 1))
    for n, expect in flips:
        fr = s2c[n]
        ok = (fr[0] == 0x10 and fr[1] == 1 and struct.unpack_from('<I', fr, 2)[0] == 40
              and struct.unpack_from('<I', fr, 6)[0] == 11
              and struct.unpack_from('<H', fr, 10)[0] == 1
              and struct.unpack_from('<H', fr, 12)[0] == idx_of['CanUse']
              and fr[14] == expect and len(fr) == 15)
        print('   #%d len=%d %s %s' % (n, len(fr), fr.hex(), 'OK' if ok else '!!!=不匹配'))

    # (6) 全帧 opcode 合法性
    bad = [n for n, fr in enumerate(s2c) if fr and (fr[0] > 0x38 or fr[0] == 0)]
    print('\nopcode 越界帧: %s' % (bad[:10] if bad else '无（解码层干净）'))


if __name__ == '__main__':
    main()
