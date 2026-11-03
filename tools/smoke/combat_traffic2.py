# -*- coding: utf-8 -*-
"""57378 战斗传输分析 v2：会话切分 / c2s custom(0x0A 信封) / CanUse 0 值行定位 / 出招链对齐。
用法: python -I combat_traffic2.py <dir>
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
    base = sys.argv[1]
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    c2s = _CD.load(os.path.join(base, '127-0-0-1-57378_c2s.bin'))
    tables = [f for f in s2c if f and f[0] == 0x09]
    count, entries, _ = _CD.parse_09(tables[0])
    canuse_idx = next(i for i, (n, t) in enumerate(entries) if n == 'CanUse')
    sp_idx = next(i for i, (n, t) in enumerate(entries) if n == 'SP')

    # ---- 会话切分：0x15 create_view(view=40) 的 cap ----
    grants = []
    for n, fr in enumerate(s2c):
        if fr and fr[0] == 0x15 and len(fr) >= 5:
            v, cap = struct.unpack_from('<HH', fr, 1)
            if v == 40:
                grants.append((n, cap))
    print('View40 授予次数(=会话/重连数)=%d caps=%s' % (len(grants), [c for _, c in grants]))

    # 行号→ConfigID（最后一段）
    rowcfg = {}
    for fr in s2c:
        if fr and fr[0] == 0x18 and len(fr) >= 7 and struct.unpack_from('<H', fr, 1)[0] == 40:
            obj = struct.unpack_from('<H', fr, 3)[0]
            # ConfigID 是 idx1440 窄串——粗解析：找 1440 的 u16 后跟 u32len
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
    print('View40 行总数(跨会话)=%d' % len(rowcfg))

    # ---- c2s customs ----
    print('\n== C2S custom(0x0A) ==')
    cc = Counter()
    seq_events = []
    for idx, seq, mid, vals in _CD.c2s_customs(c2s):
        cc[mid] += 1
        if mid in (211, 431, 54, 99, 30, 31):
            seq_events.append((idx, mid, vals))
    print('msgId 分布:', cc.most_common(15))
    for idx, mid, vals in seq_events[:20]:
        vs = []
        for v in vals[:6]:
            if isinstance(v, bytes):
                vs.append(v[:24].decode('gbk', 'replace') if v else '')
            else:
                vs.append(v)
        print('  #%d msg%s %s' % (idx, mid, vs))

    # ---- CanUse 批：0 值行定位 + 批间隔 ----
    pushes = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) != 15 or fr[1] != 1:
            continue
        view, row = struct.unpack_from('<II', fr, 2)
        if view == 40 and struct.unpack_from('<H', fr, 12)[0] == canuse_idx:
            pushes.append((n, row, fr[14]))
    batches = []
    for t in pushes:
        if batches and t[0] - batches[-1][-1][0] <= 3:
            batches[-1].append(t)
        else:
            batches.append([t])
    print('\n== CanUse 批 ==')
    print('总数=%d 批=%d' % (len(pushes), len(batches)))
    zeros = [(n, r, v) for n, r, v in pushes if v == 0]
    print('0 值推送=%d 行分布=%s' % (
        len(zeros), Counter(rowcfg.get(r, r) for _, r, _ in zeros).most_common()))
    # 每批行数分布
    print('批行数分布:', Counter(len(b) for b in batches).most_common(6))
    starts = [b[0][0] for b in batches]
    print('批起点前 12:', starts[:12])
    # 0 值推送的上下文（前后帧）
    for n, r, v in zeros[:12]:
        ctx = [(hex(f[0]) if f else '?') for f in s2c[max(0, n - 2):n + 2]]
        print('  0值 @#%d row=%d(%s) ctx=%s' % (n, r, rowcfg.get(r, '?'), ctx))

    # ---- SP 全帧值序列 ----
    sp_vals = []
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10:
            continue
        for off in range(1, len(fr) - 5):
            if struct.unpack_from('<H', fr, off)[0] == sp_idx:
                sp_vals.append((n, struct.unpack_from('<i', fr, off + 2)[0]))
                break
    uniq = []
    for n, v in sp_vals:
        if not uniq or uniq[-1][1] != v:
            uniq.append((n, v))
    print('\n== SP 变化序列 ==')
    print(sp_vals[:3] if sp_vals else '无SP帧')
    print('变化点:', uniq[:30])


if __name__ == '__main__':
    main()
