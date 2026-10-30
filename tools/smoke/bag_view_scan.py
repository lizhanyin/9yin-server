# -*- coding: utf-8 -*-
"""从 wire.log 提取指定时间窗内 send 方向的 0x18 ViewAdd 帧，按 view 分组并解码装备行。

用法: python bag_view_scan.py 2026-10-30_23:48:50 2026-10-30_23:49:15
"""
import re
import sys
from collections import Counter

LOG = r'G:\pojie\ai\9yin-go-server1\logs\wire.log'
HEADER = re.compile(
    r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[\S+\] \S+ (send|recv) remote=\S+ '
    r'opcode=(0x[0-9a-f]+) (?:decoded_)?len=\d+')
HEXLINE = re.compile(r'^[0-9a-f]{8}\s+(.*?)\s*\|')


def load_send_frames():
    frames = []
    cur = None
    with open(LOG, 'r', errors='replace') as f:
        for line in f:
            m = HEADER.match(line)
            if m:
                if cur is not None and cur[1] == 'send':
                    frames.append((cur[0], bytes(cur[2])))
                cur = [m.group(1), m.group(2), bytearray()]
                continue
            h = HEXLINE.match(line)
            if h and cur is not None:
                cur[2].extend(int(x, 16) for x in h.group(1).split())
    if cur is not None and cur[1] == 'send':
        frames.append((cur[0], bytes(cur[2])))
    return frames


def u16(b, o):
    return b[o] | (b[o + 1] << 8)


PROP_NAMES = {1439: 'ConfigID', 1892: 'Amount', 1893: 'MaxAmount', 1896: 'BindStatus',
              1887: 'ItemType', 1936: 'EquipType', 1891: 'UniqueID', 1948: 'MaxBuild',
              1949: 'CurBuild', 1969: 'RecoverFlag', 1890: 'ColorLevel',
              1933: 'Hardiness', 1934: 'MaxHardiness', 1464: 'Level', 184: 'Photo',
              1952: 'PropBuilt', 1957: 'EffectPack', 2011: 'BeginPos', 2012: 'EndPos',
              2213: 'BaseCap', 763: 'CurFill', 764: 'TotalFill'}
PROP_TYPES = {}  # idx -> 's'/'i'/'b'/'w' (string/i32/u8/i16)
for _i in (1439, 1936, 1891, 184, 1952):
    PROP_TYPES[_i] = 's'
for _i in (1887, 1892, 1893, 1896, 1948, 1949, 1890, 1933, 1934, 1957, 2213, 763, 764, 2081, 2235, 2241):
    PROP_TYPES[_i] = 'i'
for _i in (1464, 1969):
    PROP_TYPES[_i] = 'b'
for _i in (2011, 2012):
    PROP_TYPES[_i] = 'w'


def decode_row(b):
    """0x18 view add：u16 属性数@5，其后 u16 属性索引 + 隐含类型值（无 tag 字节）。"""
    out = {}
    o = 7
    while o + 2 <= len(b):
        idx = u16(b, o)
        o += 2
        t = PROP_TYPES.get(idx)
        try:
            if t == 's':
                n = int.from_bytes(b[o:o + 4], 'little')
                out[idx] = b[o + 4:o + 4 + max(0, n - 1)].decode('gbk', 'replace')
                o += 4 + n
            elif t == 'i':
                out[idx] = int.from_bytes(b[o:o + 4], 'little', signed=True)
                o += 4
            elif t == 'b':
                out[idx] = b[o]
                o += 1
            elif t == 'w':
                out[idx] = int.from_bytes(b[o:o + 2], 'little', signed=True)
                o += 2
            else:
                out[idx] = f'<unknown idx {idx} @{o}>'
                break
        except Exception as e:
            out[idx] = f'<err {e}>'
            break
    return out


def main():
    t_lo = sys.argv[1].replace('_', ' ')
    t_hi = sys.argv[2].replace('_', ' ')
    frames = load_send_frames()
    print(f'total send frames: {len(frames)}')
    win = [(t, b) for t, b in frames if t_lo <= t <= t_hi]
    print(f'window {t_lo} ~ {t_hi}: {len(win)} send frames')
    v18 = [(t, b) for t, b in win if len(b) >= 3 and b[0] == 0x18]
    views = Counter(u16(b, 1) for _, b in v18)
    print(f'0x18 frames: {len(v18)}, views: {dict(views)}')
    for view_id in sorted(views):
        rows = [(t, b) for t, b in v18 if u16(b, 1) == view_id]
        limit = 8 if view_id != 121 else 40
        print(f'--- view {view_id} ({len(rows)} rows) ---')
        for t, b in rows[:limit]:
            row = decode_row(b)
            props = ', '.join(f'{PROP_NAMES.get(k, k)}={v}' for k, v in row.items())
            print(f'{t.split()[1]} idx={u16(b, 3):3d} {props}')


if __name__ == '__main__':
    main()
