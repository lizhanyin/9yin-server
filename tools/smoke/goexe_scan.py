# -*- coding: utf-8 -*-
"""Scan the Go server exe (9yin-game-native-menu2.exe) for heartbeat/delay reply logic.

1. locate strings, 2. find rip-relative lea refs in .text, 3. disassemble around
them with capstone, 4. annotate with COFF .symtab function names.
"""
import struct
import sys

import capstone

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


def off2va(o):
    for name, va, vsz, roff, rsz in secs:
        if roff <= o < roff + rsz:
            return IMAGEBASE + va + (o - roff)
    return None


def va2off(v):
    r = v - IMAGEBASE
    for name, va, vsz, roff, rsz in secs:
        if va <= r < va + max(vsz, rsz):
            return roff + (r - va)
    return None


# ---- COFF symbol table (function names) ----
def load_syms():
    for name, va, vsz, roff, rsz in secs:
        if name == ".symtab":
            break
    else:
        return {}
    strtab_off = roff + rsz
    if strtab_off + 4 > len(data):
        return {}
    strtab_size = struct.unpack_from("<I", data, strtab_off)[0]
    strtab = data[strtab_off + 4: strtab_off + 4 + strtab_size]
    syms = {}
    n = struct.unpack_from("<I", data, e_lfanew + 12)[0]  # NumberOfSymbols
    pos = roff
    count = 0
    while count < n:
        nm = data[pos:pos + 8]
        if nm[0:1] == b"\x00":
            so = struct.unpack_from("<I", nm, 4)[0]
            end = strtab.find(b"\x00", so)
            name = strtab[so:end].decode("utf-8", "replace")
        else:
            name = nm.rstrip(b"\x00").decode("ascii", "replace")
        value = struct.unpack_from("<I", data, pos + 8)[0]
        secnum = struct.unpack_from("<H", data, pos + 12)[0]
        sc = data[pos + 16]
        aux = data[pos + 17]
        if 1 <= secnum <= len(secs) and sc == 2 and value:  # FUNCTION symbol
            va = IMAGEBASE + secs[secnum - 1][1] + value
            syms.setdefault(va, name)
        pos += 18 * (1 + aux)
        count += 1 + aux
    return syms


def nearest_sym(syms, va):
    best = None
    for a, n in syms.items():
        if a <= va and (best is None or a > best[0]):
            best = (a, n)
    return best


# ---- find string refs ----
def find_refs(target_va):
    """all rip-relative lea/mov instructions in .text targeting target_va"""
    name, va, vsz, roff, rsz = next(s for s in secs if s[0] == ".text")
    text = data[roff:roff + rsz]
    refs = []
    # 48 8d xx disp32 (lea) and 48 8b xx disp32 (mov r,[rip+d])
    for i in range(len(text) - 7):
        b = text[i]
        if b != 0x48:
            continue
        op = text[i + 1]
        modrm = text[i + 2]
        if op in (0x8D, 0x8B) and (modrm & 0xC7) == 0x05:
            disp = struct.unpack_from("<i", text, i + 3)[0]
            insn_va = IMAGEBASE + va + i
            if insn_va + 7 + disp == target_va:
                refs.append(insn_va)
    return refs


def disasm_around(va, back=0x120, fwd=0x220, syms=None):
    start_off = va2off(va - back)
    code = data[start_off:start_off + back + fwd]
    base = va - back
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    md.detail = False
    out = []
    for insn in md.disasm(code, base):
        tag = ""
        if syms:
            s = nearest_sym(syms, insn.address)
            if s and s[0] == insn.address:
                tag = "\n<%s>:" % s[1]
        out.append("%s  %-8s %s%s" % (hex(insn.address), insn.mnemonic, insn.op_str, tag))
    return "\n".join(out)


if __name__ == "__main__":
    syms = load_syms()
    print("COFF function symbols:", len(syms))
    # 也打印符号里 heartbeat/delay 相关的
    hb = sorted((a, n) for a, n in syms.items() if "eart" in n or "elay" in n.lower() or "Ping" in n)
    for a, n in hb[:20]:
        print("  0x%x  %s" % (a, n))
    for s in (b"write heartbeat reply", b"message delay 924"):
        fo = data.find(s)
        if fo < 0:
            print(s, "not found")
            continue
        sva = off2va(fo)
        print("\n==== %s @VA 0x%x ====" % (s.decode(), sva))
        for r in find_refs(sva):
            print("-- ref at 0x%x" % r)
            print(disasm_around(r, syms=syms))
