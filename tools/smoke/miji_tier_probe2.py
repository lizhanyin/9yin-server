# -*- coding: utf-8 -*-
"""篇章/页书完整字段对比 + configId 后缀规约取证。"""
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

    samples = [
        "book_CS_em_jdmz01_rm", "book_CS_em_jdmz01_jj",
        "book_CS_em_jdmz0101", "book_CS_em_jdmz0102", "book_CS_em_jdmz0105",
        "ng_book_em_001_zj", "ng_book_em_001_gj",
        "ng_book_jh_219_10th_chuancheng",
    ]
    print("== 完整字段对比 ==")
    for cfg in samples:
        v = tool.get(cfg)
        if not v:
            print(cfg, "MISSING")
            continue
        print("[" + cfg + "] " + v.get("Name", ""))
        for k in sorted(v):
            print("    %s=%s" % (k, v[k]))

    # 同系书全列：梵心降魔 + 气桩功 + 晦明日月鉴
    print("== 同系书全列 ==")
    for prefix in ("book_CS_em_jdmz01", "ng_book_em_001", "ng_book_jh_219"):
        for cfg, v in tool.items():
            if cfg.startswith(prefix) and v.get("script") in ("SkillBook", "SkillPage", "SkillScroll"):
                print("  %-42s %s" % (cfg, v.get("Name", "")))

    # configId 后缀规约统计
    print("== 书 configId 后缀形态统计 ==")
    pat = Counter()
    for cfg, v in tool.items():
        if not v.get("LearnSkillPack") or v.get("script") not in ("SkillBook", "SkillPage", "SkillScroll"):
            continue
        m = re.search(r"_(\d+)$", cfg)
        tail = "_" + m.group(1) if m else "(no-digits)"
        # 后缀长度分布
        pat[(len(m.group(1)) if m else 0, v.get("script"))] += 1
    for k, n in sorted(pat.items(), key=lambda x: -x[1])[:12]:
        print("  digit-len=%s script=%s -> %d" % (k[0], k[1], n))

    # skill_new 里目标的 maxlevel（varprop 行数决定满级）——梵心降魔/气桩功
    print("== 目标满级（skill_new varprop 行数） ==")
    for tid in ("CS_em_jdmz01", "ng_em_001", "ng_jh_219", "CS_jh_tmby01"):
        v = skillrow(tid)
        if v is None:
            print("  %s: not found" % tid)
        else:
            keys = {k.lower(): v2 for k, v2 in v.items()}
            print("  %-14s %s" % (tid, {k: keys.get(k) for k in
                  ("maxlevel", "level", "staticdata", "itemtype", "name") if keys.get(k)}))
    # ng 走 neigong_static 区间
    ns = read_ini(SKILL_DIR + "/neigong/neigong_static.ini")
    for tid in ("ng_em_001", "ng_jh_219"):
        for sect, v in ns.items():
            if v.get("QName") == tid or sect == tid:
                print("  neigong_static[%s] %s" % (sect, {k: v.get(k) for k in
                      ("QName", "StaticData", "MinLevel", "MaxLevel", "Level")}))
                break


def skillrow(tid):
    sn = read_ini(SKILL_DIR + "/skill_new.ini")
    return sn.get(tid)


if __name__ == "__main__":
    main()
