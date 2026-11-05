# -*- coding: utf-8 -*-
"""boxgen_rebuild.py — 按箱子介绍文案重建「箱子→内容物」对照表。

背景：官方 drop_box_* 掉落包正文从未随客户端发布（data/giftbox.json note 已考证），
drops_mvp.json 里绝大多数包是合成药品阶梯（开箱=随机药品）。客户端 desc_*.idres 的
箱子描述文案（如"内含菩提子*40、佛经书卷*1"）是唯一可靠的对照关系来源。

流程：
  1. stringname.idres 建立 物品名→configId 反查（同名多条优先 tool_item.ini 存在的）；
  2. tool_item.ini 收集 script=BoxItem 节；
  3. desc/desc2/desc3.idres 取 desc_<cfg>_<页> 全页文本，定位"内含/可获得/打开后获得"
     清单段，抽 `名称*数量`（＊*×xX 分隔，数量缺省 1）；
  4. 名称归一化（全角/半角括号互试、剥"（绑定）"等后缀）后反查；
  5. data/giftbox.json 手工条目优先合入不被覆盖；
  6. 输出 box_contents.json（boxes.{cfg}=[{item,min,max}]，min=max 固定全发）
     + 未匹配报告 CSV（迭代补解析规则用）。

用法（默认参数即四副本口径，--out 指定目标运行根）：
  python -I tools/boxgen_rebuild.py \
    --text-dir  G:/pojie/ai/9yin-go-server1/resources/modern/text/chineses \
    --tool-item F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item/tool_item.ini \
    --giftbox   G:/pojie/ai/9yin-go-server1/data/giftbox.json \
    --out       F:/Game/Online/9yin/9yin_server_new/resources/modern/share/drop/box_contents.json
"""
import argparse
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 引导词：其后是内容清单段（取引导词后到句末的一段；多段都抽）
LEAD_WORDS = re.compile(
    r"(?:内含|包含|内有|可获得|将获得|将会获得|可获得|随机获得|开启后获得|开启后将获得|"
    r"打开后获得|打开后将获得|打开后可|使用后获得|使用后可|获得以下|以下物品)")
# 数量符号分隔的 `名称*N`（名称=中文/字母/数字/成对括号/方括号/连接符，非清单标点）
AMOUNT = re.compile(r"([\u4e00-\u9fffA-Za-z0-9（）()【】\[\]·\-]{1,28}?)[）)\]】]?\s*[＊*×xX]\s*(\d+)")
# 名称尾部可剥离的修饰后缀（循环剥）
STRIP_SUFFIXES = ["（绑定）", "(绑定)", "（限时）", "(限时)", "（永久）", "(永久)",
                  "（15点）", "(15点)", "（30点）", "(30点)"]
# 名称头尾可剥离的冗余前缀/品级括号段（生成额外候选，不替换原名）
STRIP_PREFIXES = ["打开盒子可以获得", "打开盒子", "有可能", "获得", "开启", "得到"]
GRADE_WORDS = ["（上等）", "(上等)", "（中等）", "(中等)", "（下等）", "(下等)",
               "（上等", "(上等)", "（中等", "(中等)", "（下等", "(下等)"]
GRADE_TAIL = re.compile(r"(上等|中等|下等)$")
# 时限/计量括号后缀（时限变体不存在时剥除落基名，如 混元易筋丹（30天 → 混元易筋丹）
TIMEBOX_TAIL = re.compile(r"[（(][0-9一二三四五六七八九十半]+(天|小时|周|个月|点积分)[）)]?$")
# 截断在数字中的计量括号（`翡翠娃娃淬炼券礼包（100` → 基名）
NUMBOX_TAIL = re.compile(r"[（(][0-9]+(张|点|个|次|枚)?[）)]?$")
NAME_FIX = str.maketrans("（）", "()")
# 手工箱内容（礼包商人上架全集等泛型描述箱：介绍无清单，按箱名语义手工配；
# 物品均实存于 tool_item/stringname。优先级最高，不被生成覆盖）。(item, amount)
MANUAL_BOXES = {
    # 惊鸿礼盒（2026 活动大礼）
    "box_chinajoy_2026_giftbox": [("item_lzy_cbt_dhdj003", 5), ("zhenqi_item_009", 2),
                                  ("item_jqsjy_zyb006", 2), ("item_swhd_repute_jianghu", 2)],
    # 侠客岛海底宝箱 银/金/玉（打捞珠宝递进）
    "box_item_xiakedao_haidibaoxiang_1": [("item_zawu_294", 3), ("ore_1010", 2)],
    "box_item_xiakedao_haidibaoxiang_2": [("item_zawu_294", 5), ("ore_1010", 3), ("item_zawu_262", 4)],
    "box_item_xiakedao_haidibaoxiang_3": [("item_zawu_294", 8), ("item_xiake_shp_021", 3),
                                          ("dissect_special_012", 5)],
    # 帮会 BOSS 低/中/顶（材料递进）
    "box_prize_guild_boss_diji": [("item_zawu_262", 3), ("ore_1010", 1)],
    "box_prize_guild_boss_zhongji": [("item_zawu_262", 5), ("ore_1010", 3), ("zhenqi_match_003", 2)],
    "box_prize_guild_boss_dingji": [("item_zawu_262", 8), ("ore_1010", 5),
                                    ("zhenqi_item_009", 2), ("item_swhd_repute_jianghu", 1)],
    # 乞讨赏钱/钱袋/一袋银子（银钱主题）
    "budai0006": [("bind_money_1", 5), ("item_lzy_cbt_dhdj003", 1)],
    "budai0008": [("bind_money_1", 6), ("item_lzy_cbt_dhdj003", 1)],
    "qy_lmkz_mazeiboss001": [("bind_money_1", 8), ("item_lzy_cbt_dhdj003", 2)],
    "qy_qyb_001": [("bind_money_1", 6), ("item_lzy_cbt_dhdj003", 1)],
    "qy_suzhou_eshaobaofu": [("bind_money_1", 5)],
    "qy_suzhou_qigai": [("bind_money_1", 3), ("item_zawu_294", 1)],
    "qy_suzhou_toulu": [("bind_money_1", 3), ("item_zawu_294", 1)],
    "clone004_jl01": [("bind_money_1", 4)],
    # 奇遇奖励包（修炼材料）
    "qy_chengdu_009": [("zhenqi10001", 3), ("zhenqi_match_003", 2)],
    "qy_em0006": [("zhenqi_match_003", 2), ("zhenqi10001", 2)],
    "qy_em0008": [("zhenqi_match_003", 2), ("zhenqi10001", 3)],
    "qy_jmyjl_006": [("zhenqi10001", 3), ("bind_money_1", 3)],
    "qiyubao0009": [("zhenqi10001", 3), ("zhenqi_match_003", 1)],
    "box_ding_0606xsh001": [("zhenqi_match_003", 2), ("zhenqi10001", 2)],
    # 武当奇遇（太极剑/太极拳）
    "qy_xibeidl_017": [("smtzitem_wd01", 1), ("taskitem_014jx", 1)],
    "qy_xibeidl_106": [("itmfun_tjq", 1), ("taskitem_014jx", 1)],
    # 奇遇装备/打造台（对应生活材料）
    "qy_box_equip_04_31": [("item_zawu_262", 3), ("ore_1010", 2), ("equip_duihuan_002", 1)],
    "qy_box_tableitem_cf_01": [("cropper_20001", 5), ("cropper_20003", 3)],
    "qy_box_tableitem_ys": [("drug_1009", 3), ("taskitem_12031", 5)],
    # 江南生活锦盒（碎银+金创药 温和通用包）
    "tf_jn_aqsf_sen": [("bind_money_1", 3), ("useitem_035p", 2)],
    "tf_jn_sh_ks": [("bind_money_1", 3), ("useitem_035p", 2)],
    "tf_jn_shpf_tj": [("hcitem_cs_naij_01", 1), ("useitem_035p", 2)],
    "tf_jn_zf_jh": [("bind_money_1", 3), ("useitem_035p", 2)],
    "tf_jn_zs_pgd": [("item_zawu_262", 2), ("bind_money_1", 3)],
    # 沉香盒子 / 凤凰翎密箱
    "may_box": [("item_jqsjy_zyb006", 2), ("zhenqi_match_003", 2)],
    "item_aqzw_09": [("useitemclone001003", 3)],
    # 注：tdropbox*/treasure_hht*（辨识宝物=treasure_* 装备域）不在此配——
    # 宝物走鉴定系统（独立链路），开箱对照不覆盖，维持 drops_mvp 原路径。
}
# 手工别名（文案名 ≠ stringname 名：错别字/别称/引号变体，取值均为 stringname 实存 configId）
ALIAS = {
    "【体型丹药】锦盒": "box_20160603_txlh_fc_001",      # 体型丹药礼盒
    "授业自习丹": "box_20240919_dmfs_fc_001",            # 奖励包（授业自习丹）
    "仓库扩充凭证": "bag_expand_01",                      # 扩充包裹锦盒
    "扩充凭证（仓库": "bag_expand_01",
    "十香返生丹": "hc_0401237",                           # 十香返生丸（文案丹/丸）
    "混元易经丹": "faculty_event_02",                     # 混元易筋丹（文案错别字）
    "浣洗卷": "box_spring_festival_huanxijuan",           # 【浣洗卷】礼包
    "宝物技能洗练券": "treasure_skill_reset",             # 宝物技能洗炼券（练/炼）
    "打造台锦盒": "box_hd_dzt",                           # 【打造台】锦盒
    "乐山嫩豆花": "cs_shejian_1002",                      # 佳肴·乐山嫩豆花
    "煎饼卷大葱": "cs_shejian_1001",                      # 佳肴·煎饼卷大葱
    "大侠很忙折扣券": "box_item_zhanling_dy_001",         # “大侠很忙折扣券”礼包
}


def load_idres(path):
    out = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "=" in line:
                k, v = line.split("=", 1)
                out.setdefault(k.strip(), v.strip())
    return out


def load_tool_item_boxes(path):
    """GBK 大文件整读（iconv 会静默截断，勿用管道）；返回 {cfgLower: {key: value}}。"""
    text = open(path, "rb").read().decode("gbk", errors="replace")
    boxes, cur = {}, None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("["):
            cur = s[1:s.find("]")].strip().lower()
            boxes.setdefault(cur, {})
        elif "=" in s and cur is not None:
            k, v = s.split("=", 1)
            boxes[cur][k.strip().lower()] = v.strip()
    return boxes


def box_desc(descs, cfg):
    pages, i = [], 0
    while f"desc_{cfg}_{i}" in descs:
        pages.append(descs[f"desc_{cfg}_{i}"])
        i += 1
    if not pages and f"desc_{cfg}" in descs:
        pages.append(descs[f"desc_{cfg}"])
    return " ".join(pages)


def clean_html(text):
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    return text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")


def close_parens(name):
    """修复名称截断产生的未闭合括号（`（7天` → `（7天）`），返回修复候选。"""
    out = [name]
    fixed = name
    for op, cl in (("（", "）"), ("(", ")"), ("【", "】"), ("[", "]")):
        if fixed.count(op) > fixed.count(cl):
            fixed = fixed + cl * (fixed.count(op) - fixed.count(cl))
    if fixed != name:
        out.append(fixed)
    return out


def norm_name(name):
    """反查候选序列：括号闭合修复 → 半角化 → 剥前后缀/品级/方括号（时限变体是独立物品名，
    时限括号保留为名称一部分，靠半角化对齐 stringname 的半角形态）。"""
    cands = []
    for base in close_parens(name.strip()):
        cands.append(base)
        half = base.translate(NAME_FIX)
        if half != base:
            cands.append(half)
    # 品级/时限/前缀剥离：对已有候选派生（前缀循环剥到稳定）
    for base in list(cands):
        stripped = base
        changed = False
        while True:
            before = stripped
            for p in STRIP_PREFIXES:
                if stripped.startswith(p) and len(stripped) > len(p):
                    stripped = stripped[len(p):]
            for g in GRADE_WORDS:
                if g in stripped:
                    stripped = stripped.replace(g, "")
            for suf in STRIP_SUFFIXES:
                if stripped.endswith(suf) and len(stripped) > len(suf):
                    stripped = stripped[: -len(suf)]
            stripped = TIMEBOX_TAIL.sub("", stripped).strip()
            stripped = NUMBOX_TAIL.sub("", stripped).strip()
            stripped = GRADE_TAIL.sub("", stripped).strip()
            if stripped == before:
                break
            changed = True
        if changed and stripped:
            cands.append(stripped)
            half2 = stripped.translate(NAME_FIX)
            if half2 != stripped:
                cands.append(half2)
    seen, out = set(), []
    for c in cands:
        c = c.strip()
        if c and c not in seen:
            seen.add(c)
            out.append(c)
    return out


def extract_candidates(text):
    """返回 [(名称, 数量)]。优先引导词段；无引导词时退回全文紧凑模式。"""
    hits = []
    for m in LEAD_WORDS.finditer(text):
        seg = text[m.end(): m.end() + 200]
        seg = seg.split("\n")[0]
        for name, num in AMOUNT.findall(seg):
            hits.append((name, int(num)))
    if not hits:
        for name, num in AMOUNT.findall(text):
            hits.append((name, int(num)))
    # 同名合并数量
    merged = {}
    for name, num in hits:
        merged[name] = merged.get(name, 0) + num
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text-dir", default=r"G:\pojie\ai\9yin-go-server1\resources\modern\text\chineses")
    ap.add_argument("--tool-item", default=r"F:\Game\Online\9yin\9yin_server_new\resources\modern\share\item\tool_item.ini")
    ap.add_argument("--giftbox", default=r"G:\pojie\ai\9yin-go-server1\data\giftbox.json")
    ap.add_argument("--out", required=True)
    ap.add_argument("--report", default=None)
    args = ap.parse_args()

    names = defaultdict(list)  # 物品名 -> [configId]
    stringname = load_idres(args.text_dir + r"\stringname.idres")
    for cfg, name in stringname.items():
        if cfg.startswith("desc_"):
            continue               # stringname 混有 desc_* 描述键，反查域只认物品名键
        names[name].append(cfg)
    print(f"stringname: {len(stringname)} entries, {len(names)} distinct names")

    tool_items = load_tool_item_boxes(args.tool_item)
    all_cfgs = set(tool_items)
    box_cfgs = {c for c, kv in tool_items.items()
                if kv.get("script", "").strip().lower() == "boxitem"}
    print(f"tool_item sections={len(all_cfgs)} BoxItem={len(box_cfgs)}")

    # 可发集合提前算（箱名直配也要用）：发放链 ToolItemCatalog = tool_item ∪ equipment
    grantable = set(all_cfgs)
    eq_path = Path(args.tool_item).parent / "equipment.ini"
    eq_text = open(eq_path, "rb").read().decode("gbk", errors="replace")
    grantable |= {m.group(1).lower() for m in re.finditer(r"^\[([^\]]+)\]", eq_text, re.M)}

    descs = {}
    for fn in ["desc.idres", "desc2.idres", "desc3.idres"]:
        descs.update(load_idres(args.text_dir + "\\" + fn))

    def lookup(name):
        for base in close_parens(name.strip()):   # 手工别名按括号修复后的原名匹配
            if base in ALIAS:
                cid = ALIAS[base]
                return cid if cid in all_cfgs or cid in names.get(
                    stringname.get(cid, ""), [None]) else None
        for cand in norm_name(name):
            ids = names.get(cand)
            if ids:
                for cid in ids:                       # 偏好 tool_item 里存在的条目
                    if cid in all_cfgs:
                        return cid
                return ids[0]
        return None

    boxes, unmatched, stats = {}, [], defaultdict(int)
    # tdropbox*/treasure_hht*（辨识宝物）不进开箱对照——treasure_* 是宝物装备域，
    # 走鉴定系统（独立链路，另案接入），维持 drops_mvp 原路径。
    skip_cfgs = {c for c in box_cfgs
                 if c.startswith("tdropbox") or re.match(r"^treasure_hht_\d+$", c)}
    for cfg in sorted(box_cfgs):
        if cfg in skip_cfgs:
            continue
        raw = box_desc(descs, cfg)
        if not raw:
            stats["no_desc"] += 1
            continue
        text = clean_html(raw)
        if not any(c in text for c in "＊*×xX"):
            stats["no_amount_marker"] += 1
            continue
        cands = extract_candidates(text)
        if not cands:
            stats["amount_marker_but_no_parse"] += 1
            continue
        items = []
        for name, num in cands.items():
            cid = lookup(name)
            if cid is None:
                unmatched.append((cfg, name, num))
                stats["name_unmatched"] += 1
            else:
                items.append({"item": cid, "min": num, "max": num})
        if items:
            boxes[cfg] = items
            stats["box_built"] += 1

    # data/giftbox.json 老重建表仅补缺（本生成器规则更强：老表 box_hd_wyls_05 漏兵录条目实测，
    # 故生成优先；老表 322 箱全部落在 tool_item BoxItem 域，纯补缺实际贡献 0+）
    manual = 0
    try:
        with open(args.giftbox, encoding="utf-8") as f:
            gb = json.load(f)
        for cfg, entries in gb.get("boxes", {}).items():
            if cfg in boxes:
                stats["generated_overrode_old_table"] += 1
                continue
            fixed = [{"item": e["item"], "min": int(e.get("min", 1)), "max": int(e.get("max", 1))}
                     for e in entries if e.get("item")]
            if fixed:
                boxes[cfg] = fixed
                manual += 1
    except (OSError, ValueError) as e:
        print(f"giftbox.json merge skipped: {e}")

    # 箱名直配：箱名（或剥箱词后缀）去【】/括号后 = 某非箱可发物品的本名 → 开出本体 ×1。
    # 案例：无妄神功入门篇(贵重箱) → ng_book_jh_201_rm=无妄神功(入门篇)；
    # 魅影剑法残卷礼包 → cjbook_CS_jh_myjf=【魅影剑法】残卷；行脚令礼包 → backtown_tool。
    # 索引只收非箱 + grantable 条目，归一化同名冲突的键剔除防误配。
    BOX_WORD_TAIL = re.compile(r"(礼包|礼盒|宝盒|锦盒|幸运包|包裹|奖励包|礼匣|宝匣|福袋|秘籍盒|盒子)$")

    def plain_key(name):
        return re.sub(r"[【】（）()\[\]\s]", "", name).strip().lower()

    plain_index, plain_conflict = {}, set()
    for cid, nm in stringname.items():
        if cid.startswith("desc_") or cid in box_cfgs or cid.lower() not in grantable:
            continue                   # grantable 键为小写（tool/equipment 节名 lower）
        key = plain_key(nm)
        if not key:
            continue
        if plain_index.setdefault(key, cid) != cid:
            plain_conflict.add(key)
    for k in plain_conflict:
        plain_index.pop(k, None)
    name_matched = 0
    for cfg in sorted(box_cfgs):
        if cfg in boxes or cfg in skip_cfgs:
            continue
        box_name = stringname.get(cfg, "").strip()
        if not box_name:
            continue
        stripped = BOX_WORD_TAIL.sub("", box_name)
        for cand in dict.fromkeys([box_name, stripped]):
            if not cand:
                continue
            cid = plain_index.get(plain_key(cand))
            if cid and cid != cfg:
                amount = 1
                # 数量偏好：描述里直配名后紧贴数字（"魅影剑法残卷10张"→10）或 ×N 形式
                raw = box_desc(descs, cfg)
                if raw:
                    text = clean_html(raw)
                    m = re.search(re.escape(cand) + r"\s*(\d+)", text)
                    if not m:
                        m = re.search(re.escape(cand) + r"\s*[＊*×xX]\s*(\d+)", text)
                    if m:
                        amount = max(1, min(int(m.group(1)), 999))
                boxes[cfg] = [{"item": cid, "min": amount, "max": amount}]
                name_matched += 1
                break
    stats["box_name_matched"] = name_matched

    # MANUAL_BOXES 手工箱内容（最高优先级，覆盖一切生成/老表）
    manual_boxes = 0
    for cfg, entries in MANUAL_BOXES.items():
        fixed = [{"item": item, "min": amt, "max": amt} for item, amt in entries]
        if cfg in boxes:
            stats["manual_box_overrode"] += 1
        else:
            manual_boxes += 1
        boxes[cfg] = fixed

    # IsRarityItem=1 贵重箱兜底：无描述清单/手工配置的（多为过期活动礼包）给贵重标准包，
    # 替代 drops_mvp 合成药品——"贵重"箱开出随机药与定位不符。
    RARITY_PACK = [{"item": "item_lzy_cbt_dhdj003", "min": 3, "max": 3},
                   {"item": "zhenqi_item_009", "min": 1, "max": 1},
                   {"item": "item_jqsjy_zyb006", "min": 1, "max": 1},
                   {"item": "item_2108_dy_18", "min": 1, "max": 1}]
    rarity_filled = 0
    for cfg, kv in tool_items.items():
        if (kv.get("script", "").strip().lower() == "boxitem"
                and kv.get("israrityitem") == "1" and cfg not in boxes):
            boxes[cfg] = list(RARITY_PACK)
            rarity_filled += 1
    stats["rarity_fallback_filled"] = rarity_filled

    # 全局可发过滤兜底（grantable 已提前算好，大小写不敏感；物品 configId 统一小写输出，
    # 发放链 lookup 大小写不敏感）
    dropped_items = set()
    for cfg in list(boxes):
        kept = [e for e in boxes[cfg] if e["item"].lower() in grantable]
        if len(kept) != len(boxes[cfg]):
            dropped_items |= {e["item"] for e in boxes[cfg] if e["item"].lower() not in grantable}
        if kept:
            for e in kept:
                e["item"] = e["item"].lower()
            boxes[cfg] = kept
        else:
            del boxes[cfg]
    stats["ungrantable_items_dropped"] = len(dropped_items)

    total_items = sum(len(v) for v in boxes.values())
    payload = {
        "note": "箱子→内容物对照（按客户端 desc 描述文案重建，固定全发；"
                "data/giftbox.json 手工条目优先）。生成器 tools/boxgen_rebuild.py。",
        "boxes": dict(sorted(boxes.items())),
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    report = args.report or (args.out + ".unmatched.csv")
    with open(report, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["box", "unmatched_name", "amount"])
        w.writerows(unmatched)
    print(f"OUT {args.out}")
    print(f"boxes={len(boxes)} (desc-generated + giftbox-compat={manual} + manual-boxes={manual_boxes}) "
          f"items={total_items} unmatched_names={len(unmatched)}")
    print("stats:", dict(sorted(stats.items())))


if __name__ == "__main__":
    main()
