# -*- coding: utf-8 -*-
"""从 F 盘抓包解出的 S2C 流中提取指定属性块的值(按 0x09 官方表解 0x10/0x0D 属性行)。

用法: python -I faculty_probe.py <s2c.bin> [idx_lo idx_hi]
默认打印内修块 idx 750-780(FacultyState/Name/CurLevel/CurPlaceList/FillSpeed 等)。
0x10 布局(spec/opcodes.json): opcode | is_view u8 | identLow u32 | identHigh u32 |
propCount u16 | (idx u16 + 按 0x09 表类型的值)*
0x0D 布局: opcode | identLow u32 | identHigh u32 | xyz f32*3 | orient f32 |
motion f32*9 | propCount u16 @61 | 属性行 @63
表类型: 1=u8 2=u16 3=u32 4=u64 5=f32 7=latin str(u32len) 8=utf16 str 9=obj u64
例: CurPlaceList(idx 766, type7)='6' 即修炼地点"市"(ui_faculty_place_N)。
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture_deep import load  # noqa: E402

TABLE = ('G:/pojie/ai/9yin-server/nineyin/src/main/resources/'
         'nineyin/protocol/official_property_table.txt')


def load_table():
    entries = []
    for line in open(TABLE, encoding='ascii'):
        parts = line.strip().split('\t')
        if len(parts) == 3:
            entries.append((int(parts[0]), parts[1], int(parts[2])))
    return entries


class Rd:
    def __init__(self, d, o=0):
        self.d, self.o = d, o

    def val(self, t):
        d, o = self.d, self.o
        if t == 1:
            v = d[o]; self.o += 1
        elif t == 2:
            v = struct.unpack_from('<H', d, o)[0]; self.o += 2
        elif t == 3:
            v = struct.unpack_from('<I', d, o)[0]; self.o += 4
        elif t == 4:
            v = struct.unpack_from('<Q', d, o)[0]; self.o += 8
        elif t == 5:
            v = round(struct.unpack_from('<f', d, o)[0], 3); self.o += 4
        elif t == 7:
            n = struct.unpack_from('<I', d, o)[0]; self.o += 4
            v = d[self.o:self.o + n].rstrip(b'\x00').decode('latin-1'); self.o += n
        elif t == 8:
            n = struct.unpack_from('<I', d, o)[0]; self.o += 4
            v = d[self.o:self.o + n].decode('utf-16-le', 'replace').rstrip('\x00'); self.o += n
        elif t == 9:
            v = struct.unpack_from('<Q', d, o)[0]; self.o += 8
        else:
            raise ValueError(f'type {t}')
        return v


def main(path, lo=750, hi=780):
    by_idx = {e[0]: e for e in load_table()}
    frames = load(path)
    for i, fr in enumerate(frames):
        if not fr:
            continue
        try:
            if fr[0] == 0x10:
                is_view = fr[1]
                ident_lo, ident_hi = struct.unpack_from('<II', fr, 2)
                cnt = struct.unpack_from('<H', fr, 10)[0]
                rd = Rd(fr, 12)
                rows = []
                for _ in range(cnt):
                    idx = rd.val(2)
                    e = by_idx.get(idx)
                    v = rd.val(e[2] if e else 3)
                    if lo <= idx <= hi:
                        rows.append((idx, e[1] if e else '?', v))
                if rows:
                    print(f'#{i} 0x10 {"VIEW" if is_view else "OBJ"} '
                          f'ident={ident_lo:#x}/{ident_hi:#x} props={cnt}')
                    for idx, name, v in rows:
                        print(f'     {idx:<4} {name:<22} = {v!r}')
            elif fr[0] == 0x0D and len(fr) > 63:
                ident_lo, ident_hi = struct.unpack_from('<II', fr, 1)
                cnt = struct.unpack_from('<H', fr, 61)[0]
                rd = Rd(fr, 63)
                rows = []
                for _ in range(cnt):
                    idx = rd.val(2)
                    e = by_idx.get(idx)
                    v = rd.val(e[2] if e else 3)
                    if lo <= idx <= hi:
                        rows.append((idx, e[1] if e else '?', v))
                if rows:
                    print(f'#{i} 0x0D ADD ident={ident_lo:#x}/{ident_hi:#x} props={cnt}')
                    for idx, name, v in rows:
                        print(f'     {idx:<4} {name:<22} = {v!r}')
        except Exception as exc:
            print(f'#{i} 0x{fr[0]:02X} parse fail: {exc}')


if __name__ == '__main__':
    main(sys.argv[1],
         int(sys.argv[2]) if len(sys.argv) > 2 else 750,
         int(sys.argv[3]) if len(sys.argv) > 3 else 780)
