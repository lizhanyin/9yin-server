# -*- coding: utf-8 -*-
"""战斗一期取证：62433 技能抓包中 0x13/0x2D/msg24/msg169/cooldown 行/0x10 全量帧的字节布局钉死。

用法:
  python -I combat_forensics.py <s2c.bin> [c2s.bin]

产出（stdout）:
  1. 0x13 record_grid 帧全 hex + 多种偏移假设解析
  2. 0x2D 受击特效帧全 hex（前 5 帧）
  3. msg24 技能伤害形态完整值（前 3 帧）+ msg169 技能 buff 形态完整值
  4. cooldown_rec 0x11 行全 hex + 官方 schema [3,4,4,2] 解析验证
  5. msg20 前后的玩家 0x10 全量帧属性名清单（按 0x09 表索引映射）
  6. c2s 0x07 目标选择帧全 hex（前 3 帧）
"""
import importlib.util
import os
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)
load, parse_09, parse_tvarlist = _CD.load, _CD.parse_09, _CD.parse_tvarlist
s2c_customs, c2s_customs = _CD.s2c_customs, _CD.c2s_customs


def prop_type_reader(frame, off, ptype):
    """按 0x09 表线型读一个值，返回 (python 值, 新偏移)。"""
    if ptype == 1:
        return frame[off], off + 1
    if ptype == 2:
        return struct.unpack_from('<H', frame, off)[0], off + 2
    if ptype == 3:
        return struct.unpack_from('<i', frame, off)[0], off + 4
    if ptype == 4:
        return struct.unpack_from('<q', frame, off)[0], off + 8
    if ptype == 5:
        return round(struct.unpack_from('<f', frame, off)[0], 3), off + 4
    if ptype == 6:
        return round(struct.unpack_from('<d', frame, off)[0], 3), off + 8
    if ptype == 7:
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        raw = frame[off:off + n]
        return raw.rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', frame, off)[0]
        off += 4
        raw = frame[off:off + n]
        return raw.decode('utf-16-le', 'replace'), off + n
    if ptype == 9:
        oid, owner = struct.unpack_from('<II', frame, off)
        return f'obj({oid:#x},{owner:#x})', off + 8
    raise ValueError(ptype)


def main():
    s2c_path = sys.argv[1]
    c2s_path = sys.argv[2] if len(sys.argv) > 2 else None
    frames = load(s2c_path)

    # 0x09 协商表 → index→(name,type)
    table_frame = next(f for f in frames if f and f[0] == 0x09)
    count, entries, _ = parse_09(table_frame)
    print(f'== 0x09 negotiated property table: {count} entries')
    rec_names = {}  # record index → name 需另从 0x0A 帧取
    table_frame_records = next(f for f in frames if f and f[0] == 0x0A and len(f) > 3)
    rec_off, rec_count = 3, struct.unpack_from('<H', table_frame_records, 1)[0]
    rec_schemas = []
    for _ in range(rec_count):
        end = table_frame_records.index(b'\x00', rec_off)
        name = table_frame_records[rec_off:end].decode('latin-1')
        cols = struct.unpack_from('<H', table_frame_records, end + 1)[0]
        types = list(table_frame_records[end + 3:end + 3 + cols])
        rec_schemas.append((name, types))
        rec_off = end + 3 + cols
    print(f'== 0x0A record table: {rec_count} schemas')
    for i, (name, types) in enumerate(rec_schemas):
        if name in ('cooldown_rec', 'active_skill_rec', 'fight_relation_rec'):
            print(f'   rec[{i}] {name} cols={types}')
    rec_names = {i: n for i, (n, _) in enumerate(rec_schemas)}

    # ---- 1. 0x13 record_grid ----
    grids = [(i, f) for i, f in enumerate(frames) if f and f[0] == 0x13]
    print(f'\n== 0x13 record_grid x{len(grids)}')
    hist13 = {}
    for i, fr in grids:
        rec_idx = struct.unpack_from('<H', fr, 10)[0]
        hist13.setdefault(rec_names.get(rec_idx, rec_idx), []).append((i, fr))
    for name, items in sorted(hist13.items(), key=lambda kv: -len(kv[1])):
        print(f'   -- {name} x{len(items)}')
        for i, fr in items[:4]:
            rec, row, col = struct.unpack_from('<HHH', fr, 10)
            print(f'      #{i} len={len(fr)} rec={rec} row={row} col={col} value@16={fr[16:].hex()}')
    if grids:
        fr = grids[0][1]
        print('   offset probes @2:', struct.unpack_from('<IIHHH', fr, 2) if len(fr) >= 16 else '-')

    # ---- 2. 0x2D ----
    hits = [(i, f) for i, f in enumerate(frames) if f and f[0] == 0x2D]
    print(f'\n== 0x2D x{len(hits)}')
    for i, fr in hits[:5]:
        print(f'   #{i} len={len(fr)} hex={fr.hex()}')
    # 与紧邻的前一个 msg24 伤害值对照，找相关性
    msg24_damage = {}
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 24 and len(vals) > 3:
            msg24_damage[idx] = vals[3]
    print('   #idx  victim@3  f@1 f@2  u16@11 u16@13 u32@15 u16@19 u32@21 | prev msg24 dmg')
    for i, fr in hits[:24]:
        victim = struct.unpack_from('<I', fr, 3)[0]
        f1, f2 = fr[1], fr[2]
        a, b = struct.unpack_from('<HH', fr, 11)
        c = struct.unpack_from('<I', fr, 15)[0]
        d = struct.unpack_from('<H', fr, 19)[0]
        e = struct.unpack_from('<I', fr, 21)[0]
        prev = next((msg24_damage[j] for j in range(i - 1, max(0, i - 6), -1) if j in msg24_damage), '-')
        print(f'   #{i:<6} {victim:#010x}  {f1:<3} {f2:<3} {a:<7} {b:<7} {c:<7} {d:<7} {e:<7} | {prev}')

    # ---- 3. msg24 / msg169 完整值 ----
    print('\n== msg24 full values (skill-damage form):')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 24 and shown < 3:
            print(f'   #{idx} op=0x{op:02X} n={len(vals)}')
            for v in vals:
                print(f'      {v!r}')
            shown += 1
    print('\n== msg24 npc-attack form (third value int 7):')
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 24 and len(vals) > 3 and vals[2] == 7:
            print(f'   #{idx} n={len(vals)}')
            for v in vals:
                print(f'      {v!r}')
            break
    print('\n== msg169 skill-buff form (second value string buf_):')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 169 and len(vals) > 2 and isinstance(vals[2], str) and vals[2].startswith('buf_') and shown < 2:
            print(f'   #{idx} op=0x{op:02X} n={len(vals)}')
            for v in vals:
                print(f'      {v!r}')
            shown += 1
    print('\n== msg18 values x3:')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 18 and shown < 3:
            print(f'   #{idx} op=0x{op:02X} {[repr(v) for v in vals]}')
            shown += 1
    print('\n== msg205 values x2:')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 205 and shown < 2:
            print(f'   #{idx} op=0x{op:02X} {[repr(v) for v in vals]}')
            shown += 1
    print('\n== msg337 values x2:')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 337 and shown < 2:
            print(f'   #{idx} op=0x{op:02X} {[repr(v) for v in vals]}')
            shown += 1
    print('\n== msg20 values x3:')
    shown = 0
    for idx, op, mid, vals in s2c_customs(frames):
        if mid == 20 and shown < 3:
            print(f'   #{idx} op=0x{op:02X} {[repr(v) for v in vals]}')
            shown += 1

    # ---- 4. cooldown 0x11 行全 hex 验证 ----
    cd_rows = []
    for i, f in enumerate(frames):
        if f and f[0] == 0x11 and len(f) >= 12:
            rec_idx = struct.unpack_from('<H', f, 10)[0]
            if rec_names.get(rec_idx) == 'cooldown_rec':
                cd_rows.append((i, f))
    print(f'\n== cooldown_rec 0x11 rows x{len(cd_rows)}')
    for i, fr in cd_rows[:4]:
        obj = struct.unpack_from('<I', fr, 2)[0]
        owner = struct.unpack_from('<I', fr, 6)[0]
        rec, src, cnt = struct.unpack_from('<HHH', fr, 10)
        cat = struct.unpack_from('<i', fr, 16)[0]
        start, end = struct.unpack_from('<qq', fr, 20)
        team = struct.unpack_from('<H', fr, 36)[0]
        print(f'   #{i} len={len(fr)} obj={obj:#x} owner={owner:#x} rec={rec} src={src} cnt={cnt}'
              f' cat={cat} start={start} end={end} dur={end - start}ms team={team:#x}')
        print(f'      hex={fr.hex()}')
    act_rows = []
    for i, f in enumerate(frames):
        if f and f[0] == 0x11 and len(f) >= 12:
            rec_idx = struct.unpack_from('<H', f, 10)[0]
            if rec_names.get(rec_idx) == 'active_skill_rec':
                act_rows.append((i, f))
    print(f'== active_skill_rec rows x{len(act_rows)}')
    for i, fr in act_rows[:2]:
        print(f'   #{i} len={len(fr)} hex={fr.hex()}')
        n = struct.unpack_from('<I', fr, 16)[0]
        print(f'      string len(u32@16)={n} text={fr[20:20 + n].rstrip(bytes([0])).decode("gbk", "replace")!r}')
    # 0x12 删行帧
    dels = [(i, f) for i, f in enumerate(frames) if f and f[0] == 0x12]
    print(f'== 0x12 del-row x{len(dels)}')
    for i, fr in dels[:3]:
        obj = struct.unpack_from('<I', fr, 2)[0]
        rec, row = struct.unpack_from('<HH', fr, 10)
        print(f'   #{i} len={len(fr)} obj={obj:#x} rec={rec}({rec_names.get(rec)}) row={row} hex={fr.hex()}')

    # ---- 5. msg20 前的玩家 0x10 全量帧属性清单 ----
    print('\n== full player 0x10 right before msg20:')
    customs = list(s2c_customs(frames))
    msg20_idx = next(i for i, op, mid, _ in customs if mid == 20)
    for idx in range(msg20_idx - 6, msg20_idx):
        fr = frames[idx]
        if not fr or fr[0] != 0x10:
            continue
        is_view = fr[1]
        obj, owner, n = struct.unpack_from('<IIH', fr, 2)
        print(f'   #{idx} isView={is_view} obj={obj:#x} owner={owner:#x} props={n} len={len(fr)}')
        if n > 40:
            off = 12
            names = []
            for _ in range(min(n, 200)):
                pidx = struct.unpack_from('<H', fr, off)[0]
                off += 2
                name, ptype = entries[pidx] if pidx < len(entries) else ('?', 0)
                _, off = prop_type_reader(fr, off, ptype)
                names.append(f'{pidx}:{name}')
            print(f'      first 40: {names[:40]}')
            print(f'      last 20: {names[-20:]}')

    # ---- 6. c2s 0x07 ----
    if c2s_path:
        c2s = load(c2s_path)
        objs = [(i, f) for i, f in enumerate(c2s) if f and f[0] == 0x07]
        print(f'\n== c2s 0x07 x{len(objs)}')
        for i, fr in objs[:3]:
            print(f'   #{i} len={len(fr)} hex={fr.hex()}')
            if len(fr) >= 33:
                seq = struct.unpack_from('<I', fr, 17)[0]
                oid, owner = struct.unpack_from('<II', fr, 21)
                func = struct.unpack_from('<i', fr, 29)[0]
                print(f'      seq={seq} obj={oid:#x} owner={owner:#x} func={func}')
        sels = [(i, s, v) for i, s, m, v in c2s_customs(c2s) if m == 431]
        print(f'== c2s msg431 x{len(sels)}')
        for i, s, v in sels[:3]:
            print(f'   #{i} seq={s} vals={v}')


if __name__ == '__main__':
    main()
