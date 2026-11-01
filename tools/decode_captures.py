# -*- coding: utf-8 -*-
"""Decode F-drive captures/local s2c bins (XOR CC3B6DFA + EE escape) and search Fsbd."""
import glob
import os
import sys

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])


def unescape(block):
    out = bytearray()
    j = 0
    while j < len(block):
        if block[j] == 0xEE:
            if j + 1 >= len(block):
                return None  # truncated
            if block[j + 1] == 0x00:
                out.append(0xEE)
                j += 2
                continue
            return None  # bad escape
        out.append(block[j])
        j += 1
    return bytes(out)


def xor(data):
    return bytes(b ^ KEY[i % 4] for i, b in enumerate(data))


def decode_stream(buf):
    frames = []
    i = 0
    start = 0
    while i < len(buf):
        if buf[i] == 0xEE and i + 1 < len(buf):
            if buf[i + 1] == 0x00:
                i += 2
                continue
            if buf[i + 1] == 0xEE:
                body = unescape(buf[start:i])
                if body is None:
                    return frames, False
                frames.append(xor(body))
                i += 2
                start = i
                continue
            return frames, False  # bad escape -> wrong format assumption
        i += 1
    return frames, True


def main():
    files = sorted(glob.glob('F:/Game/Online/9yin/9yin_server1/captures/local/*_s2c.bin'),
                   key=os.path.getmtime, reverse=True)
    for f in files[:8]:
        data = open(f, 'rb').read()
        frames, ok = decode_stream(data)
        blob = b''.join(frames)
        fsbd = blob.count(b'Fsbd')
        others = {p: blob.count(p) for p in [b'stand', b'Npc_', b'npc\\']}
        print(f"{os.path.basename(f)} bytes={len(data)} frames={len(frames)} clean={ok} "
              f"Fsbd={fsbd} {others} first_ops={[hex(fr[0]) for fr in frames[:6] if fr]}")


if __name__ == '__main__':
    main()
