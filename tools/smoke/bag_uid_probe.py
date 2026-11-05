# -*- coding: utf-8 -*-
"""官服/参考服抓包 0x18 view_add 背包物品 UniqueID 对照探针。

问题：内功快捷栏放武器后切走再切回，图标变成别的物品。客户端 item 快捷方式按
UniqueID 经 FindItemInBagByUID 解析（shortcut_game.lua），UID 是否随物品实例稳定
决定图标是否会漂移。本探针解 0x09 属性表 + 0x18 view_add，输出背包页
(view 121/2/122/3) 与纸娃娃 (view 1) 每行的 ConfigID/UniqueID/槽位，并检测
同一 UniqueID 是否出现在多个槽位（=实例稳定）或同槽位复用不同 UID（=槽位寻址）。

用法: python -I bag_uid_probe.py <s2c.bin>
"""
import struct
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def decode_frames(buf):
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


def parse_09(frame):
    count = struct.unpack_from('<H', frame, 1)[0]
    entries, off = [], 3
    for _ in range(count):
        end = frame.index(b'\x00', off)
        entries.append((frame[off:end].decode('latin-1'), frame[end + 1]))
        off = end + 2
    return entries


def prop_names_index(entries):
    return {i: (name, typ) for i, (name, typ) in enumerate(entries)}


def read_prop(frame, off, typ):
    """按 0x09 表线型读值：返回 (value, new_off) 或 (None, off)。"""
    try:
        if typ == 1:  # byte
            return frame[off], off + 1
        if typ == 2:  # word
            return struct.unpack_from('<H', frame, off)[0], off + 2
        if typ == 3:  # int32
            return struct.unpack_from('<I', frame, off)[0], off + 4
        if typ == 4:  # int64
            return struct.unpack_from('<Q', frame, off)[0], off + 8
        if typ == 5:  # float32
            return struct.unpack_from('<f', frame, off)[0], off + 4
        if typ in (6, 7, 8):  # text / wide / raw
            n = struct.unpack_from('<I', frame, off)[0]
            data = frame[off + 4:off + 4 + n]
            off = off + 4 + n
            if typ == 6:
                return data.rstrip(b'\x00').decode('utf-8', 'replace'), off
            if typ == 7:
                try:
                    return data.decode('utf-16-le', 'replace').rstrip('\x00'), off
                except Exception:
                    return repr(data), off
            return data.hex(), off
    except (struct.error, IndexError):
        return None, off
    return None, off


def main(path):
    frames = decode_frames(open(path, 'rb').read())
    table = None
    for fr in frames:
        if fr and fr[0] == 0x09:
            table = parse_09(fr)
            break
    if not table:
        print('no 0x09 table')
        return
    by_index = prop_names_index(table)
    idx_of = {}
    for i, (name, _typ) in by_index.items():
        idx_of.setdefault(name, i)

    rows = []  # (frame_idx, view, objIdx, config, uid)
    for fi, fr in enumerate(frames):
        if not fr or fr[0] != 0x18 or len(fr) < 7:
            continue
        view, obj, count = struct.unpack_from('<HHH', fr, 1)
        off = 7
        config = uid = None
        for _ in range(count):
            if off + 2 > len(fr):
                break
            pidx = struct.unpack_from('<H', fr, off)[0]
            off += 2
            name, typ = by_index.get(pidx, ('?', 0))
            val, off = read_prop(fr, off, typ)
            if val is None:
                break
            if name == 'ConfigID':
                config = val
            elif name == 'UniqueID':
                uid = val
        if config is not None or uid is not None:
            rows.append((fi, view, obj, config, uid))

    print(f'== {path}: 0x18 rows with ConfigID/UniqueID = {len(rows)}')
    uid_slot = {}
    slot_uid = {}
    for fi, view, obj, config, uid in rows:
        tag = 'doll' if view == 1 else ('bag' if view in (121, 2, 122, 3, 123, 125) else f'v{view}')
        print(f'   #{fi:<5} {tag:<5} view={view:<4} slot={obj:<3} ConfigID={config} UID={uid}')
        if uid:
            uid_slot.setdefault(uid, set()).add((view, obj))
            slot_uid.setdefault((view, obj), set()).add(uid)
    moved = {u: s for u, s in uid_slot.items() if len(s) > 1}
    reuid = {s: u for s, u in slot_uid.items() if len(u) > 1}
    print(f'== UniqueID 出现在多个槽位(实例跨槽稳定): {len(moved)}')
    for u, s in list(moved.items())[:10]:
        print(f'   {u}: {sorted(s)}')
    print(f'== 同槽位出现多个 UniqueID(槽位复用): {len(reuid)}')
    for s, u in list(reuid.items())[:10]:
        print(f'   {s}: {sorted(u)}')


if __name__ == '__main__':
    main(sys.argv[1])
