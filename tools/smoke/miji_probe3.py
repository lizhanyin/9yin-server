# -*- coding: utf-8 -*-
"""SkillPage/SkillScroll 是否共用 item_learn_skill_static 表。"""
import io
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


tool = read_ini(ITEM_DIR + "/tool_item.ini")
learn = read_ini(ITEM_DIR + "/item_learn_skill_static.ini")
stat = Counter()
for cfg, v in tool.items():
    pack = v.get("learnskillpack")
    if not pack:
        continue
    row = learn.get(pack)
    tgt = row.get("TragetID") if row else None
    key = (v.get("script"), "has_row" if row else "no_row",
           "has_target" if tgt else ("empty" if row else "-"))
    stat[key] += 1
for k, n in stat.most_common(12):
    print(n, k)

shown = set()
for cfg, v in tool.items():
    pack = v.get("learnskillpack")
    sc = v.get("script")
    if not pack or sc in shown:
        continue
    row = learn.get(pack, {})
    print(sc, "|", cfg, "|", v.get("Name"), "| pack", pack, "=>", row.get("TragetID"))
    shown.add(sc)
    if len(shown) >= 5:
        break
