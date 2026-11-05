# -*- coding: utf-8 -*-
"""秘籍目标技能 → game_skills 覆盖率探针（v2：拆分多目标）。"""
import io
import subprocess
import sys

ITEM_DIR = r"F:/Game/Online/9yin/9yin_server_new/resources/modern/share/item"
MYSQL = r"G:/pojie/ai/mysql-26/bin/mysql.exe"


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
    books = {k: v for k, v in tool.items() if v.get("script", "").lower() == "skillbook"}

    targets = set()
    by_item_type = {}
    for cfg, v in books.items():
        pack = v.get("LearnSkillPack")
        if not pack:
            continue
        row = learn.get(pack)
        if not row or not row.get("TragetID"):
            continue
        for sep in (",", ";"):
            raw = row["TragetID"]
            for tok in raw.replace(";", ",").split(","):
                tok = tok.strip()
                if tok:
                    targets.add(tok)
                    by_item_type.setdefault(v.get("ItemType", "?"), set()).add(tok)

    ids = sorted(targets)
    print("distinct targets:", len(ids))

    # game_skills 覆盖
    found = set()
    chunk = 500
    for i in range(0, len(ids), chunk):
        part = ids[i:i + chunk]
        q = "SELECT skill_id FROM game_skills WHERE skill_id IN ('%s');" % "','".join(part)
        r = subprocess.run([MYSQL, "-unineyin", "-pnineyin123", "-h127.0.0.1", "-P3306",
                            "-N", "-B", "nineyin", "-e", q],
                           capture_output=True, text=True)
        for line in r.stdout.splitlines():
            found.add(line.strip())
    missing = [t for t in ids if t not in found]
    print("in game_skills: %d, missing: %d" % (len(found), len(missing)))
    print("missing sample:", missing[:25])

    # 按 ItemType 统计覆盖
    for it, ts in sorted(by_item_type.items()):
        miss = [t for t in ts if t not in found]
        print("ItemType %s: targets=%d missing=%d miss_sample=%s"
              % (it, len(ts), len(miss), miss[:6]))


if __name__ == "__main__":
    main()
