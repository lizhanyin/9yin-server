# -*- coding: utf-8 -*-
"""篇章/页书门槛字段取证：TargetLevel/ZhaoShiNeeded/ZhaoShiLevel/NeiGongNeeded 分布。"""
import io
import re
from collections import Counter

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"


def read_ini(path):
    out = {}
    cur = None
    for line in io.open(path, encoding="gbk", errors="replace"):
        s = line.strip()
        if s.startswith("[") and s.endswith("]"):
            cur = s[1:-1]
            out.setdefault(cur, {})
        elif "=" in s and cur is not None:
            k, v = s.split("=", 1)
            out[cur][k.strip()] = v.strip()
    return out


def main():
    tool = read_ini(ITEM_DIR + "/tool_item.ini")
    learn = read_ini(ITEM_DIR + "/item_learn_skill_static.ini")

    books = {k: v for k, v in tool.items()
             if v.get("LearnSkillPack") and v.get("script") in
             ("SkillBook", "SkillPage", "SkillScroll")}

    # 1) 按名字找篇章书（江湖内功 X篇）
    tier_names = ["入门篇", "进阶篇", "中阶篇", "高阶篇", "精研篇", "精修篇", "深玄篇", "秘传篇", "拾遗篇"]
    print("== 篇章书样例（含 TargetLevel/ZhaoShi 字段） ==")
    shown = 0
    seen_kinds = set()
    for cfg, v in books.items():
        name = v.get("Name", "")
        kind = next((t for t in tier_names if t in name), None)
        if not kind or kind in seen_kinds:
            continue
        pack = v.get("LearnSkillPack")
        row = learn.get(pack, {})
        print("[%s] %s script=%s it=%s pack=%s" % (cfg, name, v.get("script"), v.get("ItemType"), pack))
        print("   ", {k: row.get(k) for k in
                      ("TragetID", "TargetLevel", "ZhaoShiNeeded", "ZhaoShiLevel",
                       "NeiGongNeeded", "NeiGongLevel", "JingMaiNeeded", "PropNeeded", "ConditionID")})
        seen_kinds.add(kind)
        shown += 1
        if shown >= 12:
            break

    # 2) 第X页书样例
    print("== 第X页书样例 ==")
    page_re = re.compile(r"第[一二三四五六七八九十百\d]+页")
    shown = 0
    for cfg, v in books.items():
        name = v.get("Name", "")
        if not page_re.search(name):
            continue
        pack = v.get("LearnSkillPack")
        row = learn.get(pack, {})
        print("[%s] %s script=%s pack=%s" % (cfg, name, v.get("script"), pack))
        print("   ", {k: row.get(k) for k in
                      ("TragetID", "TargetLevel", "ZhaoShiNeeded", "ZhaoShiLevel",
                       "NeiGongNeeded", "NeiGongLevel", "ConditionID")})
        shown += 1
        if shown >= 6:
            break

    # 3) 全量字段分布
    print("== pack 字段分布（有值行数） ==")
    dist = Counter()
    tl_dist = Counter()
    zn_dist = Counter()
    for cfg, v in books.items():
        row = learn.get(v.get("LearnSkillPack"), {})
        for k in row:
            dist[k] += 1
        tl = row.get("TargetLevel")
        if tl:
            tl_dist[tl] += 1
        zn = row.get("ZhaoShiNeeded")
        if zn:
            zn_dist["withZhaoShi"] += 1
    print("fields:", dict(dist.most_common(15)))
    print("TargetLevel top:", tl_dist.most_common(25))
    print("books with ZhaoShiNeeded:", zn_dist)

    # 4) TargetLevel 与目标内功的关系：同 TragetID 的书按 TargetLevel 排序看是否分层
    print("== 同一 ng 目标的篇章分层 ==")
    by_target = {}
    for cfg, v in books.items():
        row = learn.get(v.get("LearnSkillPack"), {})
        tgt = row.get("TragetID")
        tl = row.get("TargetLevel")
        if tgt and tgt.startswith("ng_") and tl:
            by_target.setdefault(tgt, []).append((int(tl), v.get("Name", cfg), v.get("script")))
    count = 0
    for tgt, lst in by_target.items():
        if len(lst) >= 4:
            lst.sort()
            print(tgt, "->", [(t, n) for t, n, _ in lst])
            count += 1
            if count >= 6:
                break


if __name__ == "__main__":
    main()
