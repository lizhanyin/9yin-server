# -*- coding: utf-8 -*-
"""GM 面板「所有道具与换装」列表审计：复刻 GmCatalog.loadItemIni 找错误类。"""
import io
import collections

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


def to_int(v, d):
    try:
        return int(v)
    except (TypeError, ValueError):
        return d


def main():
    equip = read_ini(ITEM_DIR + "/equipment.ini")
    tool = read_ini(ITEM_DIR + "/tool_item.ini")

    rows = []
    for src, wearable in ((equip, True), (tool, False)):
        for cfg, sec in src.items():
            rows.append({
                "src": "equip" if wearable else "tool",
                "configId": cfg,
                "name": sec.get("Name", ""),
                "viewId": to_int(sec.get("ViewID"), 1),
                "wearable": wearable,
                "equipType": sec.get("EquipType", "") if wearable else "",
                "script": sec.get("script", ""),
            })
    print("total rows: equip=%d tool=%d" % (len(equip), len(tool)))

    comments = [r for r in rows if r["configId"].startswith(("//", ";", "-", "#"))]
    print("A. 注释节泄漏:", len(comments), [r["configId"] for r in comments[:6]])

    empty_names = [r for r in rows if not r["name"]]
    print("B. 空 Name:", len(empty_names), [r["configId"] for r in empty_names[:6]])

    vc = collections.Counter(r["viewId"] for r in rows)
    print("C. viewId 分布:", dict(vc.most_common(15)))
    out4 = [r for r in rows if r["viewId"] not in (1, 2, 3, 4)]
    print("   viewId not in {1,2,3,4}:", len(out4),
          "样例:", [(r["configId"], r["viewId"], r["equipType"]) for r in out4[:8]])

    tool_wear = [r for r in rows if r["src"] == "tool" and r["equipType"]]
    tool_wear_actual = [c for c, s in tool.items() if s.get("EquipType")]
    print("D. tool_item 带 EquipType(可穿但列表 wearable=false):", len(tool_wear_actual),
          "样例:", [(c, tool[c].get("EquipType"), tool[c].get("Name", "")) for c in tool_wear_actual[:6]])

    dup = [c for c in equip if c in tool]
    print("E. 两表重复 configId:", len(dup), dup[:6])

    # 装备表里 ViewID!=2 的（面板装备分类筛 view=2 会漏）
    eq_odd = [r for r in rows if r["src"] == "equip" and r["viewId"] != 2]
    print("F. equipment.ini ViewID!=2:", len(eq_odd),
          collections.Counter(r["viewId"] for r in eq_odd).most_common(8))
    print("   样例:", [(r["configId"], r["viewId"], r["equipType"], r["name"]) for r in eq_odd[:8]])

    # script 分布（tool）
    sc = collections.Counter(r["script"] for r in rows if r["src"] == "tool")
    print("G. tool script top:", dict(sc.most_common(8)))

    # 名字重复严重度（下拉框难以区分）
    name_dup = collections.Counter(
        r["name"] for r in rows if r["name"]).most_common(8)
    print("H. 重名 top:", name_dup)


if __name__ == "__main__":
    main()
