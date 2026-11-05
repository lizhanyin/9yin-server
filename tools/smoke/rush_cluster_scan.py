# -*- coding: utf-8 -*-
"""冲锋技受理簇对拍：扫本地参考服抓包里 C2S 211 冲锋技出招，
dump 每次出招前后 s2c 完整帧序（信封类型+custom msgId+关键值），
并与我方实现对比找差异帧。
用法: python -I rush_cluster_scan.py <captures_dir> [skill_id ...]
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

RUSH_IDS = {'CS_jh_yqq02', 'CS_jh_myjf02', 'CS_jh_cqgf01', 'CS_jh_sfgp01',
            'CS_jh_jysg03', 'CS_wd_tjj01', 'CS_sl_djgq07'}


def describe_s2c(frames, lo, hi):
    out = []
    for i in range(lo, hi):
        fr = frames[i]
        if not fr:
            continue
        op = fr[0]
        if op in (0x1E, 0x27):
            for _idx, _op, msg_id, vals in _CD.s2c_customs([fr]):
                txt = ''
                if msg_id in (20, 24, 25, 26, 337) and len(vals) > 1:
                    v = vals[1]
                    txt = str(v)[:36] if not isinstance(v, bytes) else v[:20].hex()
                out.append('m%d(%s)' % (msg_id, txt))
        elif op == 0x10:
            out.append('0x10')
        elif op == 0x11:
            out.append('0x11')
        elif op == 0x12:
            out.append('0x12')
        elif op == 0x13:
            out.append('0x13')
        elif op == 0x1F:
            out.append('0x1F')
        elif op == 0x0D:
            out.append('0x0D')
        else:
            out.append('%02X' % op)
    return out


def main():
    cap_dir = sys.argv[1]
    want = set(sys.argv[2:]) or RUSH_IDS
    stats = Counter()
    for name in sorted(os.listdir(cap_dir)):
        if not name.endswith('_c2s.bin'):
            continue
        tag = name[:-8]
        c2s = _CD.load(os.path.join(cap_dir, tag + '_c2s.bin'))
        s2c_path = os.path.join(cap_dir, tag + '_s2c.bin')
        if not os.path.exists(s2c_path):
            continue
        s2c = _CD.load(s2c_path)
        casts = []
        for idx, seq, msg_id, vals in _CD.c2s_customs(c2s):
            if msg_id == 211 and len(vals) > 1:
                skill = vals[1]
                if isinstance(skill, str) and any(k in skill for k in want):
                    casts.append((idx, skill))
        if not casts:
            continue
        print('=== %s  casts=%d' % (tag, len(casts)))
        for idx, skill in casts[:6]:
            stats[skill.split('\x00')[0]] += 1
            # c2s idx -> s2c 时间邻域：信封流各自独立，取 s2c 帧号邻域没意义；
            # 改扫 s2c 全流里该技能的 msg20/CD 簇位置，再向前取 12 帧
            hits = [i for i, fr in enumerate(s2c)
                    if fr and fr[0] in (0x1E, 0x27)
                    for _i, _op, mid, vals in _CD.s2c_customs([fr])
                    if mid == 20 and len(vals) > 2 and isinstance(vals[1], str)
                    and skill.split('\x00')[0] in vals[1]]
            for h in hits[:3]:
                seq_desc = describe_s2c(s2c, max(0, h - 14), h + 4)
                print('  [%s] msg20@%d <- %s' % (skill[:16], h, ' '.join(seq_desc)))
    print('\ncast skill tally:', dict(stats))


if __name__ == '__main__':
    main()
