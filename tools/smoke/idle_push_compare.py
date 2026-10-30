# -*- coding: utf-8 -*-
"""Compare server push profile during IDLE windows (client sends only pings)
between the old build (morning 01:14-01:18) and new build (evening 21:19+).
Focus: opcode histogram + sizes of periodic pushes."""
import re
from datetime import datetime
from collections import Counter

LOG = r"G:/pojie/ai/9yin-go-server1/logs/wire.log"

recv_re = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] \w+ (recv|send) remote=\S+ opcode=(0x[0-9a-f]+) (?:decoded_)?len=(\d+)")

events = []
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        m = recv_re.match(line)
        if m:
            events.append((datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"),
                           m.group(3), m.group(4), int(m.group(5)), m.group(2)))

WINDOWS = [
    ("OLD 01:15:30-01:16:30 (idle+combat)", datetime(2026,10,30,1,15,30), datetime(2026,10,30,1,16,30)),
    ("NEW 21:07:30-21:08:30 (post-login)", datetime(2026,10,30,21,7,30), datetime(2026,10,30,21,8,30)),
    ("NEW 21:23:00-21:24:00 (post-relog)", datetime(2026,10,30,21,23,0), datetime(2026,10,30,21,24,0)),
]
for name, t0, t1 in WINDOWS:
    win = [e for e in events if t0 <= e[0] < t1]
    sends = [e for e in win if e[1] == "send"]
    recvs = [e for e in win if e[1] == "recv"]
    c = Counter()
    size = {}
    for e in sends:
        c[e[2]] += 1
        size.setdefault(e[2], []).append(e[3])
    print(f"\n== {name}: recv={len(recvs)} send={len(sends)} ({(t1-t0).total_seconds():.0f}s) ==")
    for op, n in c.most_common(8):
        ss = size[op]
        print(f"  {op} x{n:4d}  len min/avg/max = {min(ss)}/{sum(ss)//len(ss)}/{max(ss)}")
    # recv gap histogram
    gaps = []
    prev = None
    for e in recvs:
        if prev is not None:
            gaps.append((e[0]-prev).total_seconds()*1000)
        prev = e[0]
    if gaps:
        h = Counter(int(g//250)*250 for g in gaps)
        print("  recv-gap buckets(ms):", dict(sorted(h.items())))
