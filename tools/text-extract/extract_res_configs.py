#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 res1 客户端资源包（share/ini/lua64/map），生成配置清单与结构摘要。

输入：resources/modern/（客户端资源）
输出：docs/text-extract/data/res1_inventory.json
  {
    "root": ...,
    "overview": {各顶层目录的文件数/大小},
    "share":   {子系统 -> {file_count, tables: [{file, sections, fields, sample_section}]}},
    "ini":     同上（顶层按子目录分组）,
    "map":     {ini 表清单, path 按场景前缀统计},
    "lua64":   {文件 -> {lines, size, head_comment}}
  }

INI 解析规则与客户端一致：`[section]` + `key=value`，`//` 与 `;` 为注释；
section 名为配置主键（如装备 ID `CLOTH_SZ001`、形状 ID `[3]`），[-1] 等负数段为
表内注释/说明行。每个文件只统计字段并保留一个示例 section，避免清单膨胀。

用法：python tools/text-extract/extract_res1_configs.py [res1根目录] [输出目录]
"""
import json
import os
import sys
from collections import defaultdict

DEFAULT_SRC = r"E:/Workspace/project/game/res1"
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "text-extract")

INI_EXTS = (".ini", ".cfg", ".txt")
SKIP_FIELDS = ("comment",)  # 纯注释字段不计入字段集合


def parse_ini_summary(path, max_fields=60):
    """解析单个 INI，返回 (section数, 字段集合, 示例section名, 示例数据)。"""
    sections = 0
    fields = set()
    sample_name = None
    sample = {}
    meta_section = None  # [-1] 段视为表说明
    cur = None
    cur_is_meta = False
    try:
        with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("//") or line.startswith(";"):
                    continue
                if line.startswith("[") and line.endswith("]"):
                    cur = line[1:-1].strip()
                    cur_is_meta = cur.lstrip("-").isdigit() and cur.startswith("-")
                    if cur_is_meta and meta_section is None:
                        meta_section = {}
                    sections += 1
                    if sample_name is None and not cur_is_meta and cur != "-1":
                        sample_name = cur
                        sample = {}
                    continue
                if cur is None or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                if k and k.lower() not in SKIP_FIELDS:
                    fields.add(k)
                if cur_is_meta and meta_section is not None:
                    meta_section[k.strip()] = v.strip()
                elif cur == sample_name and len(sample) < 25:
                    sample[k.strip()] = v.strip()
    except OSError:
        pass
    out = {
        "file": path.replace("\\", "/").split("/res1/")[-1],
        "sections": sections,
        "fields": sorted(fields)[:max_fields],
    }
    if meta_section:
        out["table_note"] = meta_section
    if sample_name is not None:
        out["sample_section"] = sample_name
        out["sample"] = sample
    return out, sections


def scan_dir_tables(root):
    """扫描目录下全部 INI 表，按一级子目录分组。"""
    groups = defaultdict(lambda: {"file_count": 0, "bytes": 0, "tables": []})
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel = os.path.relpath(dirpath, root).replace("\\", "/")
        top = rel.split("/")[0] if rel != "." else "(root)"
        for fn in sorted(filenames):
            fp = os.path.join(dirpath, fn)
            sz = os.path.getsize(fp)
            g = groups[top]
            g["file_count"] += 1
            g["bytes"] += sz
            if fn.lower().endswith(INI_EXTS) and sz < 30 * 1024 * 1024:
                summary, _ = parse_ini_summary(fp)
                g["tables"].append(summary)
    return groups


def main(src_root, out_dir):
    data_dir = os.path.join(out_dir, "data")
    os.makedirs(data_dir, exist_ok=True)

    overview = {}
    for name in sorted(os.listdir(src_root)):
        p = os.path.join(src_root, name)
        if os.path.isdir(p):
            n = b = 0
            for dp, _, fns in os.walk(p):
                n += len(fns)
                b += sum(os.path.getsize(os.path.join(dp, f)) for f in fns)
            overview[name] = {"files": n, "mb": round(b / 1048576, 1)}
        else:
            overview[name] = {"files": 1, "bytes": os.path.getsize(p)}

    result = {"root": src_root, "overview": overview}

    print("扫描 share/ ...")
    result["share"] = scan_dir_tables(os.path.join(src_root, "share"))
    print("扫描 ini/ ...")
    result["ini"] = scan_dir_tables(os.path.join(src_root, "ini"))

    print("扫描 map/ ...")
    map_root = os.path.join(src_root, "map")
    result["map"] = {
        "ini": scan_dir_tables(os.path.join(map_root, "ini")),
        "path_prefix_stats": {},
    }
    prefix_ct = defaultdict(int)
    for fn in os.listdir(os.path.join(map_root, "path")):
        if fn.endswith(".path"):
            prefix_ct[fn.split(".")[0].split("_")[0]] += 1
    result["map"]["path_prefix_stats"] = dict(sorted(prefix_ct.items(), key=lambda kv: -kv[1]))

    print("扫描 lua64/ ...")
    lua = {}
    lua_root = os.path.join(src_root, "lua64")
    for dp, dns, fns in os.walk(lua_root):
        dns.sort()
        for fn in sorted(fns):
            fp = os.path.join(dp, fn)
            rel = os.path.relpath(fp, lua_root).replace("\\", "/")
            try:
                with open(fp, "r", encoding="utf-8-sig", errors="replace") as fh:
                    content = fh.read()
                head = ""
                for line in content.splitlines():
                    line = line.strip()
                    if line and not line.startswith("--!") and not line.startswith("--[["):
                        head = line[:100]
                        break
                lua[rel] = {"kb": round(len(content.encode("utf-8", "replace")) / 1024, 1),
                            "lines": content.count("\n"), "head": head}
            except OSError:
                continue
    result["lua64"] = lua

    out_path = os.path.join(data_dir, "res1_inventory.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    size_mb = os.path.getsize(out_path) / 1048576
    n_tables = sum(len(g["tables"]) for g in result["share"].values()) + \
               sum(len(g["tables"]) for g in result["ini"].values()) + \
               sum(len(g["tables"]) for g in result["map"]["ini"].values())
    print(f"完成：{out_path} ({size_mb:.1f} MB, 覆盖 {n_tables} 张 INI 表, {len(lua)} 个 lua 文件)")


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    out = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT)
    main(src, out)
