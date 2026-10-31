#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从客户端文本资源 text/chineses/*.idres 抽取结构化知识。

输入：resources/modern/text/chineses/（九阴真经客户端中文文本，UTF-8/UTF-16 混合）
输出：docs/text-extract/
  data/items.jsonl.gz     道具作用表（id/category/name/desc/params）
  data/npcs.jsonl.gz      NPC 作用表（id/kind/name/clientinfo/talk 摘录）
  data/buffs.jsonl.gz     状态(Buff)效果表（id/name/desc/解析出的数值参数）
  data/skills.jsonl.gz    技能效果表（id/category/name/desc/数值参数）
  data/rules_numeric.json 规则数值统计（buff/技能/道具数值分布）
  data/system_rules.json  系统规则原文（systemhelp/九阴志/error/触发事件/场景）
  data/_context_stats.json 分类统计（文档生成用）

key 关联规则（勘察结论）：
  道具名 stringname.idres:xxx  ↔  描述 desc.idres:desc_xxx（desc2/desc3 覆盖）
  Buff 名 buf_xxx/buff_xxx     ↔  描述 string_buff_desc.idres:desc_buf_xxx_<等级>（含连字符变体）
  技能名 skill_/CS_/jn_/wuji_  ↔  描述 string_skill_desc.idres:desc_<同名>
  NPC 对话 string_npctalk.idres:task_<小写npckey>_<序号>

用法：python tools/text-extract/extract_text.py [资源目录] [输出目录]
"""
import gzip
import json
import os
import re
import sys
from collections import Counter, OrderedDict, defaultdict

DEFAULT_SRC = r"E:/Workspace/九阴服务端/resources/modern/text/chineses"
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "text-extract")

# ---------------------------------------------------------------- idres 解析

def load_idres(path):
    """key=value 文本；编码自动探测（utf-8-sig / utf-16 / gbk）；同文件内首见优先。"""
    raw = open(path, "rb").read()
    text = None
    for enc in ("utf-8-sig", "utf-16", "gbk"):
        try:
            text = raw.decode(enc)
            break
        except (UnicodeDecodeError, UnicodeError):
            continue
    if text is None:
        text = raw.decode("utf-8", errors="replace")
    d = OrderedDict()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("//") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        if k and k not in d:
            d[k] = v.strip()
    return d


def load_merged(files):
    """按文件顺序合并，后加载的同名 key 覆盖前者（desc3 > desc2 > desc）。"""
    d = OrderedDict()
    for f in files:
        d.update(load_idres(f))
    return d

# ---------------------------------------------------------------- 分类映射（均忽略大小写）

ITEM_CATEGORIES = [
    (r"^desc_", "描述文本(name文件冗余)"),
    (r"^book_", "秘籍/武学书"),
    (r"^tbook_", "阵法书"),
    (r"^ng_", "内功"),
    (r"^jm_", "经脉"),
    (r"^xw_", "穴位"),
    (r"^sword|^ssword|^blade|^sblade|^thorn|^sthorn|^cosh|^lstuff",
     "武器(单剑/双剑/刀/刺/棍)"),
    (r"^hw_", "暗器/机关道具"),
    (r"^tab_[bg]_", "装备图纸(时装)"),
    (r"^tab_", "装备图纸"),
    (r"^cloth|^helmet|^pants|^shoes|^wrist|^leg_|^leg\d|^amulet|^ring|^ear",
     "装备(衣帽裤鞋腕/绑腿/首饰)"),
    (r"^treasure_|^tg_", "宝物/古董"),
    (r"^box_", "礼包/宝箱"),
    (r"^tdropbox", "鉴定宝物盒"),
    (r"^taskitem_", "任务道具"),
    (r"^item_", "普通道具"),
    (r"^hcitem_|^hc_", "商城/活动道具"),
    (r"^card_", "卡片"),
    (r"^useitem", "使用类道具"),
    (r"^key_", "任务关键物/钥匙"),
    (r"^yaopin_", "药品"),
    (r"^duyao_", "毒药"),
    (r"^pz_", "配方"),
    (r"^jn_", "技能书/技能"),
    (r"^sd_", "修炼丹药"),
    (r"^food|^jiu_|^cai_", "食物/酒"),
    (r"^model_", "模型/时装"),
    (r"^hireshop", "雇佣商店"),
    (r"^qy_", "奇遇道具"),
    (r"^home_", "家园道具"),
    (r"^gift", "礼物"),
    (r"^hidden", "隐藏道具"),
    (r"^shop_|^cs_\d", "商店/页签文本"),
    (r"^ini", "场景配置名"),
]

NPC_KINDS = [
    (r"^WorldNpc", "世界NPC"),
    (r"^NpcClone|^npcclone", "副本克隆NPC"),
    (r"^FunnpcClone|^funnpccl", "功能克隆NPC"),
    (r"^GuildBuild|^GB_", "帮会建筑/帮会NPC"),
    (r"^Gather", "采集点/采集物"),
    (r"^boss|^BossBox", "Boss/掉落宝箱"),
    (r"^EventNpc|^eventnpc|^Event\d", "事件触发点"),
    (r"^hunter_", "猎杀目标"),
    (r"^AIFuncNpc", "AI功能NPC"),
    (r"^newworld|^newmp", "新世界NPC"),
    (r"^home_", "家园物件"),
    (r"^mon", "场景怪物"),
    (r"^npcclonetl", "爬塔副本NPC"),
    (r"^FarmlandNpc", "农田NPC"),
    (r"^HireShopNpc", "雇佣商店NPC"),
    (r"^FuncNpc", "功能NPC"),
    (r"^Convoy", "护送/镖车NPC"),
    (r"^BoxNpc|^boxtg|^BoxTG", "箱子/宝箱NPC"),
    (r"^MacClone", "建筑克隆"),
    (r"^Summon", "召唤物"),
    (r"^khd", "快活岛NPC"),
    (r"^wssz", "无双山庄NPC"),
    (r"^JlgNpc|^XypNpc", "门派据点NPC"),
    (r"^qy_", "奇遇NPC"),
    (r"^ly\d|^jl\d|^cd\d|^sz\d|^yyz\d|^npc_|^NPC_", "城市/剧情功能NPC"),
]

SKILL_CATEGORIES = [
    (r"^CS_", "玩家武学招式"),
    (r"^Npc_", "NPC技能"),
    (r"^wuji_", "绝技/武极"),
    (r"^jn_", "江湖技能"),
    (r"^skill_", "通用技能"),
    (r"^clone", "副本技能"),
    (r"^boss", "Boss技能"),
]

def classify(key, table):
    for pat, label in table:
        if re.match(pat, key, re.I):
            return label
    return "其他"

# ---------------------------------------------------------------- 数值规则抽取

NUM_PATTERNS = OrderedDict([
    ("duration_sec", re.compile(r"持续(?:时间)?为?\s*(\d+(?:\.\d+)?)\s*秒")),
    ("interval_sec", re.compile(r"每(?:隔)?\s*(\d+(?:\.\d+)?)\s*秒")),
    ("layers", re.compile(r"(\d+)\s*层")),
    ("cooldown_sec", re.compile(r"冷却(?:时间)?\s*(\d+(?:\.\d+)?)\s*秒")),
    ("hp_recover", re.compile(r"回复?\s*(\d+(?:\.\d+)?)(?:点|%|点气血)?\s*(?:气血|生命)")),
    ("damage_pct", re.compile(r"造成\s*(?:相当于)?[^，。;；]{0,12}?(\d+(?:\.\d+)?)\s*%")),
    ("prob_pct", re.compile(r"(\d+(?:\.\d+)?)\s*%\s*(?:的)?(?:几率|概率)|(?:几率|概率)[^。；;]{0,6}?(\d+(?:\.\d+)?)\s*%")),
    ("meter", re.compile(r"(\d+(?:\.\d+)?)\s*[米Mm]\b")),
    ("times", re.compile(r"(\d+)\s*次")),
])

def extract_params(text):
    """从效果描述中抽取常见数值参数。返回 {参数名: [值,...]}，仅保留有值的。"""
    if not text:
        return {}
    out = {}
    for name, pat in NUM_PATTERNS.items():
        vals = []
        for m in pat.finditer(text):
            v = next(g for g in m.groups() if g is not None)
            try:
                fv = float(v)
                vals.append(int(fv) if fv == int(fv) else fv)
            except ValueError:
                pass
        if vals:
            out[name] = vals[:8]
    return out

# ---------------------------------------------------------------- Buff 关联

# string_buff_desc.idres 里描述 key 的二级前缀（buf_/buff_ 之外的特殊命名空间）
BUFF_DESC_SPACES = ("buf", "buff", "mind", "wuji", "eqp", "itmbuf", "itm", "itembuf",
                    "fwz", "chaoswar", "tlcard", "xcc", "js", "xdm", "buffer",
                    "clone", "jf", "eternanightwar", "Buff", "Buf")

def norm(key):
    """统一连字符/下划线与小写，用于名字 key 与描述 key 的对齐。"""
    return key.replace("-", "_").lower()


def build_buff_index(buffd):
    """返回 {规范化base_id: {"levels": {等级: 描述文本}}}。
    desc 形态：desc_<base>_<N>（N 为内功/心法等级），base 可能含连字符。
    """
    idx = defaultdict(dict)
    pat = re.compile(r"^desc_(.+?)_(\d+)$")
    for k, v in buffd.items():
        if not k.startswith("desc_"):
            continue
        rest = k[5:]
        m = pat.match(k)
        if m:
            base, lv = m.group(1), int(m.group(2))
        else:
            base, lv = rest, 0
        idx[norm(base)].setdefault(lv, v)
    return idx

# ---------------------------------------------------------------- 主流程

def main(src_dir, out_dir):
    src = lambda f: os.path.join(src_dir, f)
    data_dir = os.path.join(out_dir, "data")
    os.makedirs(data_dir, exist_ok=True)
    stats = {}

    print("加载 idres ...")
    names = load_idres(src("stringname.idres"))
    npc_names = load_idres(src("stringname_npc.idres"))
    desc = load_merged([src("desc.idres"), src("desc2.idres"), src("desc3.idres")])
    buffd = load_idres(src("string_buff_desc.idres"))
    skilld = load_idres(src("string_skill_desc.idres"))
    talk = load_idres(src("string_npctalk.idres"))
    clientinfo = load_idres(src("string_npcclientinfo.idres"))
    scene = load_idres(src("scene.idres"))
    cond = load_idres(src("string_conditiondesc.idres"))
    sysinfo = load_idres(src("systeminfo.idres"))
    helpd = load_idres(src("systemhelp.idres"))
    jzy = load_idres(src("jiuyinzhi.idres"))
    errmsg = load_idres(src("error_msg.idres"))
    trig = load_idres(src("trigger_event.idres"))

    def gz_jsonl(name, rows):
        p = os.path.join(data_dir, name)
        n = 0
        with gzip.open(p, "wt", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
                n += 1
        stats[name] = n
        print(f"  {name}: {n} 行")
        return n

    # ---- 1) 道具作用表 -------------------------------------------------
    print("抽取道具 ...")
    item_rows = []
    for k, v in names.items():
        d = desc.get("desc_" + k, "")
        item_rows.append({
            "id": k, "category": classify(k, ITEM_CATEGORIES),
            "name": v, "desc": d, "params": extract_params(d),
        })
    gz_jsonl("items.jsonl.gz", item_rows)

    # ---- 2) NPC 作用表 -------------------------------------------------
    print("抽取NPC ...")
    talk_by_npc = defaultdict(list)
    for tk, tv in talk.items():
        if tk.startswith("task_"):
            base = re.sub(r"_[0-9]+$", "", tk[5:])
            talk_by_npc[norm(base)].append(tv)
    ci_index = defaultdict(dict)
    for ck, cv in clientinfo.items():
        m = re.match(r"^(usepart|planed|story|exility|usetype)_(.+)$", ck)
        if m:
            ci_index[norm(m.group(2))][m.group(1)] = cv

    npc_rows = []
    for k, v in npc_names.items():
        ci = ci_index.get(norm(k), {})
        t = talk_by_npc.get(norm(k), [])
        row = {"id": k, "kind": classify(k, NPC_KINDS), "name": v}
        if ci:
            row["clientinfo"] = ci
        if t:
            row["talk_sample"] = t[:3]
        npc_rows.append(row)
    gz_jsonl("npcs.jsonl.gz", npc_rows)

    # ---- 3) Buff 效果表 ------------------------------------------------
    # 以 base id 为主键：名字 key（buf_/buff_ 等）∪ 描述 key 去掉 desc_/_N 的 base。
    print("抽取Buff ...")
    bidx = build_buff_index(buffd)
    name_bases = OrderedDict()   # norm(base) -> 原始名字 key
    for k in buffd:
        if k.startswith("desc_"):
            continue
        name_bases[norm(k)] = k

    buff_rows = []
    for nb, orig in name_bases.items():
        levels = bidx.get(nb)
        d = ""
        if levels:
            d = levels.get(0) or levels[min(levels)]
        buff_rows.append({
            "id": orig, "name": buffd[orig] if buffd[orig] != orig else "",
            "desc": d, "params": extract_params(d),
        })
    # 只有描述、没有名字 key 的 buff（如 desc_mind_* 心法等级描述）
    seen = set(r and norm(r["id"]) for r in buff_rows)
    for nb, levels in bidx.items():
        if nb in name_bases or nb in seen:
            continue
        first = re.match(r"^(" + "|".join(BUFF_DESC_SPACES) + r")", nb)
        if not first:
            continue
        d0 = levels.get(0) or levels[min(levels)]
        buff_rows.append({"id": nb, "name": "", "desc": d0,
                          "params": extract_params(d0)})
    gz_jsonl("buffs.jsonl.gz", buff_rows)

    # ---- 4) 技能效果表 -------------------------------------------------
    print("抽取技能 ...")
    sm = OrderedDict()
    for k, v in skilld.items():
        if k.startswith("desc_"):
            sm.setdefault(k[5:], {})["desc"] = v
        else:
            sm.setdefault(k, {})["name"] = v
    skill_rows = [{
        "id": k, "category": classify(k, SKILL_CATEGORIES),
        "name": e.get("name", ""), "desc": e.get("desc", ""),
        "params": extract_params(e.get("desc", "")),
    } for k, e in sm.items()]
    gz_jsonl("skills.jsonl.gz", skill_rows)

    # ---- 5) 规则数值统计 ----------------------------------------------
    print("统计规则数值 ...")
    def param_stats(rows):
        agg = {}
        for row in rows:
            for pname, vals in row["params"].items():
                bucket = agg.setdefault(pname, Counter())
                for v in vals:
                    bucket[v] += 1
        return {p: {"distinct": len(c), "top": c.most_common(15)} for p, c in agg.items()}

    rules_numeric = {
        "buff_param_stats": param_stats(buff_rows),
        "skill_param_stats": param_stats(skill_rows),
        "item_param_stats": param_stats([r for r in item_rows if r["params"]]),
        "counts": stats,
    }
    with open(os.path.join(data_dir, "rules_numeric.json"), "w", encoding="utf-8") as fh:
        json.dump(rules_numeric, fh, ensure_ascii=False, indent=1)

    # ---- 6) 采集物图鉴 --------------------------------------------------
    # clientinfo：story=功效背景 usepart=使用部位 planed=产地 usetype=出没地
    print("抽取采集物图鉴 ...")
    gather_codex = {}
    for ck, cv in clientinfo.items():
        m = re.match(r"^(usepart|planed|story|usetype)_(cj_.+)$", ck)
        if m:
            gather_codex.setdefault(m.group(2), {})[m.group(1)] = cv
    gather_rows = [{"id": k, **v} for k, v in gather_codex.items()]
    gz_jsonl("gather_codex.jsonl.gz", gather_rows)

    # ---- 7) NPC 对话全表 ------------------------------------------------
    gz_jsonl("talks.jsonl.gz", ({"id": k, "text": v} for k, v in talk.items()))

    # ---- 8) 系统规则原文 ------------------------------------------------
    with open(os.path.join(data_dir, "system_rules.json"), "w", encoding="utf-8") as fh:
        json.dump({"systemhelp": helpd, "jiuyinzhi": jzy, "error_msg": errmsg,
                   "trigger_event": trig, "scene": scene}, fh, ensure_ascii=False, indent=1)

    summary = {
        "counts": stats,
        "item_categories": Counter(r["category"] for r in item_rows).most_common(),
        "npc_kinds": Counter(r["kind"] for r in npc_rows).most_common(),
        "item_with_desc": sum(1 for r in item_rows if r["desc"]),
        "npc_with_talk": sum(1 for r in npc_rows if r.get("talk_sample")),
        "npc_with_clientinfo": sum(1 for r in npc_rows if r.get("clientinfo")),
        "buff_with_desc": sum(1 for r in buff_rows if r["desc"]),
        "skill_with_desc": sum(1 for r in skill_rows if r["desc"]),
        "cond_count": len(cond), "sysinfo_count": len(sysinfo),
        "talk_count": len(talk), "gather_codex_count": len(gather_rows),
    }
    with open(os.path.join(data_dir, "_context_stats.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=1)
    print("完成。输出目录:", os.path.abspath(out_dir))


if __name__ == "__main__":
    src_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    out_dir = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT)
    main(src_dir, out_dir)
