# -*- coding: utf-8 -*-
"""Parse Go pclntab from the menu2 exe and list function names -> VAs.

Go 1.18+ pclntab header: magic u32 (fffffff1/fa/f0/fb), pad u16, minLC u8,
ptrSize u8, nfunc int, nfiles uint, textStart uintptr, funcnameOffset,
cuOffset, filetabOffset, pctabOffset, pclnOffset (all uintptr-relative to header).
"""
import struct
import sys

EXE = r"F:/Game/Online/9yin/9yin_server1/build/9yin-game-native-menu2.exe"
data = open(EXE, "rb").read()

e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
nsec = struct.unpack_from("<H", data, e_lfanew + 6)[0]
opt_size = struct.unpack_from("<H", data, e_lfanew + 20)[0]
IMAGEBASE = 0x400000
secs = []
off = e_lfanew + 24 + opt_size
for _ in range(nsec):
    name = data[off:off + 8].rstrip(b"\x00").decode("ascii", "replace")
    vsz, va, rsz, roff = struct.unpack_from("<IIII", data, off + 8)
    secs.append((name, va, vsz, roff, rsz))
    off += 40


def va2off(v):
    r = v - IMAGEBASE
    for name, va, vsz, roff, rsz in secs:
        if va <= r < va + max(vsz, rsz):
            return roff + (r - va)
    return None


def find_pclntab():
    # pclntab 位于 .rdata/.data；用 nfunc/nfiles 合理性校验排除伪 magic
    ranges = []
    for name, va, vsz, roff, rsz in secs:
        if name in (".rdata", ".data"):
            ranges.append((roff, roff + rsz))
    for magic in (b"\xf1\xff\xff\xff", b"\xfa\xff\xff\xff", b"\xf0\xff\xff\xff", b"\xfb\xff\xff\xff"):
        for lo, hi in ranges:
            start = lo
            while True:
                i = data.find(magic, start, hi)
                if i < 0:
                    break
                ptr = data[i + 7]
                if ptr == 8:
                    nfunc = struct.unpack_from("<q", data, i + 8)[0]
                    if 0 < nfunc < 10_000_000:
                        return i
                start = i + 1
    return None


def parse():
    off = find_pclntab()
    if off is None:
        print("no pclntab")
        return
    magic = struct.unpack_from("<I", data, off)[0]
    minLC, ptrSize = data[off + 6], data[off + 7]
    fmt = "<Q" if ptrSize == 8 else "<I"
    nfunc, nfiles = struct.unpack_from("<" + ("q" if ptrSize == 8 else "i") + "Q", data, off + 8)[0:2] if ptrSize == 8 else struct.unpack_from("<iI", data, off + 8)
    textStart = struct.unpack_from(fmt, data, off + 24)[0]
    funcnameOff = struct.unpack_from(fmt, data, off + 32)[0]
    cuOff = struct.unpack_from(fmt, data, off + 40)[0]
    filetabOff = struct.unpack_from(fmt, data, off + 48)[0]
    pctabOff = struct.unpack_from(fmt, data, off + 56)[0]
    pclnOff = struct.unpack_from(fmt, data, off + 64)[0]
    print("pclntab@0x%x magic=0x%x nfunc=%d textStart=0x%x" % (off, magic, nfunc, textStart))
    ft = off + pclnOff
    funcs = []
    for i in range(nfunc):
        entryoff, funcoff = struct.unpack_from("<II", data, ft + i * 8)
        nameoff = struct.unpack_from("<I", data, off + pclnOff + funcoff + 4)[0]
        nstart = off + funcnameOff + nameoff
        nend = data.find(b"\x00", nstart)
        name = data[nstart:nend].decode("utf-8", "replace")
        funcs.append((textStart + entryoff, name))
    return funcs


if __name__ == "__main__":
    funcs = parse()
    if not funcs:
        sys.exit(1)
    print("total funcs:", len(funcs))
    pats = sys.argv[1:] or ["eart", "elay", "ync", "Ping"]
    for p in pats:
        print("== pattern", p)
        for a, n in funcs:
            if p.lower() in n.lower():
                print("  0x%x  %s" % (a, n))
