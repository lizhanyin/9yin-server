#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 res1 层级描述生成缺失的内功特效脚本（scripts/neigong/buf_ng_*.lua）。

范围：res1 有名字 key + 层级描述、且当前无脚本的玩家内功书（排除 buf_ng_npc_*）。
已有人工脚本（138 本）一律不覆盖——数值已对勘与 res1 一致。

生成脚本遵循现有手工脚本约定（见 buf_ng_em_001.lua）：
  M.meta { id/name/book/category/max_tier } + M.levels[tier] + tier_of/lv +
  按机制分类放置钩子（on_parry/on_be_hit/on_hit/on_periodic/passive）。
数值字段为从 res1 描述解析的常见参数（无命中则该层仅含 desc 原文），
机制未钉死的钩子返回 nil 并留 TODO 注释，供战斗二期接线时逐本细化。

用法：python tools/skill-data/gen_neigong_scripts.py [res1根] [脚本目录] [--dry]
"""
import io
import json
import os
import re
import sys

DEFAULT_RES1 = r"E:/Workspace/project/game/res1"
DEFAULT_SCRIPTS = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "neigong")

INDEX_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "text-extract",
                          "data", "neigong_effect_index.json")

# 机制分类关键词（对齐旧清单「机制分类」口径，按优先级）
CATEGORY_RULES = [
    ("parry", re.compile(r"招架")),
    ("periodic", re.compile(r"每隔|周期")),
    ("be_hit", re.compile(r"被攻击|受击|受到伤害|被伤害")),
    ("hit", re.compile(r"每次出招|出招|攻击时|攻击有|攻击附带|攻击目标")),
]

# 常见数值参数解析（每层描述 → 字段）
PARAM_PATTERNS = [
    ("absorb", re.compile(r"吸收(?:最多)?(\d+)点")),
    ("reduce_pct", re.compile(r"减免?(\d+(?:\.\d+)?)%")),
    ("absorb_pct", re.compile(r"伤害被吸收(\d+(?:\.\d+)?)%")),
    ("heal_pct", re.compile(r"恢复?(\d+(?:\.\d+)?)%\s*(?:的)?(?:最大)?气血")),
    ("damage_up_pct", re.compile(r"提升(\d+(?:\.\d+)?)%\s*(?:伤害|增伤)")),
    ("drain_heal_pct", re.compile(r"伤害的(\d+(?:\.\d+)?)%将?转化")),
    ("duration_sec", re.compile(r"(\d+(?:\.\d+)?)\s*秒")),
    ("layers", re.compile(r"(\d+)\s*层")),
    ("times", re.compile(r"(\d+)\s*次")),
    ("mp_add", re.compile(r"内力上限提高(\d+)点")),
    ("gangqi", re.compile(r"罡气提高(\d+)")),
    ("damage_add", re.compile(r"附加(?:额外)?([阴阳刚柔])伤害")),
    ("melee_up", re.compile(r"近身威力提高(\d+)点")),
]

HOOKS = {
    "parry": ("on_parry", "M.on_parry"),
    "periodic": ("on_periodic", "M.on_periodic"),
    "be_hit": ("on_be_hit", "M.on_be_hit"),
    "hit": ("on_hit", "M.on_hit"),
}


def classify(text):
    for category, pattern in CATEGORY_RULES:
        if pattern.search(text):
            return category
    return "passive"


def parse_params(text):
    params = {}
    for name, pattern in PARAM_PATTERNS:
        values = pattern.findall(text)
        if values:
            if name == "damage_add":
                params[name] = values
            else:
                params[name] = [float(v) if "." in v else int(v) for v in values]
    return params


def lua_escape(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


def lua_value(value):
    if isinstance(value, list):
        return "{ " + ", ".join(lua_value(v) for v in value) + " }"
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, int):
        return str(value)
    return '"' + lua_escape(str(value)) + '"'


def render_script(book, meta, tiers):
    lines = []
    name = meta["name"]
    school = meta.get("school", "")
    lines.append(f"-- {name}（{book}）｜{school or '门派未知'}｜{meta['category']}")
    lines.append(f"-- 源：res1 string_buff_desc desc_{book}_<层>（{meta['max_tier']} 层）。")
    lines.append("-- 生成：tools/skill-data/gen_neigong_scripts.py；数值自描述解析，"
                 "机制钩子待战斗二期逐本细化。")
    lines.append("local M = {}")
    lines.append(f"M.meta = {{ id = \"{book}\", name = \"{lua_escape(name)}\", "
                 f"book = \"{book[4:]}\",")
    lines.append(f"           category = \"{meta['category']}\", max_tier = {meta['max_tier']} }}")
    lines.append("M.levels = {")
    for tier in sorted(tiers):
        text = tiers[tier]
        params = parse_params(text)
        parts = [f"desc = \"{lua_escape(text)}\""]
        for key, value in params.items():
            parts.append(f"{key} = {lua_value(value)}")
        lines.append(f"  [{tier}] = {{ {', '.join(parts)} }},")
    lines.append("}")
    lines.append("")
    lines.append("local function tier_of(ctx)")
    lines.append("  local mt = M.meta.max_tier")
    lines.append("  local bl, bm = ctx.book_level or 1, ctx.book_max_level or mt")
    lines.append("  -- 九阴志档位门：高层特效档位随全服九阴志阶段开放（阶段1→最高3档，每阶段+1；可调）")
    lines.append("  local cap = 3 + (ctx.story_stage or 1) - 1")
    lines.append("  return math.max(1, math.min(mt, math.ceil(bl * mt / bm), cap))")
    lines.append("end")
    lines.append("local function lv(ctx) return M.levels[tier_of(ctx)] or M.levels[M.meta.max_tier] end")
    lines.append("")
    hook_name, _ = HOOKS.get(meta["category"], (None, None))
    hooks = [("M.on_parry", "parry"), ("M.on_be_hit", "be_hit"),
             ("M.on_hit", "hit"), ("M.on_periodic", "periodic")]
    if hook_name is None:
        hooks = []
    emitted = set()
    for func, category in hooks:
        emitted.add(func)
        lines.append(f"function {func}(ctx)")
        if category == meta["category"]:
            lines.append("  local p = lv(ctx)")
            lines.append("  -- TODO(二期)：按描述机制返回效果指令（字段见 M.levels 同名参数）")
            lines.append("  return nil")
        else:
            lines.append("  return nil")
        lines.append("end")
        lines.append("")
    lines.append("function M.passive(ctx)")
    lines.append("  local p = lv(ctx)")
    if "mp_add" in json.dumps({t: parse_params(tiers[t]) for t in tiers}):
        lines.append("  -- 高层内力上限/罡气加成（描述原文见 M.levels[].desc）")
    lines.append("  -- TODO(二期)：内力上限/罡气等被动加成接线")
    lines.append("  return nil")
    lines.append("end")
    lines.append("")
    lines.append("return M")
    return "\n".join(lines) + "\n"


def main(res1, scripts_dir, dry=False):
    if not os.path.isfile(INDEX_PATH):
        print("缺少 neigong_effect_index.json，先运行 compute_skill_catalog.py")
        sys.exit(1)
    with io.open(INDEX_PATH, "r", encoding="utf-8") as fh:
        index = json.load(fh)
    # 幂等：带生成器标记的旧文件视为可重生成；无标记 = 人工脚本，不覆盖。
    def is_generated(book):
        path = os.path.join(scripts_dir, book + ".lua")
        if not os.path.isfile(path):
            return False
        head = io.open(path, "r", encoding="utf-8").read(400)
        return "gen_neigong_scripts.py" in head

    existing = {f for f in os.listdir(scripts_dir) if f.endswith(".lua")}
    generated = skipped_npc = skipped_notier = skipped_manual = 0
    for book in sorted(index):
        meta = index[book]
        if meta["script"] == "npc":
            skipped_npc += 1
            continue
        if meta["script"] and not is_generated(book):
            skipped_manual += 1
            continue
        if not meta["tiers"] or meta["max_tier"] <= 0:
            skipped_notier += 1
            continue
        first_tier = meta["tiers"].get("1") or meta["tiers"][str(min(int(t) for t in meta["tiers"]))]
        meta["category"] = classify(first_tier)
        content = render_script(book, meta, {int(k): v for k, v in meta["tiers"].items()})
        target = os.path.join(scripts_dir, book + ".lua")
        if dry:
            print(f"[dry] {book} ({meta['name']}, {meta['category']}, {meta['max_tier']}层)")
        else:
            with io.open(target, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(content)
        generated += 1
    print(f"生成 {generated} 本（dry={dry}）；跳过人工 {skipped_manual}、NPC {skipped_npc}、"
          f"无层级描述 {skipped_notier}")
    if not dry:
        print(f"脚本目录现有：{len([f for f in os.listdir(scripts_dir) if f.endswith('.lua')])} 个 lua")


if __name__ == "__main__":
    res1 = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RES1
    scripts = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_SCRIPTS
    main(res1, os.path.abspath(scripts), "--dry" in sys.argv)
