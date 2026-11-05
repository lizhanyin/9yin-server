import ida_auto
import ida_funcs
import ida_hexrays
import ida_idaapi
import ida_name
import ida_nalt
import idautils

OUT = r"F:\ida-scratch\hp-strings-xrefs.txt"
NEEDLES = {
    "MaxHPAdd", "MaxMPAdd", "MaxHPMul", "MaxMPMul",
    "MaxHP", "MaxMP", "DeModifyMaxHPAdd", "DeModifyMaxMPAdd",
}
MAX_DECOMP = 60000


def decompile_function(ea):
    func = ida_funcs.get_func(ea)
    if not func:
        return "<no function>"
    try:
        text = str(ida_hexrays.decompile(func.start_ea))
    except Exception as exc:
        return "<decompile failed: %s>" % (exc,)
    if len(text) > MAX_DECOMP:
        text = text[:MAX_DECOMP] + "\n...<truncated>..."
    return text


def main():
    ida_auto.auto_wait()
    lines = []
    lines.append("input=%s" % ida_nalt.get_input_file_path())
    lines.append("md5=%s" % ida_nalt.retrieve_input_file_md5().hex())
    dumped = set()
    for item in idautils.Strings():
        value = str(item)
        if value not in NEEDLES:
            continue
        lines.append("\n=== STRING %r @ %s ===" % (value, hex(item.ea)))
        for xref in idautils.XrefsTo(item.ea):
            func = ida_funcs.get_func(xref.frm)
            fname = ida_name.get_ea_name(func.start_ea) if func else "<nofunc>"
            lines.append("xref from %s in %s" % (hex(xref.frm), fname))
            if func and func.start_ea not in dumped:
                dumped.add(func.start_ea)
                lines.append("\n----- DECOMP %s @ %s -----" % (fname, hex(func.start_ea)))
                lines.append(decompile_function(func.start_ea))
                lines.append("----- END %s -----" % fname)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    ida_idaapi.qexit(0)


main()
