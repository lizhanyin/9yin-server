#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""effects_data.lua ↔ skill_catalog.jsonl.gz 对勘（全技能效果接入 Lua 的辅助勘验）。

jsonl 由 compute_skill_catalog.py 从 res1 全链编译（CD 抽检与抓包实证对齐过），
本脚本把 effects_data.lua 的 Lua 表重新解析后按（id, level）逐字段对照：
  damage.amount ↔ base_damage     damage.waves ↔ damage_waves
  self_buffs/target_effects ↔ exact_buff（config/static/lifetime/harmful 侧别）
  by_level 差集 ↔ 各级行

**权威 parity 钉子是 Java 双根对照**（SkillEffectDataParityTest：同表 INI 兜底编译
vs Lua 数据驱动编译全量 diff）——jsonl 是 res1（盛大GM版）编译产物，与 share 表存在
表版本漂移：伤害数值 5 个族（cf_yjqf/cf_bwqf/nlb_tmw/gm_txjj/xd_xdj/dbz_wgh）系统性
偏低 ~25%，租约/段数/侧别与 share 一致。因此本脚本退出码只对**结构性差异**（waves/
租约/侧别/静态值/缺条目）非零；伤害漂移逐条列出仅供勘验。

用法：python tools/skill-data/verify_skill_scripts.py [effects_data.lua] [jsonl.gz] [skill_new.ini] [-v]
退出码：0=结构全对齐；1=存在结构性差异或 share 表内缺条目。
"""
import argparse
import gzip
import io
import json
import os
import re
import sys

DEFAULT_LUA = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "skill",
                           "effects_data.lua")
DEFAULT_JSONL = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "text-extract",
                             "data", "skill_catalog.jsonl.gz")
DEFAULT_SHARE_SKILL = r"F:/Game/Online/9yin/9yin_server1/resources/modern/share/skill/skill_new.ini"

DAY_MS = 24 * 60 * 60 * 1000


def parse_effects_lua(path):
    """极简解析生成器方言的 effects_data.lua（E["id"] = { ... } 块）。

    生成器输出是受控格式（数字/字符串字面量、固定键名），此处按同方言解析；
    手写改动若引入生成器方言以外的语法，须先跑生成器保持方言。
    """
    with io.open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    entries = {}
    for match in re.finditer(r'E\["([^"]+)"\]\s*=\s*\{(.*?)\n\}', text, re.S):
        skill_id, body = match.group(1), match.group(2)
        entry = {"by_level": {}}
        damage = re.search(
            r"damage\s*=\s*\{\s*amount\s*=\s*(\d+)\s*,\s*waves\s*=\s*(\d+)\s*\}", body)
        if damage:
            entry["damage"] = (int(damage.group(1)), int(damage.group(2)))
        self_buff = re.search(
            r"self_buffs\s*=\s*\{\s*\{\s*config_id\s*=\s*\"([^\"]+)\"\s*,\s*static_data"
            r"\s*=\s*(\d+)[^}]*life_ms\s*=\s*(\d+)", body)
        if self_buff:
            entry["self_buff"] = (self_buff.group(1), int(self_buff.group(2)),
                                  int(self_buff.group(3)))
        target = re.search(
            r"target_effects\s*=\s*\{\s*\{\s*config_id\s*=\s*\"([^\"]+)\"\s*,\s*static_data"
            r"\s*=\s*(\d+)[^}]*life_ms\s*=\s*(\d+)", body)
        if target:
            entry["target_effect"] = (target.group(1), int(target.group(2)),
                                      int(target.group(3)))
        by_block = re.search(r"by_level\s*=\s*\{(.*?)\n\s*\}", body, re.S)
        if by_block:
            for level_match in re.finditer(
                    r"\[(\d+)\]\s*=\s*\{\s*([^}]*)\}", by_block.group(1)):
                level = int(level_match.group(1))
                fields = {}
                for kv in re.finditer(r"(\w+)\s*=\s*(\d+)", level_match.group(2)):
                    fields[kv.group(1)] = int(kv.group(2))
                entry["by_level"][level] = fields
        entries[skill_id] = entry
    return entries


def main():
    parser = argparse.ArgumentParser(description="effects_data.lua 对勘 jsonl")
    parser.add_argument("lua", nargs="?", default=DEFAULT_LUA)
    parser.add_argument("jsonl", nargs="?", default=DEFAULT_JSONL)
    parser.add_argument("share", nargs="?", default=DEFAULT_SHARE_SKILL,
                        help="share skill_new.ini（判缺失条目是否 res1 独有）")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    entries = parse_effects_lua(args.lua)
    rows = {}
    with gzip.open(args.jsonl, "rt", encoding="utf-8") as handle:
        for line in handle:
            record = json.loads(line)
            rows.setdefault(record["id"], {})[record["level"]] = record

    diffs = []
    structural = []
    checked = 0
    share_only = 0
    for skill_id, entry in sorted(entries.items()):
        levels = rows.get(skill_id)
        if not levels:
            share_only += 1  # share 表有而 res1 无（表版本差异，非矛盾）
            continue
        for level, record in sorted(levels.items()):
            checked += 1
            expect_damage = record.get("base_damage") or 0
            expect_waves = record.get("damage_waves") or 0
            buff = record.get("exact_buff")
            if level == min(levels):
                damage = entry.get("damage", (0, 0))
                if damage[0] != expect_damage:
                    diffs.append((skill_id, level,
                                  "damage %d != %d (res1 表版本漂移)" % (damage[0], expect_damage)))
                if expect_damage > 0 and damage[1] != expect_waves:
                    structural.append((skill_id, level,
                                       "waves %d != %d" % (damage[1], expect_waves)))
                if buff and buff.get("lifetime_ms") and buff["lifetime_ms"] != DAY_MS:
                    if buff.get("harmful"):
                        got = entry.get("target_effect")
                        side = "target_effect"
                        if not got:
                            structural.append((skill_id, level, "harmful side missing"))
                            got = (None, None, 0)
                    else:
                        got = entry.get("self_buff")
                        side = "self_buff"
                        if not got:
                            structural.append((skill_id, level, "self side missing"))
                            got = (None, None, 0)
                    if buff.get("config_id") and got[0] != buff["config_id"]:
                        structural.append((skill_id, level,
                                           "%s config %r != %r" % (side, got[0], buff["config_id"])))
                    if buff.get("static_data") and got[1] != buff["static_data"]:
                        structural.append((skill_id, level,
                                           "%s static %s != %s" % (side, got[1], buff["static_data"])))
                    if got and got[2] != buff["lifetime_ms"]:
                        structural.append((skill_id, level,
                                           "life %d != %d" % (got[2], buff["lifetime_ms"])))
            else:
                patch = entry["by_level"].get(level, {})
                if "damage_amount" in patch and patch["damage_amount"] != expect_damage:
                    diffs.append((skill_id, level,
                                  "by_level damage %d != %d (res1 表版本漂移)"
                                  % (patch["damage_amount"], expect_damage)))

    # 覆盖面：jsonl 有 exact_buff/damage 而 effects_data 缺条目——share 表里也没有
    # 该技能 = res1 独有（Java 本就 missing_static_id 跳过，非 parity 破绽）。
    script_ids = set()
    script_dir = os.path.dirname(os.path.abspath(args.lua))
    for name in os.listdir(script_dir):
        if name.endswith(".lua") and name != "effects_data.lua":
            script_ids.add(name[:-4])
    share_ids = set()
    if os.path.isfile(args.share):
        with io.open(args.share, "r", encoding="latin-1") as handle:
            for line in handle:
                match = re.match(r"\[([^\]]+)\]", line)
                if match:
                    share_ids.add(match.group(1))
    missing = 0
    res1_only = 0
    for skill_id, levels in rows.items():
        if skill_id in entries or skill_id in script_ids:
            continue
        base = levels.get(min(levels), {})
        if (base.get("base_damage") or 0) > 0 or base.get("exact_buff"):
            if skill_id in share_ids:
                missing += 1
                if args.verbose:
                    print("MISSING", safe(skill_id))
            else:
                res1_only += 1

    print("entries=%d checked_rows=%d damage_drift=%d structural=%d"
          " share_only=%d missing_in_share=%d res1_only=%d script_overrides=%d"
          % (len(entries), checked, len(diffs), len(structural), share_only,
             missing, res1_only, len(script_ids)))
    for skill_id, level, what in diffs[:20]:
        print("DRIFT %s@%s: %s" % (safe(skill_id), level, what))
    if len(diffs) > 20:
        print("... %d more drift" % (len(diffs) - 20))
    for skill_id, level, what in structural[:20]:
        print("STRUCTURAL %s@%s: %s" % (safe(skill_id), level, what))
    if len(structural) > 20:
        print("... %d more structural" % (len(structural) - 20))
    return 1 if structural or missing else 0


def safe(text):
    """GBK 控制台打印消毒（jsonl 有 res1 提取残留 U+FFFD）。"""
    return text.encode("ascii", "replace").decode("ascii")


if __name__ == "__main__":
    sys.exit(main())
