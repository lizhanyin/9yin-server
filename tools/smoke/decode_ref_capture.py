# -*- coding: utf-8 -*-
"""解码参考抓包：0x09 属性表(index=顺序) → 各视图物品帧逐属性带名还原。"""
import struct

CAP = 'C:/Users/lzy/AppData/Local/Temp/ref_capture.bin'


def load_frames():
    frames = []
    with open(CAP, 'rb') as fh:
        while True:
            head = fh.read(6)
            if len(head) < 6:
                break
            ln, idx = struct.unpack('<IH', head)
            frames.append((idx, fh.read(ln)))
    return frames


def parse_property_table(frame):
    """0x09: u16 count + count×(name NUL + u8 type)。索引=行号（从 0）。"""
    count = struct.unpack('<H', frame[1:3])[0]
    pos = 3
    table = {}
    for i in range(count):
        end = frame.index(b'\x00', pos)
        name = frame[pos:end].decode('ascii', errors='replace')
        typ = frame[end + 1]
        table[i] = (name, typ)
        pos = end + 2
    return table


TYPE_READERS = {
    1: ('u8', 1), 2: ('i16', 2), 3: ('i32', 4), 4: ('i64', 8),
    5: ('f32', 4), 6: ('f64', 8), 7: ('str', None), 8: ('oid', 8),
}


def decode_view_add(frame, table):
    """0x18: u16 view + u16 index + u16 count + count×(u16 idx + value by table type)。"""
    view, obj, n = struct.unpack('<HHH', frame[1:7])
    pos = 7
    props = []
    for _ in range(n):
        pidx = struct.unpack('<H', frame[pos:pos + 2])[0]
        pos += 2
        name, typ = table.get(pidx, (f'?{pidx}', 3))
        kind, size = TYPE_READERS.get(typ, ('i32', 4))
        if kind == 'str':
            slen = struct.unpack('<I', frame[pos:pos + 4])[0]
            val = frame[pos + 4:pos + 4 + slen - 1].decode('utf-8', errors='replace')
            pos += 4 + slen
        elif kind == 'u8':
            val = frame[pos]
            pos += 1
        elif kind == 'f32':
            val = struct.unpack('<f', frame[pos:pos + 4])[0]
            pos += 4
        elif kind == 'f64':
            val = struct.unpack('<d', frame[pos:pos + 8])[0]
            pos += 8
        elif kind == 'i16':
            val = struct.unpack('<h', frame[pos:pos + 2])[0]
            pos += 2
        elif kind == 'i64':
            val = struct.unpack('<q', frame[pos:pos + 8])[0]
            pos += 8
        elif kind == 'oid':
            val = struct.unpack('<II', frame[pos:pos + 8])
            pos += 8
        else:
            val = struct.unpack('<i', frame[pos:pos + 4])[0]
            pos += 4
        props.append((pidx, name, val))
    if pos != len(frame):
        props.append((-1, f"_TRAILING_{len(frame)-pos}B", frame[pos:].hex()))
    return view, obj, props


def main():
    frames = load_frames()
    ptable = None
    for idx, f in frames:
        if f[0] == 0x09:
            ptable = parse_property_table(f)
            break
    print(f'property table: {len(ptable)} entries')
    for i in (105, 106, 110, 205, 340, 353, 354, 355, 356, 229, 230, 231, 232,
              276, 325, 336, 337, 235, 236, 234, 7, 204, 203, 176, 177, 208):
        if i in ptable:
            print(f'  [{i}] {ptable[i][0]} (type {ptable[i][1]})')

    by_view = {}
    for idx, f in frames:
        if f[0] != 0x18:
            continue
        view, obj, props = decode_view_add(f, ptable)
        by_view.setdefault(view, []).append((idx, obj, props))

    for view in (1, 2, 121, 3, 40):
        objs = by_view.get(view, [])
        print(f'\n=== view {view}: {len(objs)} objects ===')
        for idx, obj, props in objs[:4]:
            cfg = next((v for _, n, v in props if n == 'ConfigID'), '?')
            print(f'#{idx} obj={obj} cfg={cfg}')
            for pidx, name, val in props:
                print(f'    [{pidx}] {name} = {val!r}')


if __name__ == '__main__':
    main()
