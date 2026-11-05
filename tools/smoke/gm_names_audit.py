# -*- coding: utf-8 -*-
"""全量名字审计：INI Name vs stringname.idres——无妄同款问题（篇章错位/占位/重叠后缀/抄错）归类。"""
import io
import re
from collections import Counter, defaultdict

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"
IDRES = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/text/chineses/stringname.idres"


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
    eq = read_ini(ITEM_DIR + "/equipment.ini")
    tool = read_ini(ITEM_DIR + "/tool_item.ini")
    learn = read_ini(ITEM_DIR + "/item_learn_skill_static.ini")
    sm = {}
    for line in io.open(IDRES, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if "=" in line:
            k, v = line.split("=", 1)
            sm.setdefault(k, v.strip())

    def numeric(n):
        return bool(n) and all("0" <= c <= "9" for c in n)

    placeholder = re.compile(r"^[A-Za-z0-9_\-\.]+$")  # 纯 ASCII（configId 回声/编号）

    stats = Counter()
    tier_wrong = []       # 书类篇章名错位/缺失
    doubled = []          # 重叠后缀
    ascii_ph = []         # ASCII 占位（有 idres）
    ascii_ph_noidres = [] # ASCII 占位（无 idres，仍显示 ConfigID）
    empty_noidres = []
    corrected_other = []

    tier_words = ["入门", "进阶", "中阶", "高阶", "精研", "精修", "深玄", "秘传", "拾遗"]
    ordinal = re.compile(r"拾遗篇·([一二三四五六七八九十]+)")

    for src, is_tool in ((eq, False), (tool, True)):
        for cfg, sec in src.items():
            if cfg.startswith("//"):
                continue
            iname = sec.get("Name", "")
            sname = sm.get(cfg)
            script = sec.get("script", "")
            smax = sec.get("SkillMaxLevel", "")

            # ① 重叠后缀（传承心法传承心法）
            if iname and re.search(r"(.{2,6})\1", iname):
                doubled.append((cfg, iname, sname or ""))
            # ② ASCII/数字占位
            if iname and (numeric(iname) or (placeholder.match(iname) and iname.lower() == cfg.lower())):
                if sname:
                    ascii_ph.append((cfg, iname, sname))
                    stats["占位名→已纠正"] += 1
                else:
                    ascii_ph_noidres.append((cfg, iname))
                    stats["占位名→仍无中文名"] += 1
            if not iname:
                if sname:
                    stats["空名→已补"] += 1
                else:
                    empty_noidres.append(cfg)
                    stats["空名→仍无名"] += 1
            # ③ 书类篇章错位：smax 落在拾遗区间但 INI 名不含拾遗；或含错序篇章词
            if script in ("SkillBook", "SkillPage", "SkillScroll") and smax.isdigit() and sname:
                want = None
                n = int(smax)
                tiers = [(9, "入门"), (18, "进阶"), (27, "中阶"), (30, "中阶"), (36, "高阶"),
                         (40, "精研"), (43, "精修"), (46, "深玄"), (49, "秘传"),
                         (52, "拾遗"), (56, "拾遗"), (60, "拾遗"), (64, "拾遗"),
                         (68, "拾遗"), (72, "拾遗"), (75, "拾遗"), (78, "拾遗"),
                         (82, "拾遗"), (86, "拾遗")]
                for cap, w in tiers:
                    if n == cap:
                        want = w
                        break
                if want and iname:
                    has_wrong = any(w in iname for w in tier_words if w != want)
                    missing = want not in iname
                    if has_wrong or (want == "拾遗" and missing):
                        tier_wrong.append((cfg, iname, sname, smax))
                        stats["篇章名错位→已纠正"] += 1
            # ④ 其余被纠正的差异
            if iname and sname and sname != iname and not numeric(iname) \
                    and iname.lower() != cfg.lower() \
                    and (cfg, iname, sname) not in tier_wrong:
                stats["其它名字差异→以idres为准"] += 1

    print("== 统计 ==")
    for k, v in stats.most_common():
        print("  %-24s %d" % (k, v))
    print()
    print("== 篇章名错位家族（书类，smax 顶层 vs INI 名）==", len(tier_wrong))
    fam = defaultdict(list)
    for cfg, iname, sname, smax in tier_wrong:
        fam[cfg.rsplit("_", 1)[0] if cfg.rsplit("_", 1)[1].endswith("th") else cfg].append((cfg, iname, sname))
    for f, rows in sorted(fam.items(), key=lambda x: -len(x[1]))[:12]:
        print("  %s: %d 本（%s …）" % (f, len(rows), rows[0][1] + " → " + rows[0][2]))
    print()
    print("== 重叠后缀（含 idres 修正情况）==", len(doubled))
    for cfg, iname, sname in doubled[:10]:
        print("  %-30s INI=%s idres=%s" % (cfg, iname, sname or "(无)"))
    print()
    print("== 仍无中文名（idres 缺键）==", len(ascii_ph_noidres) + len(empty_noidres))
    from collections import Counter as C2
    pref = C2(re.sub(r"[\d_]+$", "", c).rsplit("_", 1)[0] for c in empty_noidres)
    print("  空名前缀 top:", pref.most_common(8))
    pref2 = C2(c.rsplit("_", 1)[0] for c, _ in ascii_ph_noidres)
    print("  占位前缀 top:", pref2.most_common(8))


if __name__ == "__main__":
    main()
