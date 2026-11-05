# -*- coding: utf-8 -*-
"""SkillMaxLevel/AddMaxLevel/UseCondition 全量取证 + 条件表定位。"""
import io
import re
from collections import Counter

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"
SKILL_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/skill"


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
             if v.get("LearnSkillPack") and v.get("script") in ("SkillBook", "SkillPage", "SkillScroll")}

    # 1) 气桩功全家桶（基础书/入门/进阶/每层书）
    print("== 气桩功全家桶 SkillMaxLevel/AddMaxLevel/UseCondition ==")
    for cfg, v in sorted(books.items()):
        if not cfg.startswith("ng_book_em_001"):
            continue
        pack = learn.get(v.get("LearnSkillPack"), {})
        print("  %-26s %-14s smax=%-4s addmax=%-4s addlvl=%-3s cond=%-6s -> %s"
              % (cfg.replace("ng_book_em_001", "~"), v.get("Name", ""),
                 v.get("SkillMaxLevel", "-"), v.get("AddMaxLevel", "-"),
                 v.get("AddLevel", "-"), v.get("UseCondition", "-"),
                 pack.get("TragetID")))

    # 2) 梵心降魔全家桶
    print("== 梵心降魔全家桶 ==")
    for cfg, v in sorted(books.items()):
        if not cfg.startswith("book_CS_em_jdmz01"):
            continue
        pack = learn.get(v.get("LearnSkillPack"), {})
        print("  %-26s %-16s smax=%-4s addmax=%-4s cond=%-6s -> %s"
              % (cfg.replace("book_CS_em_jdmz01", "~"), v.get("Name", ""),
                 v.get("SkillMaxLevel", "-"), v.get("AddMaxLevel", "-"),
                 v.get("UseCondition", "-"), pack.get("TragetID")))

    # 3) 全量字段覆盖统计
    stat = Counter()
    for cfg, v in books.items():
        for k in ("SkillMaxLevel", "AddMaxLevel", "AddLevel", "UseCondition"):
            if v.get(k):
                stat[k] += 1
    print("== 字段覆盖 ==", len(books), "书 =", dict(stat))

    # 4) UseCondition 表定位：item_func_static.ini?
    for fname in ("item_func_static.ini", "func_static.ini", "item_use_condition.ini"):
        try:
            t = read_ini(ITEM_DIR + "/" + fname)
            if t:
                print("== %s: %d 节 ==" % (fname, len(t)))
                for cid in ("12193", "12197", "13579", "13595"):
                    if cid in t:
                        print("  [%s] %s" % (cid, t[cid]))
                break
        except OSError:
            pass

    # 5) 条件表若不在 item 目录，全 share 搜
    import os
    hits = []
    for root, _dirs, files in os.walk(r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share"):
        for f in files:
            if f.endswith(".ini") and ("func" in f.lower() or "condition" in f.lower()):
                hits.append(os.path.join(root, f))
    print("== func/condition 候选文件 ==")
    for h in hits[:15]:
        print("  ", h)


if __name__ == "__main__":
    main()
