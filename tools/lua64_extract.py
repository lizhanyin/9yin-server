#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract client protocol dictionary from unpacked 9yin client lua64 scripts.

Parses (read-only, paths passed as args):
  <root>/share/client_custom_define.lua  -> msg id <-> name map
  <root>/custom_sender.lua               -> per-function CustomSend(...) signatures (client->server)
  <root>/custom_handler.lua              -> handler function signatures (server->client, name-addressed)

Usage:
  python -I lua64_extract.py <lua64_root> <out_md>
"""
import io
import re
import sys
from collections import defaultdict


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read().replace("\r\n", "\n")


def parse_define(text):
    """NAME = ID  lines -> {id: [names]}"""
    out = defaultdict(list)
    for m in re.finditer(r"^\s*([A-Z][A-Z0-9_]*)\s*=\s*(\d+)\s*$", text, re.M):
        name, num = m.group(1), int(m.group(2))
        out[num].append(name)
    return out


def split_functions(text):
    """Yield (funcname, params, body) for top-level `function name(...)...end` blocks."""
    starts = [(m.start(), m.group(1), m.group(2)) for m in
              re.finditer(r"^function\s+([.\w]+)\s*\(([^)]*)\)", text, re.M)]
    for i, (pos, name, params) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        yield name, params, text[pos:end]


def match_paren(text, open_idx):
    """Return index just past the ')' matching '(' at open_idx (skips lua strings roughly)."""
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c in "'\"":
            q = c
            i += 1
            while i < n and text[i] != q:
                if text[i] == "\\":
                    i += 1
                i += 1
        elif c == "-" and i + 1 < n and text[i + 1] == "-":  # comment
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def classify_arg(arg):
    """Map an argument expr to a short type tag."""
    a = arg.strip()
    m = re.match(r"^nx_(int64|int|string|widestr|float|object|boolean|number)\(", a)
    if m:
        return {"int64": "i64", "int": "int", "string": "str", "widestr": "wstr",
                "float": "flt", "object": "obj", "boolean": "bool", "number": "num"}[m.group(1)]
    if a.startswith("unpack("):
        return "..."
    if re.match(r"^-?\d+$", a):
        return "int"
    if a.startswith('"') or a.startswith("'"):
        return "str"
    if a == "nil":
        return "nil"
    return "var"


def parse_sender(text, defines):
    """[(func, msg_ids, [typed args])] for every CustomSend call."""
    results = []
    id_re = re.compile(r"^\s*nx_int\(\s*([A-Za-z_][\w]*)\s*\)\s*,?")
    for name, params, body in split_functions(text):
        for m in re.finditer(r"CustomSend\s*\(", body):
            open_idx = body.find("(", m.start())
            call = body[open_idx:match_paren(body, open_idx)]
            inner = call[1:-1]
            parts, cur, depth, i = [], "", 0, 0
            while i < len(inner):
                c = inner[i]
                if c in "'\"":
                    j = i + 1
                    while j < len(inner) and inner[j] != c:
                        if inner[j] == "\\":
                            j += 1
                        j += 1
                    cur += inner[i:j + 1]
                    i = j + 1
                    continue
                if c in "([{":
                    depth += 1
                elif c in ")]}":
                    depth -= 1
                if c == "," and depth == 0:
                    parts.append(cur)
                    cur = ""
                else:
                    cur += c
                i += 1
            parts.append(cur)
            if not parts:
                continue
            head = parts[0].strip()
            hm = id_re.match("nx_int(" + head + "),") if not head.startswith("nx_int") else id_re.match(head + ",")
            ids = []
            hm2 = re.match(r"nx_int\(\s*([A-Za-z_]\w*)\s*\)", head)
            if hm2:
                sym = hm2.group(1)
                ids = [sym]
            else:
                num = re.match(r"^(\d+)$", head)
                ids = ["#" + num.group(1)] if num else [head]
            args = [classify_arg(p) for p in parts[1:]]
            results.append((name, ids, args, params.strip()))
    return results


def main():
    root, out_path = sys.argv[1], sys.argv[2]
    defines = parse_define(read(root + "/share/client_custom_define.lua"))

    senders = parse_sender(read(root + "/custom_sender.lua"), defines)
    handlers = [(n, p) for n, p, _ in split_functions(read(root + "/custom_handler.lua"))]

    # group senders by resolved msg id
    by_id = defaultdict(list)
    for func, ids, args, params in senders:
        for sym in ids:
            key = sym
            by_id[key].append((func, args))

    lines = []
    w = lines.append
    w("# 九阴客户端 lua64 协议字典（生成物）")
    w("")
    w("> 由 `tools/lua64_extract.py` 从客户端解包脚本 `lua64_unpacked/lua64` 自动提取。")
    w("> 类型标记: int/str/wstr(UTF16串)/flt/obj(场景对象ident)/i64(金额文)/... (变参)。")
    w("> 服务端→客户端为**命令名寻址**：引擎按消息名调用 custom_handler.lua 同名函数，")
    w("> 其 msg_type 之后的形参即下行载荷布局（见第三节）。")
    w("")
    w("## 一、客户端→服务端 custom 消息（按 ID）")
    w("")
    w("| ID | 常量名 | 客户端发送函数(参数类型序列) |")
    w("|---|---|---|")
    for num in sorted(defines):
        names = "/".join(defines[num])
        entries = by_id.get(names.split("/")[0], [])
        if not entries:
            for nm in defines[num]:
                if nm in by_id:
                    entries = by_id[nm]
                    break
        if entries:
            funcs = "; ".join("`%s(%s)`" % (f, ",".join(a)) for f, a in entries[:4])
            if len(entries) > 4:
                funcs += " +%d" % (len(entries) - 4)
            w("| %d | %s | %s |" % (num, names, funcs))
    # senders whose id symbol didn't resolve
    w("")
    w("### 未解析到 ID 常量的发送调用（子命令直传）")
    w("")
    for func, ids, args, params in senders:
        sym = ids[0]
        if sym not in defines and not sym.startswith("#") and sym not in ("",):
            pass  # collected below
    raw = [(f, i[0], a) for f, i, a, _ in senders if i[0] not in defines and not i[0].lstrip("-").isdigit()]
    if raw:
        w("| 发送函数 | ID 表达式 | 参数 |")
        w("|---|---|---|")
        for f, e, a in raw[:80]:
            w("| `%s` | %s | %s |" % (f, e, ",".join(a)))
    w("")
    w("## 二、仅定义无 Lua 发送方的 ID（引擎 C++ 直发，需抓包/逆向还原）")
    w("")
    sent_syms = set()
    for _f, ids, _a, _p in senders:
        sent_syms.update(ids)
    engine_ids = []
    for num in sorted(defines):
        if not any(nm in sent_syms for nm in defines[num]):
            engine_ids.append("%d=%s" % (num, defines[num][0]))
    w("共 %d 个: %s" % (len(engine_ids), ", ".join(engine_ids)))
    w("")
    w("## 三、服务端→客户端 handler 函数名册（%d 个，命令名寻址）" % len(handlers))
    w("")
    w("| 函数名 | msg_type 后形参 |")
    w("|---|---|")
    for name, params in handlers:
        plist = [p.strip() for p in params.split(",") if p.strip()]
        rest = ",".join(plist[3:]) if len(plist) > 3 else "(无)"
        w("| `%s` | %s |" % (name, rest))

    with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("senders=%d handlers=%d ids=%d engine-only=%d -> %s"
          % (len(senders), len(handlers), len(defines), len(engine_ids), out_path))


if __name__ == "__main__":
    main()
