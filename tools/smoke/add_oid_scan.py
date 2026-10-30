# -*- coding: utf-8 -*-
"""Extract target oids from sent 0x0D (object add) frames in wire.log hex dumps.
0x0D frame: byte0=0x0d, oid=u32 LE @1..5. Count unique vs total per session."""
import re
from datetime import datetime
from collections import Counter

LOG = r"G:/pojie/ai/9yin-go-server1/logs/wire.log"

head_re = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) .*\bsend .*opcode=0xd len=(\d+)")
hex_re = re.compile(r"^([0-9a-f]{8})  ((?:[0-9a-f]{2} ?)+)")

oids = []
pending = None
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        m = head_re.match(line)
        if m:
            pending = (datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"), int(m.group(2)))
            continue
        if pending:
            h = hex_re.match(line)
            if h:
                data = h.group(2).split()
                if len(data) >= 5 and data[0] == "0d":
                    oid = int(data[1], 16) | (int(data[2], 16) << 8) | (int(data[3], 16) << 16) | (int(data[4], 16) << 24)
                    oids.append((pending[0], oid))
                pending = None

morning = [(t, o) for t, o in oids if t < datetime(2026, 10, 30, 12)]
evening = [(t, o) for t, o in oids if t >= datetime(2026, 10, 30, 21)]
for name, arr in [("MORNING(old)", morning), ("EVENING(new)", evening)]:
    if not arr:
        print(f"{name}: 0 object-add frames")
        continue
    c = Counter(o for _, o in arr)
    print(f"{name}: total={len(arr)} unique_oids={len(c)} "
          f"span={arr[0][0]:%H:%M:%S}-{arr[-1][0]:%H:%M:%S}")
    for oid, n in c.most_common(10):
        print(f"   oid {oid} added {n}x")
    # adds per minute
    permin = Counter(t.strftime("%H:%M") for t, _ in arr)
    print("   per-minute:", dict(sorted(permin.items())))
