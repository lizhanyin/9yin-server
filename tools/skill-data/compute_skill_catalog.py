#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""res1 技能数据计算器：share/skill 全链编译（对齐 Java CombatSkillCatalog 语义）。

输入（仅 res1）：
  share/skill/*.ini  skill_new / skill_static / skill_(normal|lock)_varprop /
    skill_consume / damage_calculate / attack_hitshape / attack_targetshape /
    buff_new / buff_static / buff_varprop / skill_use_limit
  ini/action/zhaoshi_player*.ini  动作时间线（首段时长/后续段/多段判定帧）
  share/skill/jingmai/wuji*.ini  武技表（wuji_learn/wuji_quality/wuji_activate/wuji.ini）

输出（docs/text-extract/data/）：
  skill_catalog.jsonl.gz      每 技能×等级 一行的编译结果（CD/消耗/伤害/形状/精确buff/武技族）
  wuji_catalog.json           武技目录（学习表/品质/激活动作/挂接技能 629 条 LevelupRelevance）
  neigong_effect_index.json   内功书索引（179 本：名称/门派/层级描述/参数/脚本覆盖）

与 Java 口径一致的关键语义：
  - INI 重复键保留序（damage_calculate 多 r 行）；同名键取最后值。
  - varprop 按 skill_static [Min,Max]VarPropNo 区间内 Level 精确匹配；SkillLock 用 lock 表。
  - 伤害 = damage_calculate r 行第 3 段正值求和 / 正段数（damageRatio = 和/100/段数）。
  - 目标模式 HitShape：0/缺→SELECTED（伤害技）；1 圆/2 选中区/3,9,10 扇形/4 矩形/11,12 地面。
  - 多段判定：trigger_custom 行效果名含 _hurt 优先；否则核心名前缀（尾段去数字取前 3 字母）
    开头的帧；与技能同名（施法帧）剔除。30fps 换算毫秒。
  - 精确 buff 租约：buf_<技能ID> → buff_new LifeTime → buff_varprop 同级覆盖 → 24h 兜底。
  - 武技族：LevelupRelevance 以 wuji_ 开头；+PackCategory=2（怒招）为武技连击段。
  - res1 无 skill_init.ini：修炼总量(fill/total)与 pause_time 不计算（输出 null 并在此注明）。

用法：python tools/skill-data/compute_skill_catalog.py [res1根] [输出目录]
"""
import gzip
import io
import json
import os
import re
import sys
from collections import OrderedDict

DEFAULT_RES1 = r"E:/Workspace/project/game/res1"
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "text-extract", "data")

DEFAULT_BUFF_LEASE_MS = 24 * 60 * 60 * 1000
ZHAOSHI_FILES = ("zhaoshi_player.ini", "zhaoshi_player_2.ini",
                 "zhaoshi_player_dodge.ini", "zhaoshi_player_parry.ini")


# ---------------------------------------------------------------- INI 解析

class Section:
    """字段保序（重复键全保留）；last=同键取最后。"""

    def __init__(self):
        self.fields = []

    def add(self, key, value):
        self.fields.append((key.strip().lower(), value))

    def last(self, key):
        found = ""
        for k, v in self.fields:
            if k == key.lower():
                found = v
        return found

    def all(self, key):
        return [v for k, v in self.fields if k == key.lower()]

    def int32(self, key):
        try:
            return int(float(self.last(key).strip()))
        except (ValueError, TypeError):
            return 0

    def f32(self, key):
        try:
            return float(self.last(key).strip())
        except (ValueError, TypeError):
            return 0.0


def read_ini(path, into):
    if not os.path.isfile(path):
        return
    current = None
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("//") or line.startswith(";"):
                continue
            if line.startswith("[") and line.endswith("]"):
                name = line[1:-1].strip()
                current = into.setdefault(name, Section())
                continue
            if current is None or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip():
                current.add(key, value.strip())


def load_tables(res1):
    t = {}
    skill = os.path.join(res1, "share", "skill")
    for name, key in (("skill_new.ini", "skillNew"), ("skill_static.ini", "skillStatic"),
                      ("skill_normal_varprop.ini", "normalVar"), ("skill_lock_varprop.ini", "lockVar"),
                      ("skill_consume.ini", "consume"), ("damage_calculate.ini", "damage"),
                      ("attack_hitshape.ini", "hitShape"), ("attack_targetshape.ini", "targetShape"),
                      ("buff_new.ini", "buffNew"), ("buff_static.ini", "buffStatic"),
                      ("buff_varprop.ini", "buffVar"), ("skill_use_limit.ini", "useLimit")):
        t[key] = OrderedDict()
        read_ini(os.path.join(skill, name), t[key])
    t["actions"] = OrderedDict()
    action_root = os.path.join(res1, "ini", "action")
    for name in ZHAOSHI_FILES:
        read_ini(os.path.join(action_root, name), t["actions"])
    return t


# ---------------------------------------------------------------- 编译（对齐 Java）

def frame_ms(end_frame):
    return int(end_frame / 30.0 * 1000)


def skill_core_prefix(skill_id):
    for segment in reversed(skill_id.split("_")):
        if re.search(r"[A-Za-z].*\d", segment) or re.search(r"\d.*[A-Za-z]", segment):
            if re.search(r"[A-Za-z]", segment) and re.search(r"\d", segment):
                letters = re.sub(r"[^A-Za-z]", "", segment)
                return letters[:3] if len(letters) >= 3 else letters
    return ""


def find_varprop(tables, static_fields, script, level):
    var_table = tables["lockVar"] if script.lower() == "skilllock" else tables["normalVar"]
    for vid in range(max(static_fields.int32("MinVarPropNo"), 1), static_fields.int32("MaxVarPropNo") + 1):
        fields = var_table.get(str(vid))
        if fields is not None and fields.int32("Level") == level:
            return vid, fields
    return None, None


def compile_damage(damage_fields):
    if damage_fields is None:
        return 0, 0
    total, waves = 0.0, 0
    for raw in damage_fields.all("r"):
        parts = raw.split(",")
        if len(parts) < 3:
            continue
        try:
            value = float(parts[2])
        except ValueError:
            continue
        if value > 0:
            total += value
            waves += 1
    return int(total), waves


HIT_SHAPE_MODE = {1: "caster_circle", 2: "selected_area", 11: "ground_area",
                  3: "caster_sector", 9: "caster_sector", 10: "caster_sector",
                  4: "caster_rectangle", 12: "ground_rectangle"}


def compile_target_mode(hit_shape, is_damage):
    if not is_damage or hit_shape is None:
        return "self", 0.0, 0.0, 0.0
    shape = hit_shape.int32("HitShape")
    mode = HIT_SHAPE_MODE.get(shape, "selected")
    radius = hit_shape.f32("HitShapePara2") if mode not in ("self", "selected") else 0.0
    angle = hit_shape.f32("HitShapePara1") if mode == "caster_sector" else 0.0
    width = hit_shape.f32("HitShapePara1") if mode in ("caster_rectangle", "ground_rectangle") else 0.0
    return mode, radius, angle, width


def compile_actions(fields, skill_id):
    if fields is None:
        return "", 0, [], []
    raw = []
    hurt_frames, core_frames = [], []
    core_prefix = skill_core_prefix(skill_id)
    for key, value in fields.fields:
        if key.isdigit():
            parts = value.split(";")
            if len(parts) < 5 or not parts[0].strip():
                continue
            try:
                raw.append((int(key), parts[0].strip(), float(parts[2]), float(parts[3])))
            except (ValueError, IndexError):
                continue
        elif key.startswith("trigger_custom_"):
            parts = value.split(";")
            if len(parts) < 4 or parts[1].strip() != "trigger_effect":
                continue
            effect = parts[3].strip()
            if not effect or effect == skill_id:
                continue
            try:
                frame = float(parts[2])
            except ValueError:
                continue
            if frame < 0:
                continue
            if "_hurt" in effect:
                hurt_frames.append(frame_ms(frame))
            elif core_prefix and effect.startswith(core_prefix):
                core_frames.append(frame_ms(frame))
    hit_frames = sorted(hurt_frames if hurt_frames else core_frames)
    raw.sort(key=lambda r: r[0])
    if not raw:
        return "", 0, [], hit_frames
    duration = frame_ms(raw[0][2])
    followups, at = [], 0
    for i in range(1, len(raw)):
        at += frame_ms(raw[i - 1][2]) - int(max(raw[i][3], 0) * 1000)
        followups.append({"action": raw[i][1], "at_ms": at, "duration_ms": frame_ms(raw[i][2])})
    return raw[0][1], duration, followups, hit_frames


def compile_exact_buff(tables, skill_id, level):
    buff_new = tables["buffNew"].get("buf_" + skill_id)
    if buff_new is None:
        return None
    static_data = buff_new.int32("StaticData")
    if static_data <= 0:
        return None
    static_fields = tables["buffStatic"].get(str(static_data))
    lifetime = buff_new.int32("LifeTime")
    if static_fields is not None:
        for vid in range(max(static_fields.int32("MinVarPropNo"), 1),
                         static_fields.int32("MaxVarPropNo") + 1):
            fields = tables["buffVar"].get(str(vid))
            if fields is None or fields.int32("Level") != level:
                continue
            if fields.int32("LifeTime") > 0:
                lifetime = fields.int32("LifeTime")
            break
    if lifetime <= 0:
        lifetime = DEFAULT_BUFF_LEASE_MS
    harmful = static_fields is not None and static_fields.int32("IsDamage") != 0
    return {"config_id": "buf_" + skill_id, "static_data": static_data,
            "lifetime_ms": lifetime, "harmful": bool(harmful)}


def compile_weapon_types(tables, use_limit_id):
    if not use_limit_id:
        return []
    fields = tables["useLimit"].get(use_limit_id.strip())
    if fields is None:
        return []
    codes = set()
    for raw in fields.all("r"):
        parts = raw.split(",")
        if len(parts) < 3 or parts[1].strip() != "ItemType":
            continue
        for code in parts[2].split("|"):
            try:
                codes.add(int(code))
            except ValueError:
                continue
    return sorted(codes)


def compile_skill(tables, skill_id, level):
    new_fields = tables["skillNew"].get(skill_id)
    if new_fields is None:
        return None
    static_data = new_fields.int32("StaticData")
    static_fields = tables["skillStatic"].get(str(static_data))
    if static_fields is None or not static_fields.fields:
        return None
    script = new_fields.last("script")
    var_no, var_prop = find_varprop(tables, static_fields, script, level)
    if var_prop is None:
        return None
    consume = tables["consume"].get(str(var_prop.int32("Consume")))
    damage, waves = compile_damage(tables["damage"].get(str(var_prop.int32("DamageCalculate"))))
    hit_shape = tables["hitShape"].get(str(var_prop.int32("HitShapePkg")))
    is_damage = static_fields.int32("IsDamage") != 0
    mode, radius, angle, width = compile_target_mode(hit_shape, is_damage)
    action, duration, followups, hit_frames = compile_actions(
        tables["actions"].get(skill_id), skill_id)
    public_cd = var_prop.int32("AddCoolDownTime") or new_fields.int32("AddCoolDownTime")
    personal_cd = var_prop.int32("CoolDownTime")
    if public_cd <= 0:
        public_cd = personal_cd
    exact = compile_exact_buff(tables, skill_id, level)
    relevance = static_fields.last("LevelupRelevance")
    pack_category = new_fields.int32("PackCategory")
    return {
        "id": skill_id, "level": level, "static_data": static_data, "script": script,
        "taolu": static_fields.last("TaoLu"),
        "cooldown_category": static_fields.int32("CoolDownCategory"),
        "cooldown_team": static_fields.int32("CoolDownTeam"),
        "personal_cd_ms": personal_cd, "public_cd_ms": public_cd,
        "consume": {"hp": consume.int32("AConsumeHP") if consume else 0,
                    "mp": consume.int32("AConsumeMP") if consume else 0,
                    "sp": consume.int32("AConsumeSP") if consume else 0,
                    "qgp": consume.int32("AConsumeQGP") if consume else 0},
        "base_damage": damage, "damage_waves": waves,
        "damage_ratio": round(damage / 100.0 / waves, 4) if waves > 0 else 0,
        "target_mode": mode, "radius_m": radius, "sector_angle": angle, "rect_width_m": width,
        "height_diff_m": var_prop.f32("HeightDiff"),
        "can_rush": static_fields.int32("CanRush") != 0,
        "rush_range_m": static_fields.f32("RushRange"),
        "is_damage": is_damage,
        "target_type": static_fields.int32("TargetType"),
        "damage_type": static_fields.int32("DamageType"),
        "levelup_relevance": relevance, "pack_category": pack_category,
        "wuji_relevance": bool(relevance and relevance.lower().startswith("wuji_")),
        "wuji_combo": bool(relevance and relevance.lower().startswith("wuji_") and pack_category == 2),
        "lock_skill": script.lower() == "skilllock" or static_fields.int32("CanRush") != 0
                      or (static_fields.int32("TargetType") == 4 and static_fields.int32("DamageType") != 0),
        "exact_buff": exact,
        "weapon_item_types": compile_weapon_types(tables, new_fields.last("UseLimit")),
        "action": {"name": action, "duration_ms": duration, "followups": followups,
                   "hit_frame_ms": hit_frames} if action else None,
        "var_prop_no": var_no,
        # res1 无 skill_init.ini：修炼进度族字段不计算（对齐说明见脚本头）。
        "fill_total": None, "pause_time_ms": None,
    }


# ---------------------------------------------------------------- 武技目录

def load_wuji(res1):
    tables = OrderedDict()
    jingmai = os.path.join(res1, "share", "skill", "jingmai")
    for name in ("wuji.ini", "wuji_info.ini", "wuji_quality.ini", "wuji_activate.ini"):
        tables[name] = OrderedDict()
        read_ini(os.path.join(jingmai, name), tables[name])
    wuji = {"source": "res1/share/skill/jingmai/wuji*.ini", "arts": [], "reset_costs": {},
            "wear_types": {}, "wujibox_need": {}, "activates": []}
    info = tables["wuji_info.ini"]
    learn = info.get("wuji_learn")
    quality = tables["wuji_quality.ini"].get("wuji_quality")
    if learn is not None:
        for key, value in sorted(learn.fields, key=lambda kv: int(kv[0]) if kv[0].isdigit() else 1 << 30):
            parts = [p.strip() for p in value.split(",")]
            art = {"art_id": int(key), "combo_id": parts[0] if parts else "",
                   "raw": value}
            if quality is not None:
                q = quality.last(parts[0]) if parts else ""
                art["quality"] = q
            wuji["arts"].append(art)
    wear = info.get("wuji_wear_type")
    if wear is not None:
        wuji["wear_types"] = {k: v for k, v in wear.fields}
    costs = tables["wuji.ini"].get("wuji_info")
    if costs is not None:
        wuji["reset_costs"] = {k: v for k, v in costs.fields}
    box = tables["wuji.ini"].get("wujibox_need")
    if box is not None:
        wuji["wujibox_need"] = dict(box.fields)
    act = tables["wuji_activate.ini"].get("wuji_action")
    if act is not None:
        for key, value in sorted(act.fields, key=lambda kv: int(kv[0]) if kv[0].isdigit() else 1 << 30):
            parts = [p.strip() for p in value.split(",")]
            # 布局：seq, StaticData, WJ_<技能>[, StaticData2, WJ_<技能>2, ...]（成对扩展）
            variants = [{"static_data": parts[i], "buff": parts[i + 1]}
                        for i in range(1, len(parts) - 1, 2)]
            wuji["activates"].append({"action_id": int(key), "seq": parts[0] if parts else "",
                                      "variants": variants})
    return wuji


# ---------------------------------------------------------------- 内功索引

def load_neigong_index(res1, scripts_dir=None):
    # string_buff_desc：名字 key + 层级描述
    names, levels = {}, {}
    pat = re.compile(r"^desc_(buf_ng_[a-z]+_\d+)_(\d+)$")
    path = os.path.join(res1, "text", "chineses", "string_buff_desc.idres")
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\r\n")
            if line.startswith("//") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if re.match(r"^buf_ng_[a-z]+_\d+$", key):
                names[key] = value
            m = pat.match(key)
            if m and int(m.group(2)) > 0:
                levels.setdefault(m.group(1), {})[int(m.group(2))] = re.sub(r"<[^>]+>", "", value)
    # neigong.ini：门派/StaticData（QName → 书）
    qname = {}
    section = None
    current = {}
    with io.open(os.path.join(res1, "share", "skill", "neigong", "neigong.ini"),
                 "r", encoding="utf-8-sig", errors="replace") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("["):
                if current.get("QName"):
                    qname[current["QName"]] = current
                section = s[1:-1].strip()
                current = {"section": section}
            elif "=" in s and section:
                k, v = s.split("=", 1)
                current[k.strip()] = v.strip()
    if current.get("QName"):
        qname[current["QName"]] = current
    scripts = set()
    if scripts_dir and os.path.isdir(scripts_dir):
        scripts = {f[:-4] for f in os.listdir(scripts_dir) if f.endswith(".lua")}
    books = {}
    for book, name in names.items():
        cfg = qname.get(book[4:], {})
        tiers = levels.get(book, {})
        if book.startswith("buf_ng_npc"):
            status = "npc"
        elif book in scripts:
            status = "manual"
        else:
            status = ""
        books[book] = {
            "name": name,
            "school": cfg.get("School", ""),
            "static_data": int(cfg.get("StaticData", 0) or 0),
            "tiers": tiers,
            "max_tier": max(tiers) if tiers else 0,
            "script": status,
        }
    return books


# ---------------------------------------------------------------- 主流程

def main(res1, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    tables = load_tables(res1)

    rows, skipped = [], {}
    max_level_cache = {}
    for skill_id, new_fields in tables["skillNew"].items():
        script = new_fields.last("script")
        if script.lower() not in ("skillnormal", "skilllock"):
            skipped["unsupported_script"] = skipped.get("unsupported_script", 0) + 1
            continue
        static_fields = tables["skillStatic"].get(str(new_fields.int32("StaticData")))
        if static_fields is None:
            skipped["missing_static"] = skipped.get("missing_static", 0) + 1
            continue
        var_table = tables["lockVar"] if script.lower() == "skilllock" else tables["normalVar"]
        best = 1
        for vid in range(max(static_fields.int32("MinVarPropNo"), 1),
                         static_fields.int32("MaxVarPropNo") + 1):
            fields = var_table.get(str(vid))
            if fields is not None and fields.int32("Level") > best:
                best = fields.int32("Level")
        max_level_cache[skill_id] = best
        for level in range(1, best + 1):
            row = compile_skill(tables, skill_id, level)
            if row is None:
                skipped["missing_level_varprop"] = skipped.get("missing_level_varprop", 0) + 1
                continue
            rows.append(row)

    catalog_path = os.path.join(out_dir, "skill_catalog.jsonl.gz")
    with gzip.open(catalog_path, "wt", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")

    # 武技
    wuji = load_wuji(res1)
    combo_skills = sorted({r["id"] for r in rows if r["wuji_relevance"]})
    combo_only = sorted({r["id"] for r in rows if r["wuji_combo"]})
    wuji["skills_with_relevance"] = combo_skills
    wuji["combo_skills"] = combo_only
    with io.open(os.path.join(out_dir, "wuji_catalog.json"), "w", encoding="utf-8") as fh:
        json.dump(wuji, fh, ensure_ascii=False, indent=1)

    # 内功索引
    neigong = load_neigong_index(res1, os.path.join(os.path.dirname(out_dir), "..", "..",
                                                    "scripts", "neigong"))
    with io.open(os.path.join(out_dir, "neigong_effect_index.json"), "w", encoding="utf-8") as fh:
        json.dump(neigong, fh, ensure_ascii=False, indent=1)

    manual = sum(1 for b in neigong.values() if b["script"] == "manual")
    npc = sum(1 for b in neigong.values() if b["script"] == "npc")
    print(f"技能编译：{len(max_level_cache)} 本技能，{len(rows)} 行（技能×等级）")
    print(f"跳过：{skipped}")
    print(f"武技：arts={len(wuji['arts'])} 挂接技能={len(combo_skills)} 连击怒招={len(combo_only)} "
          f"激活动作={len(wuji['activates'])}")
    print(f"内功书：{len(neigong)}（手写脚本 {manual}，NPC {npc}，缺脚本 {len(neigong) - manual - npc}）")


if __name__ == "__main__":
    res1 = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RES1
    out = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT)
    main(res1, out)
