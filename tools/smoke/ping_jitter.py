# -*- coding: utf-8 -*-
"""Track the client's ~1s ping (924) arrival jitter over the evening session.
If the client hitches periodically, ping deltas show aligned spikes."""
import re
from datetime import datetime
from collections import Counter

LOG = r"G:/pojie/ai/9yin-go-server1/logs/nineyin-server.log"
pat = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*message delay 924 response")

times = []
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        m = pat.match(line)
        if m:
            times.append(datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"))

# group into sessions by >60s gaps
sessions = []
cur = [times[0]]
for t in times[1:]:
    if (t - cur[-1]).total_seconds() > 60:
        sessions.append(cur)
        cur = [t]
    else:
        cur.append(t)
sessions.append(cur)

for s in sessions:
    deltas = [(b - a).total_seconds() for a, b in zip(s, s[1:])]
    if not deltas:
        continue
    buckets = Counter()
    for d in deltas:
        if d <= 1.15: buckets["<=1.15s (normal)"] += 1
        elif d <= 1.5: buckets["1.15-1.5s (minor)"] += 1
        elif d <= 2.5: buckets["1.5-2.5s (HITCH)"] += 1
        elif d <= 5: buckets["2.5-5s (BAD)"] += 1
        else: buckets[">5s (frozen/relog)"] += 1
    print(f"\n{s[0]:%H:%M:%S}-{s[-1]:%H:%M:%S} pings={len(s)}")
    for k in ["<=1.15s (normal)", "1.15-1.5s (minor)", "1.5-2.5s (HITCH)", "2.5-5s (BAD)", ">5s (frozen/relog)"]:
        if buckets.get(k):
            print(f"   {k}: {buckets[k]}")
