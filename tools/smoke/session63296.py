# -*- coding: utf-8 -*-
"""63296 当前测试会话分析：CanUse 覆盖率 / 出招受理与静默拒 / 目标选择链。
用法: python -I session63296.py <s2c.bin> <c2s.bin>
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


def main():
    s2c_path, c2s_path = sys.argv[1], sys.argv[2]
    s2c = _CD.load(s2c_path)
    c2s = _CD.load(c2s_path)
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    canuse_idx = next(i for i, (n, t) in enumerate(entries) if n == 'CanUse')

    # View40 授予
    for n, fr in enumerate(s2c):
        if fr and fr[0] == 0x15 and len(fr) >= 7 and struct.unpack_from('<H', fr, 1)[0] == 40:
            print('#%d createView view=40 cap=%d raw=%s' % (
                n, struct.unpack_from('<H', fr, 3)[0], fr.hex()))
    adds = [f for f in s2c if f and f[0] == 0x18 and len(f) >= 7
            and struct.unpack_from('<H', f, 1)[0] == 40]
    rows = [struct.unpack_from('<H', f, 3)[0] for f in adds]
    rowcfg = {}
    for fr in adds:
        obj = struct.unpack_from('<H', fr, 3)[0]
        off = 7
        pc = struct.unpack_from('<H', fr, 5)[0]
        while off + 2 <= len(fr):
            idx = struct.unpack_from('<H', fr, off)[0]
            off += 2
            if idx >= count:
                break
            name, ptype = entries[idx]
            if ptype == 1:
                off += 1
            elif ptype == 2:
                off += 2
            elif ptype in (3, 5):
                off += 4
            elif ptype == 7:
                ln = struct.unpack_from('<I', fr, off)[0]
                off += 4
                if name == 'ConfigID':
                    rowcfg[obj] = fr[off:off + ln].rstrip(b'\x00').decode('gbk', 'replace')
                off += ln
            else:
                break
    print('View40 行数=%d 行号 %d..%d' % (len(adds), min(rows), max(rows)))

    # CanUse 推送覆盖与值
    pushes = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) != 15 or fr[1] != 1:
            continue
        view, row = struct.unpack_from('<II', fr, 2)
        if view == 40 and struct.unpack_from('<H', fr, 12)[0] == canuse_idx:
            pushes.append((n, row, fr[14]))
    distinct_rows = set(r for _, r, _ in pushes)
    print('CanUse 推送=%d 覆盖行数=%d/%d 行范围=%s..%s' % (
        len(pushes), len(distinct_rows), len(rowcfg),
        min(distinct_rows) if distinct_rows else '-', max(distinct_rows) if distinct_rows else '-'))
    print('值分布:', Counter(v for _, _, v in pushes))
    zeros = Counter(rowcfg.get(r, r) for _, r, v in pushes if v == 0)
    print('0 值行:', zeros.most_common(10))
    not_pushed = [rowcfg.get(r, r) for r in sorted(rowcfg) if r not in distinct_rows]
    print('从未推过的行数=%d 例=%s' % (len(not_pushed), not_pushed[:8]))

    # c2s: 211 出招 + 431/0x07 选择 + s2c 回应
    print('\n== C2S 动作流 ==')
    casts = []
    for idx, seq, mid, vals in _CD.c2s_customs(c2s):
        if mid == 211:
            sid = vals[1].decode('gbk', 'replace') if isinstance(vals[1], bytes) else vals[1]
            casts.append((idx, sid))
        elif mid in (431,):
            print('  #c2s%d msg431 select %s' % (idx, vals[1:3]))
    print('211 出招次数=%d 技能分布=%s' % (
        len(casts), Counter(s for _, s in casts).most_common(10)))

    # 每个 211 后 s2c 是否有回应帧（接下来 40 帧内 0x11/0x10/custom20/0x13）
    s2c_len = len(s2c)
    print('\n== 出招回应（每招后 40 帧窗口）==')
    for i, (cidx, sid) in enumerate(casts):
        nxt = casts[i + 1][0] if i + 1 < len(casts) else cidx + 400
        window = s2c[cidx:min(nxt, cidx + 120)]
        ops = Counter(hex(f[0]) for f in window if f)
        has_cd = any(f and f[0] == 0x11 for f in window[:40])
        has_zero = any(f and f[0] == 0x10 and len(f) == 15 and f[1] == 1
                       and struct.unpack_from('<I', f, 2)[0] == 40
                       and struct.unpack_from('<H', f, 12)[0] == canuse_idx
                       and f[14] == 0 for f in window[:40])
        print('  cast#%d %s -> CD行=%s CanUse0=%s ops=%s' % (
            cidx, sid, has_cd, has_zero, dict(ops.most_common(5))))


if __name__ == '__main__':
    main()
