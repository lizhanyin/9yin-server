# -*- coding: utf-8 -*-
"""57378·CanUse 翻转上下文 + CD 行(cooldown_rec 0x11)时间轴。
用法: python -I rage_flip_context.py <captures_dir>
"""
import importlib.util
import os
import struct
import sys
import time
from collections import Counter

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

FLIPS = [(1886, 11, 0), (1997, 11, 1), (2863, 11, 0), (3055, 11, 1), (4052, 11, 0), (4094, 11, 1)]


def main():
    base = sys.argv[1]
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    c2s = _CD.load(os.path.join(base, '127-0-0-1-57378_c2s.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)

    # CD 行: 0x11 ServerRecordAddRow cooldown_rec —— [cat i32, start i64, end i64, u16]
    print('== 0x11 记录行（前4条看结构 + 全部疑似CD行）==')
    shown = 0
    cd_rows = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x11 or len(fr) < 16:
            continue
        view = struct.unpack_from('<I', fr, 1)[0]
        row = struct.unpack_from('<I', fr, 5)[0]
        if shown < 4:
            print('  #%d view=%d row=%d len=%d hex=%s' % (n, view, row, len(fr), fr[:40].hex()))
            shown += 1
        # 冷却行特征: 两个 i64 时间戳（13 位 unix ms）
        try:
            body = fr[9:]
            cat = struct.unpack_from('<i', body, 0)[0]
            t1 = struct.unpack_from('<q', body, 4)[0]
            t2 = struct.unpack_from('<q', body, 12)[0]
            if 1.6e12 < t1 < 1.9e12 and 1.6e12 < t2 < 1.9e12 and t2 >= t1:
                cd_rows.append((n, view, row, cat, t1, t2, (t2 - t1) / 1000.0))
        except Exception:
            pass
    print('CD 行总数=%d' % len(cd_rows))
    for n, view, row, cat, t1, t2, dur in cd_rows:
        print('   #%d view=%d row=%d cat=%d dur=%.1fs [%s→%s]' % (
            n, view, row, cat, dur,
            time.strftime('%H:%M:%S', time.localtime(t1 / 1000)),
            time.strftime('%H:%M:%S', time.localtime(t2 / 1000))))

    # 翻转窗口上下文
    for center, rowno, val in FLIPS:
        print('\n===== 翻转 #%d 行%d→%d 上下文 [#%d..#%d] =====' % (center, rowno, val, center - 25, center + 25))
        for n in range(max(0, center - 25), min(len(s2c), center + 26)):
            fr = s2c[n]
            if not fr:
                continue
            op = fr[0]
            desc = 'len=%d' % len(fr)
            if op == 0x10 and len(fr) >= 12:
                isview = fr[1]
                obj = struct.unpack_from('<I', fr, 2)[0]
                owner = struct.unpack_from('<I', fr, 6)[0]
                if isview == 1 and obj == 40:
                    desc = 'View40 行%d CanUse=%d (len=%d)' % (owner, fr[14], len(fr)) if len(fr) == 15 \
                        else 'View40 行%d 多属性 len=%d' % (owner, len(fr))
                else:
                    desc = 'obj=%#x len=%d' % (obj, len(fr))
            elif op == 0x11 and len(fr) >= 20:
                desc = 'record view=%d row=%d %s' % (
                    struct.unpack_from('<I', fr, 1)[0], struct.unpack_from('<I', fr, 5)[0], fr[9:29].hex())
            elif op in (0x1E, 0x27):
                try:
                    vals = _CD.parse_tvarlist(fr[1:])
                    desc = 'custom %s' % (vals[:5],)
                except Exception:
                    desc = 'custom len=%d' % len(fr)
            elif op == 0x13:
                desc = '动作/攻击 len=%d %s' % (len(fr), fr[:16].hex())
            mark = ' <<< 翻转' if n == center else ''
            print('   #%d 0x%02X %s%s' % (n, op, desc, mark))


if __name__ == '__main__':
    main()
