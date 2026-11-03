#!/usr/bin/env python3
"""Find parallel Go string-header runs (slug array / destination array) in the exe."""
import re
import sys

sys.path.insert(0, r"G:/pojie/ai/9yin-server/tools/smoke")
from extract_gm_presets import pe_sections, rva_to_off  # noqa: E402


def main(path):
    data = open(path, "rb").read()
    e = int.from_bytes(data[0x3C:0x40], "little")
    opt = e + 24
    base = int.from_bytes(data[opt + 24:opt + 32], "little")
    secs = pe_sections(data)

    def deref(ptr, ln):
        if not (0 < ln < 200) or ptr <= base:
            return None
        off = rva_to_off(secs, ptr - base)
        if off is None or off + ln > len(data):
            return None
        return data[off:off + ln]

    is_slug = lambda b: re.fullmatch(rb"[a-z][a-z0-9]{2,20}", b) is not None
    is_dest = lambda b: re.fullmatch(rb"ini\\scene\\[!-~]{2,60},[A-Za-z0-9_]+,"
                                     rb"-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*,"
                                     rb"-?[0-9]+\.?[0-9]*,-?[0-9]+\.?[0-9]*", b) is not None

    def runs(pred):
        out = []
        i = 0
        while i + 16 <= len(data):
            j = i
            items = []
            while j + 16 <= len(data):
                s = deref(int.from_bytes(data[j:j + 8], "little"),
                          int.from_bytes(data[j + 8:j + 16], "little"))
                if s is None or not pred(s):
                    break
                items.append(s.decode())
                j += 16
            if len(items) >= 8:
                out.append((i, j, items))
                i = j
            else:
                i += 16
        return out

    for name, pred in (("slug", is_slug), ("dest", is_dest)):
        for lo, hi, items in runs(pred):
            print(f"== {name} run {hex(lo)}..{hex(hi)} n={len(items)}")
            for s in items[:200]:
                print("  ", s)


if __name__ == "__main__":
    main(sys.argv[1])
