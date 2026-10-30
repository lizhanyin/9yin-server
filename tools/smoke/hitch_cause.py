# -*- coding: utf-8 -*-
"""For each client hitch (recv gap 2s..15s) in the evening session, print the
last sends from the server before the client went silent, to find the trigger.
"""
import re
from datetime import datetime

LOG = r"G:/pojie/ai/9yin-go-server1/logs/wire.log"

recv_re = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[[^\]]+\] \w+ (recv|send) remote=\S+ opcode=(0x[0-9a-f]+) (?:decoded_)?len=(\d+)")

events = []
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        m = recv_re.match(line)
        if m:
            events.append((datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"),
                           m.group(2), m.group(3), int(m.group(4))))

evening = [e for e in events if e[0] >= datetime(2026, 10, 30, 21, 6)]
last_recv = None
tail_sends = []  # sends since last recv
for i, e in enumerate(evening):
    if e[1] == "send":
        tail_sends.append(e)
        continue
    if last_recv is not None:
        gap = (e[0] - last_recv).total_seconds() * 1000
        if 2000 <= gap <= 15000:
            print(f"\n=== HITCH {gap:.0f}ms  client silent {last_recv:%H:%M:%S.%f} -> {e[0]:%H:%M:%S.%f} ===")
            print("  last 12 sends before silence:")
            for s in tail_sends[-12:]:
                print(f"    {s[0]:%H:%M:%S.%f} send {s[2]} len={s[3]}")
    last_recv = e[0]
    tail_sends = []
