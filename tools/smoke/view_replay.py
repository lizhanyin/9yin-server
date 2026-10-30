# -*- coding: utf-8 -*-
"""按时间序重放 wire.log 中的 0x15/0x18/0x19 视图帧，模拟客户端视图行集合。

用法: python view_replay.py 2026-10-30_23:48:50 2026-10-30_23:49:15 [view_id]
"""
import re
import sys

LOG = r'G:\pojie\ai\9yin-go-server1\logs\wire.log'
HEADER = re.compile(
    r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[\S+\] \S+ (send|recv) remote=\S+ '
    r'opcode=(0x[0-9a-f]+) (?:decoded_)?len=\d+')
HEXLINE = re.compile(r'^[0-9a-f]{8}\s+(.*?)\s*\|')


def u16(b, o):
    return b[o] | (b[o + 1] << 8)


def main():
    t_lo = sys.argv[1].replace('_', ' ')
    t_hi = sys.argv[2].replace('_', ' ')
    only_view = int(sys.argv[3]) if len(sys.argv) > 3 else None

    ops = []  # (ts, bytes)
    cur = None
    with open(LOG, 'r', errors='replace') as f:
        for line in f:
            m = HEADER.match(line)
            if m:
                if cur is not None and cur[1] == 'send':
                    ops.append((cur[0], bytes(cur[2])))
                cur = [m.group(1), m.group(2), bytearray()]
                continue
            h = HEXLINE.match(line)
            if h and cur is not None:
                cur[2].extend(int(x, 16) for x in h.group(1).split())
    if cur is not None and cur[1] == 'send':
        ops.append((cur[0], bytes(cur[2])))

    views = {}  # view -> dict(idx -> config)
    timeline = []
    for ts, b in ops:
        if not (t_lo <= ts <= t_hi) or not b:
            continue
        if b[0] == 0x15 and len(b) >= 5:
            v = u16(b, 1)
            views.setdefault(v, {})
            timeline.append((ts, 'CREATE', v, u16(b, 3), ''))
        elif b[0] == 0x18 and len(b) >= 7:
            v, idx = u16(b, 1), u16(b, 3)
            rows = views.setdefault(v, {})
            # 提取 ConfigID(1439, string)
            cfg = ''
            o = 7
            n = u16(b, 5)
            for _ in range(n):
                if o + 2 > len(b):
                    break
                pidx = u16(b, o)
                o += 2
                if pidx == 1439 and o + 4 <= len(b):
                    ln = int.from_bytes(b[o:o + 4], 'little')
                    cfg = b[o + 4:o + 4 + ln - 1].decode('gbk', 'replace')
                # 跳过：这里只需粗略推进，string 按长度，其余常见 4 字节近似即可
                if o + 4 <= len(b) and pidx != 1439:
                    o += 4
                elif pidx == 1439 and o + 4 <= len(b):
                    ln = int.from_bytes(b[o:o + 4], 'little')
                    o += 4 + ln
            rows[idx] = cfg
            timeline.append((ts, 'ADD', v, idx, cfg))
        elif b[0] == 0x19 and len(b) >= 5:
            v, idx = u16(b, 1), u16(b, 3)
            rows = views.setdefault(v, {})
            gone = rows.pop(idx, None)
            timeline.append((ts, 'REMOVE', v, idx, f'was={gone}' if gone is not None else '(empty)'))

    if only_view:
        print(f'--- view {only_view} 操作时间线 ---')
        for ts, op, v, idx, extra in timeline:
            if v == only_view:
                print(f'{ts.split()[1]} {op:6s} idx={idx:3d} {extra}')
        rows = views.get(only_view, {})
        print(f'最终存活 {len(rows)} 行: idx={sorted(rows)}')
    else:
        for v in sorted(views):
            rows = views[v]
            print(f'view {v}: 最终 {len(rows)} 行, idx样例={sorted(rows)[:20]}')


if __name__ == '__main__':
    main()
