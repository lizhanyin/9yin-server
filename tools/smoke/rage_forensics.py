# -*- coding: utf-8 -*-
"""取证：官方抓包中 (1) View40 带 AConsume 族属性行全集 (2) 0x10/0x0D 中 SP/MaxSP 属性帧
(3) CanUse 是否在任意 View40 行出现。
用法: python -I rage_forensics.py <s2c.bin>
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

_SPEC2 = importlib.util.spec_from_file_location(
    'view40_forensics', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'view40_forensics.py'))
_V4 = importlib.util.module_from_spec(_SPEC2)
_SPEC2.loader.exec_module(_V4)


def main():
    path = sys.argv[1]
    frames = _CD.load(path)
    table = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)

    # 1) View40 全行属性名统计 + 怒招行(AConsume*)全属性
    adds = [f for f in frames if f and f[0] == 0x18 and len(f) >= 7
            and struct.unpack_from('<H', f, 1)[0] == 40]
    name_counter = Counter()
    rage_rows = []
    for fr in adds:
        row, _, _ = _V4.parse_row(fr, entries, count)
        name_counter.update(row.keys())
        if any(k.startswith('AConsume') for k in row):
            rage_rows.append(row)
    print('View40 行数=%d  属性名出现次数:' % len(adds))
    for name, n in name_counter.most_common():
        print('   %-20s x%d' % (name, n))
    print('\n带 AConsume 族的行 x%d:' % len(rage_rows))
    for row in rage_rows[:8]:
        print('   ---- %s' % row.get('ConfigID'))
        for k, v in row.items():
            print('      %s = %r' % (k, v))

    # 2) 0x10 (objectProperties?) / 0x0D 中 SP idx 帧扫描
    sp_idx = [i for i, (n, t) in enumerate(entries) if n == 'SP']
    maxsp_idx = [i for i, (n, t) in enumerate(entries) if n in ('MaxSP', 'MaxSPAdd')]
    print('\nSP 0x09表下标=%r Max族=%r' % (sp_idx, maxsp_idx))
    if not sp_idx:
        return
    sp_idx = sp_idx[0]
    hits = 0
    for fr in frames:
        if not fr or fr[0] not in (0x0D, 0x10):
            continue
        # 扫帧内 u16 == sp_idx（粗扫，误报由长度/计数排除不了太多，打出来人工看）
        body = fr[1:]
        for off in range(0, min(len(body) - 6, 64)):
            if struct.unpack_from('<H', body, off)[0] == sp_idx:
                hits += 1
                print('   %s帧 len=%d off+1=%d 附近: %s' % (
                    hex(fr[0]), len(fr), off + 1, body[off:off + 10].hex()))
                break
        if hits >= 12:
            break
    print('SP 属性帧粗扫命中=%d' % hits)


if __name__ == '__main__':
    main()
