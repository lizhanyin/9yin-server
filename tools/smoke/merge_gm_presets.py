#!/usr/bin/env python3
"""Merge GM scene-preset sources into one table for Java GmScenePresets.

Sources:
  1. Go reference server cmd/protocol-probe/gm_scene_presets.go (curated 38)
  2. menu2 exe recovered table tools/smoke/menu2_preset_full.tsv (105,
     key/label/dest triples extracted from the 48-byte stride KV arrays)
Verifies every preset() slug in gm-web console.html resolves, then emits
the merged table as TSV.
"""
import re

GO_SRC = r"G:/pojie/ai/9yin-go-server1/cmd/protocol-probe/gm_scene_presets.go"
TSV = r"G:/pojie/ai/9yin-server/tools/smoke/menu2_preset_full.tsv"
HTML = r"G:/pojie/ai/9yin-server/gm-web/src/main/resources/nineyin/gm/console.html"
OUT = r"G:/pojie/ai/9yin-server/tools/smoke/gm_presets_merged.tsv"


def load():
    go = {}
    cur = None
    for line in open(GO_SRC, encoding="utf-8"):
        m = re.match(r'\s*"([a-z0-9]+)":\s*\{Label: "([^"]+)", Destination: `([^`]+)`', line)
        if m:
            go[m.group(1)] = (m.group(2), m.group(3))
            cur = m.group(1)
            continue
        m = re.match(r'\s*"([a-z0-9]+)":\s*\{Label: "([^"]+)"', line)
        if m:
            cur = m.group(1)
            go[cur] = (m.group(2), None)
            continue
        m = re.match(r"\s*Destination: `([^`]+)`", line)
        if m and cur and go[cur][1] is None:
            go[cur] = (go[cur][0], m.group(1))
    exe = {}
    for line in open(TSV, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 3:
            exe[p[0]] = (p[1], p[2])
    html = sorted(set(re.findall(r"preset\('([a-z0-9]+)'\)", open(HTML, encoding="utf-8").read())))
    return go, exe, html


def main():
    go, exe, slugs = load()
    print(f"go {len(go)}  exe {len(exe)}  html slugs {len(slugs)}")
    overlap = set(go) & set(exe)
    print("slug overlap go∩exe:", sorted(overlap) or "none")
    merged = {}
    gaps = []
    for s in slugs:
        if s in go and go[s][1]:
            merged[s] = go[s]
        elif s in exe:
            merged[s] = exe[s]
        else:
            gaps.append(s)
    print(f"resolved {len(merged)}/{len(slugs)}  gaps: {gaps}")
    rows = sorted(merged.items())
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for k, (lab, dest) in rows:
            f.write(f"{k}\t{lab}\t{dest}\n")
    print(f"wrote {OUT} ({len(rows)} entries)")


if __name__ == "__main__":
    main()
