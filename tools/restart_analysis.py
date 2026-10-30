import csv
import re
import sys
from datetime import datetime

log_path, status_path = sys.argv[1:3]

rows = []
with open(log_path, newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        try:
            rows.append(datetime.strptime(r["time"], "%Y-%m-%d %H:%M:%S"))
        except (ValueError, KeyError):
            pass

starts = []
for line in open(status_path, encoding="utf-8", errors="replace"):
    m = re.match(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\s+started in process pid=(\d+) uid=(\d+)", line)
    if m:
        starts.append((datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S"), int(m.group(2))))

print(f"logger starts recorded: {len(starts)}")
print(f"{'started at':20s} {'pid':>6s}  last data row before   gap(last data -> start)   since previous start")
prev = None
for t, pid in starts:
    before = [r for r in rows if r < t]
    last = before[-1] if before else None
    gap = (t - last).total_seconds() if last else None
    since = (t - prev).total_seconds() if prev else None
    print(f"{str(t):20s} {pid:6d}  {str(last)[11:] if last else '-':>20s}   "
          f"{gap if gap is not None else '-':>10}               {since if since is not None else '-'}")
    prev = t
