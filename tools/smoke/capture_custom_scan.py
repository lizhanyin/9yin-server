# -*- coding: utf-8 -*-
"""抓包 custom 消息扫描:解 c2s/s2c 流,列 custom 帧的 msgId 与方向、时间序。

用法: python capture_custom_scan.py <session_prefix>   # e.g. 127-0-0-1-60310
"""
import importlib.util
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
decode_frames = _SC.decode_frames
op = _SC.op

CAPDIR = r"F:/Game/Online/9yin/9yin_server1/captures/local"


def custom_msgid_s2c(frame):
    """下行 0x1E/0x27 custom:skill_cast_verify 同款 msgId 提取(frame[3]==2, [4:8] int)。"""
    if (frame[0] & 0xFF) in (0x1E, 0x27) and len(frame) >= 8 and frame[3] == 2:
        return struct.unpack("<i", frame[4:8])[0]
    return None


def parse_custom_values(frame):
    """尽力解 custom 值区:tag 流 01/02/04/06/07(参照 CustomC2S/Values)。"""
    vals = []
    i = 8
    while i < len(frame) and len(vals) < 8:
        tag = frame[i]
        try:
            if tag == 0x01:
                vals.append(("i8", frame[i + 1])); i += 2
            elif tag == 0x02:
                vals.append(("i32", struct.unpack("<i", frame[i + 1:i + 5])[0])); i += 5
            elif tag == 0x03:
                vals.append(("i64", struct.unpack("<q", frame[i + 1:i + 9])[0])); i += 9
            elif tag == 0x04:
                vals.append(("f32", round(struct.unpack("<f", frame[i + 1:i + 5])[0], 2))); i += 5
            elif tag == 0x06:
                n = struct.unpack("<I", frame[i + 1:i + 5])[0]
                raw = frame[i + 5:i + 5 + n]
                if len(raw) < n:
                    break
                vals.append(("str", raw[:max(0, n - 1)].decode("gbk", "replace"))); i += 5 + n
            elif tag == 0x07:
                n = struct.unpack("<I", frame[i + 1:i + 5])[0]
                raw = frame[i + 5:i + 5 + n]
                if len(raw) < n:
                    break
                vals.append(("wstr", raw[:max(0, n - 2)].decode("utf-16-le", "replace"))); i += 5 + n
            else:
                break
        except Exception:
            break
    return vals


def custom_msgid_c2s(frame):
    """上行 0x0A 信封:21 字节头 + u16 count + 值;首值 tag2 int = msgId。"""
    if (frame[0] & 0xFF) != 0x0A or len(frame) < 25:
        return None, []
    count = struct.unpack("<H", frame[21:23])[0]
    vals = []
    i = 23
    while i < len(frame) and len(vals) < count:
        tag = frame[i]
        try:
            if tag == 0x02:
                vals.append(struct.unpack("<i", frame[i + 1:i + 5])[0]); i += 5
            elif tag == 0x04:
                vals.append(round(struct.unpack("<f", frame[i + 1:i + 5])[0], 2)); i += 5
            elif tag == 0x06:
                n = struct.unpack("<I", frame[i + 1:i + 5])[0]
                raw = frame[i + 5:i + 5 + n]
                if len(raw) < n:
                    break
                vals.append(raw[:max(0, n - 1)].decode("gbk", "replace")); i += 5 + n
            elif tag == 0x07:
                n = struct.unpack("<I", frame[i + 1:i + 5])[0]
                raw = frame[i + 5:i + 5 + n]
                if len(raw) < n:
                    break
                vals.append(raw[:max(0, n - 2)].decode("utf-16-le", "replace")); i += 5 + n
            else:
                break
        except Exception:
            break
    if vals and isinstance(vals[0], int):
        return vals[0], vals[1:]
    return None, vals


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "127-0-0-1-60310"
    interesting = {99, 107, 705}
    for direction in ("c2s", "s2c"):
        path = os.path.join(CAPDIR, prefix + "_" + direction + ".bin")
        if not os.path.exists(path):
            continue
        raw = open(path, "rb").read()
        frames, _ = decode_frames(bytearray(raw))
        print("=== %s frames=%d ===" % (direction, len(frames)))
        seq = 0
        hits = 0
        for f in frames:
            seq += 1
            if direction == "s2c":
                mid = custom_msgid_s2c(f)
                if mid in interesting:
                    vals = parse_custom_values(f)
                    body = ""
                    for t, v in vals:
                        if t == "str" and len(str(v)) > 40:
                            body += " str(len=%d):%r...," % (len(str(v)), str(v)[:40])
                        else:
                            body += " %s=%s," % (t, v)
                    print("  [%d] S2C custom %d len=%d %s" % (seq, mid, len(f), body))
                    hits += 1
            else:
                mid, rest = custom_msgid_c2s(f)
                if mid in interesting:
                    body = ""
                    for v in rest:
                        if isinstance(v, str) and len(v) > 40:
                            body += " str(len=%d):%r...," % (len(v), v[:40])
                        else:
                            body += " %r," % (v,)
                    print("  [%d] C2S custom %s len=%d rest=[%s]" % (seq, mid, len(f), body))
                    hits += 1
        if hits == 0:
            print("  (no %s custom frames)" % sorted(interesting))


if __name__ == "__main__":
    main()
