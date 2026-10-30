# -*- coding: utf-8 -*-
"""Scan wire.log for client-side hitches: gaps between consecutive client recv
frames longer than THRESH ms, grouped by session segment (old build vs new).

A hitch = ServerWorker saw no client frame for >THRESH ms while the session was
already established (skip the first 30s after server start of each segment).
"""
import re
import sys
from datetime import datetime, timedelta

LOG = r"G:/pojie/ai/9yin-go-server1/logs/wire.log"
THRESH = 700  # ms

recv_re = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] \w+ (recv|send) remote=\S+ opcode=(0x[0-9a-f]+) (?:decoded_)?len=(\d+)")

def to_ms(ts):
    d = datetime.strptime(ts, "%Y-%m-%d %H:%M:%S.%f")
    return d

events = []  # (time, dir, opcode, thread)
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        m = recv_re.match(line)
        if m:
            events.append((to_ms(m.group(1)), m.group(3), m.group(4)))

# segment by wall-clock gap > 120s (new connection)
segments = []
cur = [events[0]]
for ev in events[1:]:
    if (ev[0] - cur[-1][0]).total_seconds() > 120:
        segments.append(cur)
        cur = [ev]
    else:
        cur.append(ev)
segments.append(cur)

print("segments:", len(segments))
for seg in segments:
    t0, t1 = seg[0][0], seg[-1][0]
    # skip first 20s (login/loading noise)
    steady = [e for e in seg if (e[0] - t0).total_seconds() > 20]
    hitches = []
    prev = None
    for e in steady:
        if e[1] == "recv":
            if prev is not None:
                gap = (e[0] - prev).total_seconds() * 1000
                if gap > THRESH:
                    hitches.append((gap, prev))
            prev = e[0]
    n_recv = sum(1 for e in steady if e[1] == "recv")
    dur_min = (t1 - t0).total_seconds() / 60
    if not hitches:
        print(f"{t0:%H:%M:%S}-{t1:%H:%M:%S} ({dur_min:5.1f} min) recv={n_recv:6d} hitches=0")
        continue
    big = [h for h in hitches if h[0] > 2000]
    print(f"{t0:%H:%M:%S}-{t1:%H:%M:%S} ({dur_min:5.1f} min) recv={n_recv:6d} "
          f"hitches(>{THRESH}ms)={len(hitches)} of which >2s: {len(big)} "
          f"max={max(h[0] for h in hitches):.0f}ms")
    for gap, at in sorted(hitches, reverse=True)[:8]:
        print(f"    {at:%H:%M:%S.%f}  gap={gap:6.0f}ms")
