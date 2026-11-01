# -*- coding: utf-8 -*-
"""F 盘参考服 captures/local 抓包深挖工具(解码 + 双向全景 + custom 信封 + 54↔169 链 + 0x09 表对比)。

用法(建议 -I 隔离运行):
  python -I capture_deep.py overview  <c2s.bin> <s2c.bin>
  python -I capture_deep.py table09   <s2c.bin> [official_property_table.txt]
  python -I capture_deep.py parry     <c2s.bin> <s2c.bin>
  python -I capture_deep.py views     <s2c.bin>
  python -I capture_deep.py customs   <bin> <c2s|s2c>

帧格式: 明文帧逐字节 XOR(KEY=CC 3B 6D FA, 帧内偏移 i%4) 后 0xEE 转义(EE→EE 00, 帧尾 EE EE)。
C2S 0x0A custom 信封: 0x0A | 16B session | u32 seq | TVarList(count u16 + [tag value]*)。
S2C 0x1E/0x27 custom: opcode | TVarList(同上, 无信封头)。
TVarList tags: 2=i32 3=i64 4=f32 5=f64 6=text(u32 len) 7=wide(u32 len utf16) 8=raw16。
已知帧布局:
  0x09 属性表: u16 count + (name\\0 u8 type)*
  0x15 create_view: u16 view + u32 cap
  0x18 view_add: u16 view + u16 objIdx + ...
  0x11 record_add_row: u32 objId + u32 recView + ... + row payload
  0x13 record_grid: u32 objId + u32 recView + u16 rec + u16 row + u16 col + value
  0x1F location: u32 objId + f32 x + f32 y + f32 z + u32
"""
import struct
import sys
from collections import Counter

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def decode_frames(buf):
    """EE EE 切帧 → 去转义 → 帧内偏移对齐 KEY 异或。返回明文帧列表。"""
    frames, body = [], bytearray()
    i = 0
    while i < len(buf):
        if buf[i] == 0xEE and i + 1 < len(buf):
            nxt = buf[i + 1]
            if nxt == 0x00:
                body.append(0xEE)
                i += 2
            elif nxt == 0xEE:
                frames.append(bytes(b ^ KEY[j % 4] for j, b in enumerate(body)))
                body = bytearray()
                i += 2
            else:
                break
        else:
            body.append(buf[i])
            i += 1
    return frames


def load(path):
    return decode_frames(open(path, 'rb').read())


# ---------- TVarList ----------

def parse_tvarlist(payload):
    """解析 count u16 + 值列表; 结构不符返回 None。"""
    if len(payload) < 2:
        return None
    count = struct.unpack_from('<H', payload)[0]
    if count == 0 or count > 1024:
        return None
    rest = payload[2:]
    vals = []
    for _ in range(count):
        if not rest:
            return None
        tag = rest[0]
        rest = rest[1:]
        try:
            if tag == 2:
                vals.append(struct.unpack('<i', rest[:4])[0]); rest = rest[4:]
            elif tag == 3:
                vals.append(struct.unpack('<q', rest[:8])[0]); rest = rest[8:]
            elif tag == 4:
                vals.append(round(struct.unpack('<f', rest[:4])[0], 3)); rest = rest[4:]
            elif tag == 5:
                vals.append(round(struct.unpack('<d', rest[:8])[0], 3)); rest = rest[8:]
            elif tag in (6, 7):
                n = struct.unpack('<I', rest[:4])[0]
                rest = rest[4:]
                raw, rest = rest[:n], rest[n:]
                try:
                    s = raw.decode('utf-16-le') if tag == 7 else raw.rstrip(b'\x00').decode('gbk', 'replace')
                except UnicodeDecodeError:
                    s = raw.hex()
                vals.append(s)
            elif tag == 8:
                # object: u32 objID + u32 ownerID(与 SceneObj 的 object/owner 对同构)
                oid, owner = struct.unpack('<II', rest[:8])
                vals.append(f'obj({oid:#x},{owner:#x})'); rest = rest[8:]
            else:
                return None
        except struct.error:
            return None
    return vals if not rest else None


def fmt_val(v):
    if isinstance(v, str):
        return f'"{v[:48]}"'
    return str(v)


def c2s_customs(frames):
    """yield (idx, seq, msgId, vals) — 0x0A 信封全部 custom 帧。"""
    for idx, fr in enumerate(frames):
        if not fr or fr[0] != 0x0A or len(fr) < 24:
            continue
        seq = struct.unpack_from('<I', fr, 17)[0]
        vals = parse_tvarlist(fr[21:])
        if vals and isinstance(vals[0], int):
            yield idx, seq, vals[0], vals


def s2c_customs(frames):
    """yield (idx, opcode, msgId, vals) — 0x1E/0x27 帧。"""
    for idx, fr in enumerate(frames):
        if not fr or fr[0] not in (0x1E, 0x27) or len(fr) < 3:
            continue
        vals = parse_tvarlist(fr[1:])
        if vals and isinstance(vals[0], int):
            yield idx, fr[0], vals[0], vals


# ---------- commands ----------

def cmd_overview(c2s_path, s2c_path):
    for path, tag in ((c2s_path, 'C2S'), (s2c_path, 'S2C')):
        frames = load(path)
        print(f'== {tag} {path}: {len(frames)} frames')
        hist, sizes, firsts = Counter(), {}, {}
        for idx, fr in enumerate(frames):
            op = fr[0] if fr else -1
            hist[op] += 1
            sizes[op] = sizes.get(op, 0) + len(fr)
            firsts.setdefault(op, idx)
        for op, cnt in sorted(hist.items(), key=lambda kv: -kv[1]):
            print(f'   0x{op:02X}  x{cnt:<5} bytes={sizes[op]:<7} first@{firsts[op]}')
        if tag == 'C2S':
            ch = Counter(m for _, _, m, _ in c2s_customs(frames))
            print(f'   -- 0x0A custom msgId histogram:')
            for mid, cnt in sorted(ch.items(), key=lambda kv: -kv[1]):
                print(f'      msgId={mid:<6} x{cnt}')
        else:
            ch = Counter(m for _, _, m, _ in s2c_customs(frames))
            print(f'   -- 0x1E/0x27 custom msgId histogram:')
            for mid, cnt in sorted(ch.items(), key=lambda kv: -kv[1]):
                print(f'      msgId={mid:<6} x{cnt}')


def parse_09(frame):
    """0x09 | u16 count | (name\\0 u8 type)* → [(name, type)]"""
    count = struct.unpack_from('<H', frame, 1)[0]
    entries, off = [], 3
    for _ in range(count):
        end = frame.index(b'\x00', off)
        entries.append((frame[off:end].decode('latin-1'), frame[end + 1]))
        off = end + 2
    return count, entries, off


def cmd_table09(s2c_path, table_path=None):
    frames = load(s2c_path)
    fr = next(f for f in frames if f and f[0] == 0x09)
    count, entries, off = parse_09(fr)
    print(f'== S2C 0x09 property table: count={count} consumed={off}/{len(fr)} tail={len(fr)-off}B')
    print(f'   first 10: {entries[:10]}')
    if table_path:
        official = []
        for line in open(table_path, encoding='ascii'):
            parts = line.strip().split('\t')
            if len(parts) == 3:
                official.append((int(parts[0]), parts[1], int(parts[2])))
        print(f'   official table: {len(official)} entries')
        cap = min(len(entries), len(official))
        diffs = [(i, entries[i], (official[i][1], official[i][2]))
                 for i in range(cap) if entries[i][0] != official[i][1] or entries[i][1] != official[i][2]]
        print(f'   positional diffs: {len(diffs)}')
        for i, cap_e, off_e in diffs[:20]:
            print(f'     @{i}: capture={cap_e} official={off_e}')
        names_c = [e[0] for e in entries]
        names_o = [e[1] for e in official]
        only_c = [n for n in names_c if n not in set(names_o)]
        only_o = [n for n in names_o if n not in set(names_c)]
        print(f'   names only in capture: {len(only_c)} {only_c[:10]}')
        print(f'   names only in official: {len(only_o)} {only_o[:10]}')


def cmd_parry(c2s_path, s2c_path):
    c2s = load(c2s_path)
    s2c = load(s2c_path)
    sends = [(idx, seq, v[1]) for idx, seq, m, v in c2s_customs(c2s) if m == 54]
    recvs = [(idx, v[1]) for idx, op, m, v in s2c_customs(s2c) if op == 0x1E and m == 169]
    resets = [(idx, v) for idx, op, m, v in s2c_customs(s2c) if op == 0x27]
    print(f'== parry chain: C2S msgId=54 x{len(sends)} → S2C 0x1E msg169 x{len(recvs)} + 0x27 reset x{len(resets)}')
    print('   C2S value run-length:', runlength(v for _, _, v in sends))
    print('   S2C value run-length:', runlength(v for _, v in recvs))
    edges = [v for i, v in enumerate([v for _, _, v in sends]) if i == 0 or [x for _, _, x in sends][i - 1] != v]
    print(f'   C2S state-change edges: {len(edges)} ({edges[:30]})')
    rec_vals = [v for _, v in recvs]
    print(f'   S2C value sequence equals C2S dedup-by-change: {rec_vals == dedupe_edges([v for _, _, v in sends])}')
    for idx, v in resets:
        print(f'   0x27 reset @#{idx}: {[fmt_val(x) for x in v]}')


def runlength(vals):
    out, prev, n = [], None, 0
    for v in vals:
        if v == prev:
            n += 1
        else:
            if prev is not None:
                out.append((prev, n))
            prev, n = v, 1
    if prev is not None:
        out.append((prev, n))
    return out


def dedupe_edges(vals):
    out, prev = [], object()
    for v in vals:
        if v != prev:
            out.append(v)
            prev = v
    return out


def cmd_views(s2c_path):
    frames = load(s2c_path)
    print('== 0x15 create_view (view, cap, propCount) — u16+u16+u16:')
    for fr in frames:
        if fr and fr[0] == 0x15 and len(fr) >= 7:
            view, cap, props = struct.unpack_from('<HHH', fr, 1)
            print(f'   view={view:<4} cap={cap:<6} props={props}')
    vh = Counter()
    for fr in frames:
        if fr and fr[0] == 0x18 and len(fr) >= 3:
            vh[struct.unpack_from('<H', fr, 1)[0]] += 1
    print('== 0x18 view_add histogram:', dict(sorted(vh.items())))
    print('== 0x11 record_add rows (objId, recView):')
    rh, names = Counter(), Counter()
    for fr in frames:
        if fr and fr[0] == 0x11 and len(fr) >= 9:
            obj = struct.unpack_from('<I', fr, 1)[0]
            rv = struct.unpack_from('<I', fr, 5)[0]
            rh[(obj, rv)] += 1
            m = find_ascii(fr)
            if m:
                names[m] += 1
    for (obj, rv), cnt in rh.most_common(10):
        print(f'   obj={obj:<6} recView=0x{rv:08X} x{cnt}')
    print('   row-name tokens:', dict(names.most_common(15)))
    print('== 0x13 record_grid rows (objId, recView, rec, row, col, val):')
    for fr in frames:
        if fr and fr[0] == 0x13 and len(fr) >= 17:
            obj = struct.unpack_from('<I', fr, 1)[0]
            rv = struct.unpack_from('<I', fr, 5)[0]
            rec, row, col = struct.unpack_from('<HHH', fr, 9)
            print(f'   obj={obj} recView=0x{rv:08X} rec={rec} row={row} col={col} tail={fr[15:].hex()}')


def find_ascii(fr):
    import re
    m = re.search(rb'[a-z][a-z0-9_]{3,}', fr)
    return m.group().decode('ascii') if m else None


def cmd_customs(path, direction):
    frames = load(path)
    print(f'== {direction} custom frames ({path}):')
    if direction == 'c2s':
        for idx, seq, mid, vals in c2s_customs(frames):
            print(f'   #{idx:<4} seq={seq:<5} msgId={mid:<6} {[fmt_val(v) for v in vals[1:7]]}')
    else:
        for idx, op, mid, vals in s2c_customs(frames):
            print(f'   #{idx:<4} op=0x{op:02X} msgId={mid:<6} {[fmt_val(v) for v in vals[1:7]]}')


def main():
    cmd = sys.argv[1]
    if cmd == 'overview':
        cmd_overview(sys.argv[2], sys.argv[3])
    elif cmd == 'table09':
        cmd_table09(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif cmd == 'parry':
        cmd_parry(sys.argv[2], sys.argv[3])
    elif cmd == 'views':
        cmd_views(sys.argv[2])
    elif cmd == 'customs':
        cmd_customs(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
