# -*- coding: utf-8 -*-
"""扫描 F 盘参考服抓包：C2S custom msgId 99（SET_OPERATEMODE）与 S2C 0x10 属性帧 idx 223（OperateMode）。

用法: python -I scan_operate_mode.py <captures_dir>
帧解码复用 capture_deep.py 的 decode_frames/parse_tvarlist。
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture_deep import decode_frames, parse_tvarlist  # noqa: E402


def scan_custom(values, path, frame_no, direction):
    """values[0]==99 → 打印整帧值（parse_tvarlist 已拍平标签，返回解码值列表）。"""
    if not values or not isinstance(values[0], int) or values[0] != 99:
        return
    print(f"[{direction}] {os.path.basename(path)} frame#{frame_no} "
          f"len>21 msg99 values={values!r}")


def walk_custom_frames(frames, path, direction):
    for no, f in enumerate(frames):
        op = f[0]
        if op == 0x0A and len(f) >= 24:
            values = parse_tvarlist(f[21:])
            scan_custom(values, path, no, direction)
        elif op in (0x1E, 0x27) and len(f) >= 3:
            values = parse_tvarlist(f[1:])
            scan_custom(values, path, no, direction)


def load_types(table_path):
    """official_property_table.txt → {index: type_code}。"""
    types = {}
    for line in open(table_path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3 and parts[0].isdigit():
            types[int(parts[0])] = int(parts[2])
    return types


TYPES = {}


def prop_size(type_code, f, off):
    """按线型返回值字节数（变长串返回 (n, size)）。解析越界返回 None。"""
    if type_code in (1,):
        return 1
    if type_code in (2,):
        return 2
    if type_code in (3, 5):
        return 4
    if type_code in (4, 9):
        return 8
    if type_code in (7, 8):
        if off + 4 > len(f):
            return None
        n = struct.unpack_from("<I", f, off)[0]
        if n <= 0 or off + 4 + n > len(f):
            return None
        return 4 + n
    return None


def walk_prop_frames(frames, path):
    """0x10 | u8 isView | u32 obj | u32 owner | u16 count | (u16 idx + typed)*。

    用官服 0x09 表线型逐属性推进，多属性大帧也能扫到 223/198。
    """
    for no, f in enumerate(frames):
        if f[0] != 0x10 or len(f) < 12:
            continue
        obj = struct.unpack_from("<I", f, 2)[0]
        owner = struct.unpack_from("<I", f, 6)[0]
        count = struct.unpack_from("<H", f, 10)[0]
        off = 12
        hits = []
        for _ in range(count):
            if off + 2 > len(f):
                break
            idx = struct.unpack_from("<H", f, off)[0]
            off += 2
            type_code = TYPES.get(idx)
            size = prop_size(type_code, f, off) if type_code is not None else None
            if size is None:
                break  # 表外索引/越界：放弃本帧剩余
            if idx == 223 and type_code == 3:
                v = struct.unpack_from("<i", f, off)[0]
                hits.append((idx, f"OperateMode int32={v}"))
            elif idx == 198 and type_code == 7:
                n = struct.unpack_from("<I", f, off)[0]
                raw = f[off + 4:off + 4 + max(0, n - 1)]
                hits.append((idx, f"ShortcutKey str({n})={raw[:48]!r}"))
            off += size
        if hits:
            print(f"[s2c-prop] {os.path.basename(path)} frame#{no} "
                  f"obj={obj} owner={owner} count={count} hits={hits}")


def main():
    global TYPES
    table = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "..", "..", "nineyin", "src", "main", "resources",
                         "nineyin", "protocol", "official_property_table.txt")
    TYPES = load_types(table)
    root = sys.argv[1]
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not os.path.isfile(path):
            continue
        frames = decode_frames(open(path, "rb").read())
        if name.endswith("_c2s.bin"):
            walk_custom_frames(frames, path, "c2s")
        elif name.endswith("_s2c.bin"):
            walk_custom_frames(frames, path, "s2c")
            walk_prop_frames(frames, path)


if __name__ == "__main__":
    main()
