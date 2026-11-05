# -*- coding: utf-8 -*-
"""秘籍(SkillBook)数据链探针：LearnSkillPack -> item_learn_skill_static -> 目标技能。"""
import io
import re
import sys
from collections import Counter

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"
SKILL_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/skill"


def read_ini(path):
    with io.open(path, encoding="gbk", errors="replace") as f:
        txt = f.read()
    out = {}
    cur = None
    for line in txt.splitlines():
        line = line.strip()
        if line.startswith("[") and line.endswith("]"):
            cur = line[1:-1]
            out.setdefault(cur, {})
        elif "=" in line and cur is not None:
            k, v = line.split("=", 1)
            out[cur][k.strip()] = v.strip()
    return out


def main():
    tool = read_ini(ITEM_DIR + "/tool_item.ini")
    learn = read_ini(ITEM_DIR + "/item_learn_skill_static.ini")
    skill_new = read_ini(SKILL_DIR + "/skill_new.ini")

    books = {k: v for k, v in tool.items() if v.get("script", "").lower() == "skillbook"}
    print("SkillBook items:", len(books))
    itypes = Counter(v.get("ItemType", "?") for v in books.values())
    print("ItemType dist:", dict(itypes.most_common(12)))

    # LearnSkillPack coverage
    have_pack = missing_pack = 0
    pack_targets = {}
    for cfg, v in books.items():
        pack = v.get("LearnSkillPack")
        if not pack:
            continue
        row = learn.get(pack)
        if row is None:
            missing_pack += 1
            continue
        have_pack += 1
        pack_targets.setdefault(pack, row)

    print("books with LearnSkillPack row: %d, pack id missing in learn_static: %d"
          % (have_pack, missing_pack))

    # TragetID 是否在 skill_new
    target_in_skill_new = target_missing = 0
    samples = []
    for pack, row in pack_targets.items():
        tgt = row.get("TragetID")
        if not tgt:
            continue
        if tgt in skill_new:
            target_in_skill_new += 1
        else:
            target_missing += 1
            if len(samples) < 10:
                samples.append((pack, tgt))
    print("TragetID in skill_new: %d, missing: %d" % (target_in_skill_new, target_missing))
    print("missing samples:", samples)

    # 样例：给几个不同 ItemType 的书看目标
    shown = 0
    seen_it = set()
    for cfg, v in books.items():
        it = v.get("ItemType")
        pack = v.get("LearnSkillPack")
        if it in seen_it or not pack:
            continue
        row = learn.get(pack, {})
        print("sample [%s] Name=%s ItemType=%s pack=%s -> TragetID=%s Level=%s cond=%s"
              % (cfg, v.get("Name"), it, pack, row.get("TragetID"),
                 row.get("TargetLevel"), row.get("ConditionID", "")))
        seen_it.add(it)
        shown += 1
        if shown >= 10:
            break

    # LearnSkillPack 字段总分布（learn_static 表内键分布）
    key_dist = Counter()
    for row in learn.values():
        for k in row:
            key_dist[k] += 1
    print("learn_static key dist:", dict(key_dist.most_common(15)))

    if len(sys.argv) > 1:
        # 按名称/配置id查书
        pat = sys.argv[1].lower()
        for cfg, v in books.items():
            if pat in cfg.lower() or pat in v.get("Name", "").lower():
                pack = v.get("LearnSkillPack", "")
                row = learn.get(pack, {})
                print("[%s] Name=%s ItemType=%s ViewID=%s pack=%s -> %s"
                      % (cfg, v.get("Name"), v.get("ItemType"), v.get("ViewID"),
                         pack, {k: row.get(k) for k in
                                ("TragetID", "TargetLevel", "ZhaoShiNeeded", "ConditionID")}))


if __name__ == "__main__":
    main()
