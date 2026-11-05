#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 share/skill ini 表生成全技能效果汇总表 scripts/skill/effects_data.lua。

数据源与 Java CombatSkillCatalog 同一份表（语义逐条复刻，保证 Lua 权威值与
INI 编译兜底逐位一致——verify_skill_scripts.py 对照 jsonl 钉死）：
  compileSkillDamage   damage_calculate r 段第 3 段求和（正值计段）
  compileExactSkillBuff buf_<技能ID> → buff_new StaticData/LifeTime →
                       buff_static IsDamage（harmful 分流）→ buff_varprop
                       按级 LifeTime 覆盖 → 24h 兜底

产出单文件 effects_data.lua（LuaScriptService 启动装载为全局 SkillEffectsData；
逐技能 <ID>.lua M.compile 优先级更高，手写脚本不冲突）。条目形态：

  E["<技能ID>"] = {
    damage      = { amount = 540, waves = 4 },          -- L1 基准（无伤害技不发）
    self_buffs  = { { config_id = "buf_...", static_data = 6069,
                      level = 1, life_ms = 45000 } },   -- harmful=false 精确 buff
    target_effects = { { config_id = "buf_...", static_data = 7001,
                          level = 1, life_ms = 8000, control = true } },  -- harmful=true
    by_level    = { [5] = { damage_amount = 860, self_life_ms = 60000 } }, -- 仅差集
  }

幂等：内容确定（排序键控），重复运行覆盖同名文件；--dry 只打印统计不落盘。
用法：python tools/skill-data/gen_skill_scripts.py [share/skill根] [输出lua] [--dry]
"""
import argparse
import io
import os
import re
import sys

DEFAULT_SHARE_SKILL = r"F:/Game/Online/9yin/9yin_server1/resources/modern/share/skill"
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "skill",
                           "effects_data.lua")

DAY_MS = 24 * 60 * 60 * 1000


def parse_ini(path):
    """逐节保留重复键序（damage_calculate 的多 r 字段依赖全序列；键小写对齐 Java Section）。"""
    sections = {}
    current = None
    with io.open(path, "r", encoding="latin-1", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\r\n")
            match = re.match(r"\[([^\]]+)\]", line)
            if match:
                current = match.group(1)
                sections.setdefault(current, [])
                continue
            if current and "=" in line:
                key, value = line.split("=", 1)
                sections[current].append((key.strip().lower(), value))
    return sections


def last(fields, key):
    """Go iniValue/Java Section.last：同键多次出现取最后一个。"""
    found = ""
    for name, value in fields:
        if name == key:
            found = value
    return found


def all_of(fields, key):
    return [value for name, value in fields if name == key]


def int32(fields, key):
    try:
        return int(float(last(fields, key).strip()))
    except (ValueError, TypeError):
        return 0


def compile_damage(damage_sections, damage_calc_id):
    """Java compileSkillDamage：r 值逗号第 3 段求和 → (编译伤害, 正段数)。"""
    fields = damage_sections.get(str(damage_calc_id))
    if not fields:
        return 0, 0
    total = 0.0
    waves = 0
    for value in all_of(fields, "r"):
        parts = value.split(",")
        if len(parts) < 3:
            continue
        try:
            segment = float(parts[2].strip())
        except ValueError:
            continue
        if segment > 0:
            total += segment
            waves += 1
    return int(round(total)), waves


def exact_buff(buff_new, buff_static, buff_var, skill_id, level):
    """Java compileExactSkillBuff → (config_id, static_data, life_ms, harmful)；无精确 buff 返回 None。"""
    config_id = "buf_" + skill_id
    fields = buff_new.get(config_id)
    if not fields:
        return None
    static_data = int32(fields, "staticdata")
    if static_data <= 0:
        return None
    static_fields = buff_static.get(str(static_data))
    lifetime = int32(fields, "lifetime")
    if static_fields:
        minimum = int32(static_fields, "minvarpropno")
        maximum = int32(static_fields, "maxvarpropno")
        for var_id in range(max(minimum, 1), maximum + 1):
            var_fields = buff_var.get(str(var_id))
            if not var_fields or int32(var_fields, "level") != level:
                continue
            milliseconds = int32(var_fields, "lifetime")
            if milliseconds > 0:
                lifetime = milliseconds
            break
    if lifetime <= 0:
        lifetime = DAY_MS
    harmful = static_fields is not None and int32(static_fields, "isdamage") != 0
    return config_id, static_data, lifetime, harmful


def lua_str(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    parser = argparse.ArgumentParser(description="生成 scripts/skill/effects_data.lua")
    parser.add_argument("share", nargs="?", default=DEFAULT_SHARE_SKILL,
                        help="share/skill 表目录")
    parser.add_argument("out", nargs="?", default=DEFAULT_OUT, help="输出 lua 文件")
    parser.add_argument("--dry", action="store_true", help="只统计不写盘")
    args = parser.parse_args()

    skill_new = parse_ini(os.path.join(args.share, "skill_new.ini"))
    skill_static = parse_ini(os.path.join(args.share, "skill_static.ini"))
    normal_var = parse_ini(os.path.join(args.share, "skill_normal_varprop.ini"))
    lock_var = parse_ini(os.path.join(args.share, "skill_lock_varprop.ini"))
    damage_calc = parse_ini(os.path.join(args.share, "damage_calculate.ini"))
    buff_new = parse_ini(os.path.join(args.share, "buff_new.ini"))
    buff_static = parse_ini(os.path.join(args.share, "buff_static.ini"))
    buff_var = parse_ini(os.path.join(args.share, "buff_varprop.ini"))

    entries = {}
    stats = {"damage": 0, "self_buff": 0, "target_ctrl": 0, "by_level": 0}
    for skill_id in sorted(skill_new):
        fields = skill_new[skill_id]
        script = last(fields, "script").lower()
        if script not in ("skillnormal", "skilllock"):
            continue
        static_fields = skill_static.get(str(int32(fields, "staticdata")))
        if not static_fields:
            continue
        var_table = lock_var if script == "skilllock" else normal_var

        # 逐级编译（伤害/租约随级变；static_data 固定）。等级枚举 = 扫
        # [Min,Max]VarPropNo 全区间按 Level 归组、首行胜出（对齐 Java
        # findVarProp 首匹配；等级有缺口也不断链）。
        levels = {}
        minimum = max(int32(static_fields, "minvarpropno"), 1)
        maximum = int32(static_fields, "maxvarpropno")
        for var_id in range(minimum, maximum + 1):
            var_fields = var_table.get(str(var_id))
            if not var_fields:
                continue
            level = int32(var_fields, "level")
            if level in levels or level < 1:
                continue
            amount, waves = compile_damage(
                damage_calc, int32(var_fields, "damagecalculate"))
            buff = exact_buff(buff_new, buff_static, buff_var, skill_id, level)
            levels[level] = (amount, waves, buff)
        if not levels:
            continue

        base_level = min(levels)
        base_amount, base_waves, base_buff = levels[base_level]
        entry_lines = []
        if base_amount > 0:
            entry_lines.append("    damage = { amount = %d, waves = %d },"
                               % (base_amount, base_waves))
            stats["damage"] += 1
        if base_buff:
            config_id, static_data, lifetime, harmful = base_buff
            if harmful:
                entry_lines.append(
                    "    target_effects = { { config_id = %s, static_data = %d,"
                    " level = 1, life_ms = %d, control = true } },"
                    % (lua_str(config_id), static_data, lifetime))
                stats["target_ctrl"] += 1
            else:
                entry_lines.append(
                    "    self_buffs = { { config_id = %s, static_data = %d,"
                    " level = 1, life_ms = %d } },"
                    % (lua_str(config_id), static_data, lifetime))
                stats["self_buff"] += 1

        by_level = {}
        for level in sorted(levels):
            if level == base_level:
                continue
            amount, waves, buff = levels[level]
            diff = []
            if amount != base_amount:
                diff.append("damage_amount = %d" % amount)
            if waves != base_waves:
                diff.append("damage_waves = %d" % waves)
            if buff and base_buff:
                if buff[2] != base_buff[2]:
                    side = "target_life_ms" if buff[3] else "self_life_ms"
                    diff.append("%s = %d" % (side, buff[2]))
            if diff:
                by_level[level] = "{ " + ", ".join(diff) + " }"
        if by_level:
            entry_lines.append("    by_level = {")
            for level in sorted(by_level):
                entry_lines.append("      [%d] = %s," % (level, by_level[level]))
            entry_lines.append("    },")
            stats["by_level"] += 1

        if entry_lines:
            entries[skill_id] = entry_lines

    out = io.StringIO()
    out.write("-- 本文件由 tools/skill-data/gen_skill_scripts.py 生成（幂等可重跑，勿手改）。\n")
    out.write("-- 数据源 share/skill ini（与 Java CombatSkillCatalog 同源同语义）；\n")
    out.write("-- 逐技能 <技能ID>.lua M.compile 优先级更高（手写覆盖）；无条目技能走 INI 默认编译。\n")
    out.write("local E = {}\n\n")
    for skill_id in sorted(entries):
        out.write("E[%s] = {\n" % lua_str(skill_id))
        for line in entries[skill_id]:
            out.write(line + "\n")
        out.write("}\n\n")
    out.write("return E\n")

    if args.dry:
        print("dry: entries=%d damage=%d self_buff=%d target_ctrl=%d by_level=%d"
              % (len(entries), stats["damage"], stats["self_buff"],
                 stats["target_ctrl"], stats["by_level"]))
        return

    out_path = os.path.normpath(args.out)
    directory = os.path.dirname(out_path)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(out.getvalue())
    print("wrote %s: entries=%d damage=%d self_buff=%d target_ctrl=%d by_level=%d"
          % (out_path, len(entries), stats["damage"], stats["self_buff"],
             stats["target_ctrl"], stats["by_level"]))


if __name__ == "__main__":
    sys.exit(main())
