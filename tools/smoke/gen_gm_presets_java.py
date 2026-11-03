#!/usr/bin/env python3
"""Generate the Java GmScenePresets table body from the merged TSV."""
import io

TSV = r"G:/pojie/ai/9yin-server/tools/smoke/gm_presets_merged.tsv"
OUT = r"G:/pojie/ai/9yin-server/tools/smoke/gm_presets_body.txt"


def java_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    rows = []
    for line in io.open(TSV, encoding="utf-8"):
        key, label, dest = line.rstrip("\n").split("\t")
        config, resource, x, y, z, orient = dest.split(",")
        rows.append(
            "            {" + java_str(key) + ", " + java_str(label) + ", "
            + java_str(config) + ", " + java_str(resource)
            + ", " + f"{x}f, {y}f, {z}f, {orient}f" + "},")
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    print(f"wrote {OUT} rows={len(rows)}")


if __name__ == "__main__":
    main()
