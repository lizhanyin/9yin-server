# -*- coding: utf-8 -*-
"""bak 抓包·官方 SP(怒气)变化帧形态取证：
找 SP 属性值发生过变化的会话，抽出所有含 SP 槽的 0x10 帧，
按 pc(属性个数) 分类：单属性帧 vs 复合帧，并给出精确字节布局。
用法: python -I sp_delta_official.py <s2c.bin> ...
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


def walk(fr, entries, count, pc_off):
    if pc_off + 2 > len(fr):
        return None, pc_off, None
    pc = struct.unpack_from('<H', fr, pc_off)[0]
    off = pc_off + 2
    names = []
    for _ in range(pc):
        if off + 2 > len(fr):
            return None, off, pc
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= count:
            return None, off, pc
        name, ptype = entries[idx]
        names.append(name)
        try:
            _val, off = read_prop(fr, off, ptype)
        except Exception:
            return None, off, pc
    return names, off, pc


def main():
    for path in sys.argv[1:]:
        frames = _CD.load(path)
        tbl = next((f for f in frames if f and f[0] == 0x09), None)
        if tbl is None:
            print('%s: 无 0x09 表' % os.path.basename(path))
            continue
        count, entries, _ = _CD.parse_09(tbl)
        sp_idx = next((i for i, (n, _t) in enumerate(entries) if n == 'SP'), None)
        # 找含 SP 的 0x10 帧
        sp_frames = []
        sp_values = []
        for n, fr in enumerate(frames):
            if not fr or fr[0] != 0x10 or len(fr) < 12:
                continue
            names, off, pc = walk(fr, entries, count, 10)
            if not names or 'SP' not in names:
                continue
            sp_frames.append((n, fr, names, off, pc))
            # 取 SP 值
            o = 12
            for nm in names:
                idx = struct.unpack_from('<H', fr, o)[0]
                o += 2
                val, o = read_prop(fr, o, entries[idx][1])
                if nm == 'SP':
                    sp_values.append((n, val))
        changed = len(set(v for _, v in sp_values)) > 1
        print('\n== %s ==  SP下标=%s  含SP的0x10帧=%d  SP值变化=%s' % (
            os.path.basename(path), sp_idx, len(sp_frames), changed))
        vals = [v for _, v in sp_values]
        if changed:
            seq = [vals[0]] + [vals[i] for i in range(1, len(vals)) if vals[i] != vals[i - 1]]
            print('   SP 值序列: %s' % seq[:25])
        # 按 (pc, names组合) 分类
        combo = Counter()
        for n, fr, names, off, pc in sp_frames:
            combo[(pc, tuple(names))] += 1
        for (pc, names), c in combo.most_common(8):
            print('   x%-4d pc=%d %s' % (c, pc, list(names)[:8]))
        # 单属性 SP 帧样例 hex
        for n, fr, names, off, pc in sp_frames:
            if pc == 1 and names == ['SP'] and off == len(fr):
                print('   单属性SP帧样例 #%d len=%d hex=%s' % (n, len(fr), fr.hex(' ')))
                break


if __name__ == '__main__':
    main()
