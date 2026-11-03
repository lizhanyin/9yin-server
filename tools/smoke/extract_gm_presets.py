#!/usr/bin/env python3
"""Extract GM scene-preset destination strings from the native menu exe.

Scans ASCII and UTF-16LE for `ini\\scene\\<config>,<resource>,x,y,z,orient`
destination tuples plus nearby preset key names, so the Java GmScenePresets
table can be seeded from the same source the shipped console.html uses.
"""
import re
import sys

DEST_ASCII = re.compile(
    rb"ini\\scene\\[!-~]{2,60},[A-Za-z0-9_]+,-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*")
KEY_ASCII = re.compile(rb"[a-z][a-z0-9]{2,20}\x00")


def scan(path):
    data = open(path, "rb").read()
    print(f"== {path} size={len(data)}")
    hits = DEST_ASCII.findall(data)
    uniq = sorted({h.decode("ascii") for h in hits})
    print(f"ascii dest hits: {len(hits)} uniq {len(uniq)}")
    for s in uniq:
        print("  ", s)
    # UTF-16LE variant
    dest16 = re.compile(
        ("ini\\scene\\[!-~]{2,60},[A-Za-z0-9_]+,"
         "-?[0-9]+\\.?[0-9]*,-?[0-9]+\\.?[0-9]*,-?[0-9]+\\.?[0-9]*,-?[0-9]+\\.?[0-9]*").encode("utf-16-le"))
    # build utf16 pattern via translate: encode regex is messy; do naive window scan
    needle = "ini\\scene\\".encode("utf-16-le")
    idx = 0
    found = 0
    while True:
        idx = data.find(needle, idx)
        if idx < 0:
            break
        end = data.find(b"\x00\x00", idx)
        try:
            s = data[idx:end].decode("utf-16-le", "ignore")
        except Exception:
            s = ""
        if "," in s and len(s) < 120:
            found += 1
            print("  u16:", s)
        idx += 1
    print(f"utf16 windows: {found}")


def pe_sections(data):
    """Minimal PE section table: [(va, vsize, rawptr, rawsize, name)]."""
    e_lfanew = int.from_bytes(data[0x3C:0x40], "little")
    num_sections = int.from_bytes(data[e_lfanew + 6:e_lfanew + 8], "little")
    opt_size = int.from_bytes(data[e_lfanew + 20:e_lfanew + 22], "little")
    table = e_lfanew + 24 + opt_size
    out = []
    for i in range(num_sections):
        off = table + i * 40
        name = data[off:off + 8].rstrip(b"\x00").decode("ascii", "ignore")
        vsize = int.from_bytes(data[off + 8:off + 12], "little")
        va = int.from_bytes(data[off + 12:off + 16], "little")
        rawsize = int.from_bytes(data[off + 16:off + 20], "little")
        rawptr = int.from_bytes(data[off + 20:off + 24], "little")
        out.append((va, vsize, rawptr, rawsize, name))
    return out


def rva_to_off(sections, rva):
    for va, vsize, rawptr, rawsize, name in sections:
        if va <= rva < va + max(vsize, rawsize):
            delta = rva - va
            if delta < rawsize:
                return rawptr + delta
    return None


def kv_table(path):
    """Scan the whole image for Go string-header pairs key{ptr,len} -> dest."""
    data = open(path, "rb").read()
    sections = pe_sections(data)
    e_lfanew = int.from_bytes(data[0x3C:0x40], "little")
    opt = e_lfanew + 24
    magic = int.from_bytes(data[opt:opt + 2], "little")
    if magic == 0x20B:  # PE32+
        base = int.from_bytes(data[opt + 24:opt + 32], "little")
    else:
        base = int.from_bytes(data[opt + 28:opt + 32], "little")

    def deref(ptr, ln):
        if not (0 < ln < 200) or ptr <= base:
            return None
        rva = ptr - base
        off = rva_to_off(sections, rva)
        if off is None or off + ln > len(data):
            return None
        return data[off:off + ln]

    results = []
    i = 0
    while i + 32 <= len(data):
        kptr = int.from_bytes(data[i:i + 8], "little")
        kln = int.from_bytes(data[i + 8:i + 16], "little")
        key = deref(kptr, kln)
        if key is not None and re.fullmatch(rb"[a-z][a-z0-9]{2,20}", key):
            vptr = int.from_bytes(data[i + 16:i + 24], "little")
            vln = int.from_bytes(data[i + 24:i + 32], "little")
            val = deref(vptr, vln)
            if val is not None and val.startswith(b"ini\\scene\\") and b"," in val:
                results.append((i, key.decode(), val.decode()))
                i += 32
                continue
        i += 8
    return results


def header_probe(path):
    """Recover the (len,ptr)-alternating preset KV table around a known header."""
    import struct
    data = open(path, "rb").read()
    e_lfanew = int.from_bytes(data[0x3C:0x40], "little")
    opt = e_lfanew + 24
    base = int.from_bytes(data[opt + 24:opt + 32], "little")
    secs = pe_sections(data)

    def rva2off(rva):
        for va, vsize, rawptr, rawsize, _n in secs:
            if va <= rva < va + max(vsize, rawsize):
                d = rva - va
                return rawptr + d if d < rawsize else None
        return None

    needle = rb"ini\scene\city04_LuoYang,city04,243.387,43.277,403.821,5.024"
    for m in re.finditer(re.escape(struct.pack("<Q", len(needle))), data):
        p = m.start()
        ptr = int.from_bytes(data[p - 8:p], "little")
        off = rva2off(ptr - base) if ptr > base else None
        if off is None or data[off:off + len(needle)] != needle:
            continue
        print("table center at", hex(p))

        def try_header(qpos):
            """Go string header {ptr, len} at qpos; return the bytes or None."""
            ptr2 = int.from_bytes(data[qpos:qpos + 8], "little")
            ln = int.from_bytes(data[qpos + 8:qpos + 16], "little")
            if not (0 < ln < 200) or ptr2 <= base:
                return None
            off2 = rva2off(ptr2 - base)
            if off2 is None or off2 + ln > len(data):
                return None
            return data[off2:off2 + ln]

        # The needle header sits at p-8 ({ptr,len}); snap back to the
        # 16-byte-aligned KV grid, then walk out while headers resolve and
        # alternate key/value plausibly.
        anchor = p - 8

        def plausible(pos):
            a = try_header(pos)
            b = try_header(pos + 16)
            if a is None or b is None:
                return False
            return (re.fullmatch(rb"[a-z][a-z0-9]*", a) is not None
                    and b.startswith(b"ini\\")) or \
                   (re.fullmatch(rb"[a-z][a-z0-9]*", b) is not None
                    and a.startswith(b"ini\\"))

        lo = anchor
        while plausible(lo - 32):
            lo -= 32
        hi = anchor
        while plausible(hi + 32):
            hi += 32
        print(f"table span {hex(lo)}..{hex(hi + 31)}")
        q = lo
        while q <= hi:
            a = try_header(q)
            b = try_header(q + 16)
            if a is not None and b is not None:
                if b.startswith(b"ini\\"):
                    print(f"{a.decode()}\t{b.decode()}")
                else:
                    print(f"{b.decode()}\t{a.decode()}")
            q += 32
        break


def _decode_cn(raw):
    """Exe strings mix UTF-8 and GBK; decode either into text."""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("gbk", "replace")


def label_tables(path):
    """Dump both exe tables: slug->Chinese label and Chinese label->destination."""
    data = open(path, "rb").read()
    e = int.from_bytes(data[0x3C:0x40], "little")
    opt = e + 24
    base = int.from_bytes(data[opt + 24:opt + 32], "little")

    def deref(ptr, ln):
        if not (0 < ln < 200) or ptr <= base:
            return None
        rva = ptr - base
        off = rva_to_off(pe_sections(data), rva)
        if off is None or off + ln > len(data):
            return None
        return data[off:off + ln]

    slug_label = []
    label_dest = []
    i = 0
    while i + 32 <= len(data):
        k = deref(int.from_bytes(data[i:i + 8], "little"),
                  int.from_bytes(data[i + 8:i + 16], "little"))
        if k is not None:
            v = deref(int.from_bytes(data[i + 16:i + 24], "little"),
                      int.from_bytes(data[i + 24:i + 32], "little"))
            if v is not None:
                if re.fullmatch(rb"[a-z][a-z0-9]{2,20}", k) \
                        and 3 <= len(v) <= 40 and any(b >= 0x80 for b in v):
                    slug_label.append((k.decode(), _decode_cn(v)))
                    i += 32
                    continue
                if any(b >= 0x80 for b in k[:6]) and len(k) <= 24 \
                        and re.fullmatch(rb"ini\\scene\\[!-~]{2,60},[A-Za-z0-9_]+,"
                                         rb"-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*,"
                                         rb"-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*", v):
                    label_dest.append((_decode_cn(k), v.decode()))
                    i += 32
                    continue
        i += 8
    return slug_label, label_dest


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--labels":
        out = sys.argv[-1] if sys.argv[-1].endswith(".tsv") else None
        for p in sys.argv[2:]:
            if p == out:
                continue
            sl, ld = label_tables(p)
            lines = [f"== slug->label {len(sl)}  label->dest {len(ld)} ({p})"]
            lines += [f"SLUG\t{k}\t{v}" for k, v in sl]
            lines += [f"DEST\t{k}\t{v}" for k, v in ld]
            if out:
                open(out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
                print(f"wrote {out}: slug->label {len(sl)} label->dest {len(ld)}")
            else:
                print("\n".join(lines))
    elif len(sys.argv) > 1 and sys.argv[1] == "--header":
        for p in sys.argv[2:]:
            print(f"== header {p}")
            header_probe(p)
    elif len(sys.argv) > 1 and sys.argv[1] == "--kv":
        for p in sys.argv[2:]:
            print(f"== kv {p}")
            for off, k, v in kv_table(p):
                print(f"{k}\t{v}")
    elif len(sys.argv) > 1 and sys.argv[1] == "--pairs":
        for p in sys.argv[2:]:
            print(f"== pairs {p}")
            pairs(p)
    else:
        for p in sys.argv[1:]:
            scan(p)
