#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 res1 层级 buff 描述校验 scripts/neigong/*.lua 的 M.levels 数值。

对勘口径：
  res1 string_buff_desc 的 desc_buf_ng_<id>_<层> 是每层特效权威文本；
  lua 的 M.levels[层] 表是从该文本人工提炼的数值（external/internal/reduce/...）。
本脚本抽取两边数值做集合对比，输出差异清单（不修改文件）。

用法：python tools/skill-data/verify_neigong_scripts.py [res1根] [脚本目录]
"""
import io
import os
import re
import sys

DEFAULT_RES1 = r"E:/Workspace/project/game/res1"
DEFAULT_SCRIPTS = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "neigong")

# lua M.levels 中出现的数值字段（外部公伤/内功伤/减免比例/吸收/回血等，动态取）
LUA_LEVEL_PAT = re.compile(r"\[(\d+)\]\s*=\s*\{([^}]*)\}")
FIELD_PAT = re.compile(r"(\w+)\s*=\s*([-\d./]+)")
# 描述文本中的数值（含 % 与点数）
DESC_NUM_PAT = re.compile(r"(\d+(?:\.\d+)?)\s*(%|点)?")


def load_res1_levels(res1_root):
    """desc_buf_ng_<book>_<层> → {book: {层: 文本}}。"""
    path = os.path.join(res1_root, "text", "chineses", "string_buff_desc.idres")
    pat = re.compile(r"^desc_(buf_ng_[a-z]+_\d+)_(\d+)$")
    levels = {}
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\r\n")
            if line.startswith("//") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            m = pat.match(key)
            if m:
                levels.setdefault(m.group(1), {})[int(m.group(2))] = value
    return levels


def strip_rich(text):
    return re.sub(r"<[^>]+>", "", text)


def lua_levels(path):
    """解析 lua 的 M.levels 表 → {层: {字段: 值}}。"""
    src = io.open(path, "r", encoding="utf-8").read()
    out = {}
    for m in LUA_LEVEL_PAT.finditer(src):
        tier = int(m.group(1))
        fields = {}
        for fm in FIELD_PAT.finditer(m.group(2)):
            value = fm.group(2)
            fields[fm.group(1)] = float(value) if "." in value else int(value)
        out[tier] = fields
    return out


def max_tier(path):
    src = io.open(path, "r", encoding="utf-8").read()
    m = re.search(r"max_tier\s*=\s*(\d+)", src)
    return int(m.group(1)) if m else None


def desc_numbers(text):
    """描述文本的数值多重集（%值与点值分开标记）。"""
    nums = {}
    for m in DESC_NUM_PAT.finditer(strip_rich(text)):
        token = m.group(1) + (m.group(2) or "")
        nums[token] = nums.get(token, 0) + 1
    return nums


def lua_numbers(fields):
    nums = {}
    for name, value in fields.items():
        if name == "reduce":
            token = f"{int(value * 100)}%"
        elif isinstance(value, float):
            token = str(value)
        else:
            token = str(value)
        nums[token] = nums.get(token, 0) + 1
    return nums


def main(res1_root, scripts_dir):
    levels = load_res1_levels(res1_root)
    scripts = sorted(f for f in os.listdir(scripts_dir) if f.endswith(".lua"))
    print(f"res1 有层级描述的内功书 {len(levels)}；脚本 {len(scripts)} 个\n")
    diff_books = []
    missing_desc = []
    for fn in scripts:
        book = fn[:-4]
        path = os.path.join(scripts_dir, fn)
        lua = lua_levels(path)
        mt = max_tier(path)
        desc = levels.get(book)
        if not desc:
            missing_desc.append(book)
            continue
        diffs = []
        for tier in sorted(lua):
            if tier not in desc:
                diffs.append(f"L{tier}: res1 无该层描述（res1 层集={sorted(desc)}）")
                continue
            expect = desc_numbers(desc[tier])
            actual = lua_numbers(lua[tier])
            if expect != actual:
                miss = {k: v for k, v in expect.items() if actual.get(k, 0) < v}
                extra = {k: v for k, v in actual.items() if expect.get(k, 0) < v}
                diffs.append(f"L{tier}: 文本={expect} lua={actual} 缺={miss} 多={extra}")
        res1_max = max(desc)
        if mt is not None and mt != res1_max:
            diffs.append(f"max_tier={mt} 但 res1 最高层={res1_max}")
        if diffs:
            diff_books.append((book, diffs))
    print(f"== 数值/层数不一致：{len(diff_books)} 本 ==")
    for book, diffs in diff_books:
        print(f"\n[{book}]")
        for d in diffs:
            print("  " + d)
    print(f"\n== res1 缺层级描述（脚本保留原值）：{len(missing_desc)} ==")
    print("  " + ", ".join(missing_desc))


if __name__ == "__main__":
    res1 = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RES1
    scripts = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_SCRIPTS
    main(res1, scripts)
