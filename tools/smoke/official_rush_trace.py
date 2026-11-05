# -*- coding: utf-8 -*-
"""官服 frames.log 冲锋追踪：解 C2S 211 出招（技能/坐标/目标），
按时间窗 dump S2C 应答簇与后续玩家位移（C2S 211/msg15 位置跳变=客户端冲刺证据）。
用法: python -I official_rush_trace.py <frames.log> [skill_substring ...]
"""
import binascii
import importlib.util
import os
import re
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

LINE = re.compile(r'^(\d\d:\d\d:\d\d\.\d+) \[(S2C|C2S) ([^\]]+)\] op=(0x[0-9A-Fa-f]+) len=(\d+) body=([0-9a-f]*)')


def to_ms(ts):
    h, m, rest = ts.split(':')
    s, ms = rest.split('.')
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(ms)


def fmt(v):
    if isinstance(v, float):
        return '%.2f' % v
    return str(v)[:30]


def main():
    path = sys.argv[1]
    want = [w.lower() for w in sys.argv[2:]] or ['myjf', 'yqq', 'cqgf', 'sfgp', 'jysg', 'shl', 'tjj', 'djgq']
    rows = []  # (ms, dir, op, raw)
    with open(path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            m = LINE.match(line.strip())
            if not m:
                continue
            ts, d, _endp, op, ln, body = m.groups()
            raw = bytes.fromhex(body) if body else b''
            rows.append((to_ms(ts), d, int(op, 16), raw))
    casts = []
    movements = []
    for i, (ms, d, op, raw) in enumerate(rows):
        if d == 'C2S' and op == 0x0A and len(raw) >= 24:
            vals = _CD.parse_tvarlist(raw[21:])
            if vals and isinstance(vals[0], int) and vals[0] == 211 and len(vals) > 2:
                skill = vals[1] if isinstance(vals[1], str) else '?'
                casts.append((i, ms, skill, vals))
            elif vals and isinstance(vals[0], int) and vals[0] == 15 and len(vals) > 6:
                movements.append((i, ms, vals))
    hits = [(i, ms, sk, vals) for i, ms, sk, vals in casts
            if isinstance(sk, str) and any(w in sk.lower() for w in want)]
    print('total 211 casts=%d  rush-family=%d  msg15 movements=%d' % (len(casts), len(hits), len(movements)))
    for i, ms, sk, vals in hits:
        xyz = tuple(round(v, 2) for v in vals[2:5] if isinstance(v, float)) if len(vals) >= 5 else ()
        aim = vals[10] if len(vals) > 10 else None
        txyz = tuple(round(v, 2) for v in vals[11:14] if isinstance(v, float)) if len(vals) > 13 else ()
        print('\n== cast #%d t=%s skill=%s pos=%s aim=%s tpos=%s' % (i, ms, sk, xyz, aim, txyz))
        # S2C 应答簇：之后 1.5s 内的 custom 帧 + 0x1F/0x21
        for j in range(i, min(i + 400, len(rows))):
            ms2, d2, op2, raw2 = rows[j]
            if ms2 - ms > 1500:
                break
            if d2 != 'S2C':
                continue
            if op2 in (0x1E, 0x27):
                for _i, _op, mid, cv in _CD.s2c_customs([raw2]):
                    if mid in (20, 24, 25, 26, 337, 169, 294, 205, 18):
                        print('   +%4dms m%-4d %s' % (ms2 - ms, mid, ' | '.join(fmt(v) for v in cv[1:6])))
            elif op2 == 0x1F and len(raw2) >= 25:
                x, y, z, o = struct.unpack_from('<ffff', raw2, 9)
                print('   +%4dms 0x1F x=%.2f y=%.2f z=%.2f o=%.2f' % (ms2 - ms, x, y, z, o))
        # 出招后 2.5s 内玩家位移（C2S 211 + msg15 上报）
        for j in range(i + 1, min(i + 800, len(rows))):
            ms2, d2, op2, raw2 = rows[j]
            if ms2 - ms > 2500:
                break
            if d2 == 'C2S' and op2 == 0x0A:
                vals2 = _CD.parse_tvarlist(raw2[21:]) if len(raw2) >= 24 else []
                if vals2 and isinstance(vals2[0], int):
                    if vals2[0] == 15 and len(vals2) > 6 and all(isinstance(v, float) for v in vals2[3:6]):
                        dx = vals2[3] - vals[2] if isinstance(vals[2], float) else 0
                        dz = vals2[5] - vals[4] if len(vals) > 4 and isinstance(vals[4], float) else 0
                        print('   +%4dms C2S15 pos=(%.2f,%.2f,%.2f) d=(%+.2f,%+.2f)'
                              % (ms2 - ms, vals2[3], vals2[4], vals2[5], dx, dz))
                    elif vals2[0] == 211 and len(vals2) > 5 and all(isinstance(v, float) for v in vals2[2:5]):
                        dx = vals2[2] - vals[2] if isinstance(vals[2], float) else 0
                        dz = vals2[4] - vals[4] if len(vals) > 4 and isinstance(vals[4], float) else 0
                        print('   +%4dms C2S211[%s] pos=(%.2f,%.2f,%.2f) d=(%+.2f,%+.2f)'
                              % (ms2 - ms, str(vals2[1])[:14], vals2[2], vals2[3], vals2[4], dx, dz))


if __name__ == '__main__':
    main()
