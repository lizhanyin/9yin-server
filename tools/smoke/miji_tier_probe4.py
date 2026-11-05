# -*- coding: utf-8 -*-
"""UseCondition 引用的条件分布 + 篇章汇总书 cond 校验 + SkillScroll 语义。"""
import io
from collections import Counter

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"
RULE = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/rule/condition.ini"


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
    cond = read_ini(RULE)

    books = {k: v for k, v in tool.items()
             if v.get("LearnSkillPack") and v.get("script") in ("SkillBook", "SkillPage", "SkillScroll")}

    names = Counter()
    para2 = Counter()
    types = Counter()
    missing = 0
    for cfg, v in books.items():
        uc = v.get("UseCondition")
        if not uc:
            continue
        row = cond.get(uc)
        if not row:
            missing += 1
            continue
        names[(row.get("Name"), row.get("Para2"))] += 1
        types[row.get("Type")] += 1
    print("书引用条件 Type 分布:", dict(types.most_common(10)))
    print("Name/Para2 分布:", dict(names.most_common(12)))
    print("条件表缺席引用:", missing)

    # 汇总书 cond 校验：进阶篇 13563 / 精研精修深玄秘传拾遗样本
    print("== 篇章 cond 样本 ==")
    for cid in ("13563", "13571", "13587", "13595", "13579"):
        print(" ", cid, cond.get(cid))

    # 江湖内功(晦明日月鉴 ng_jh_219)全家：篇章/拾遗/传承
    print("== 晦明日月鉴 ng_jh_219 全家 ==")
    learn = read_ini(ITEM_DIR + "/item_learn_skill_static.ini")
    for cfg, v in sorted(books.items()):
        if "ng_book_jh_219" not in cfg:
            continue
        pack = learn.get(v.get("LearnSkillPack"), {})
        print("  %-34s smax=%-4s addL=%-3s addM=%-4s cond=%-6s -> %s"
              % (v.get("Name", cfg), v.get("SkillMaxLevel", "-"),
                 v.get("AddLevel", "-"), v.get("AddMaxLevel", "-"),
                 v.get("UseCondition", "-"), pack.get("TragetID")))

    # 拾遗/深玄等汇总书的 cond 实例（从名字找）
    print("== 高篇章书 cond 实例 ==")
    shown = set()
    for cfg, v in books.items():
        name = v.get("Name", "")
        for t in ("精研篇", "精修篇", "深玄篇", "秘传篇", "拾遗篇一", "拾遗篇·一", "拾遗篇二"):
            if t in name and t not in shown:
                uc = v.get("UseCondition")
                print("  %-24s cond=%s -> %s" % (name, uc, cond.get(uc, {})))
                shown.add(t)
                break


if __name__ == "__main__":
    main()
