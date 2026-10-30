import csv, sys
rows = []
with open(sys.argv[1], newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        try:
            if r["time"] >= "2026-10-30 23:15:00":
                rows.append((int(r["epoch_ms"]), int(r["rpm"]), int(r["raw_djzs"])))
        except (ValueError, KeyError):
            pass
bands = [(-100, 30), (30, 500), (500, 1000), (1000, 2000), (2000, 2800), (2800, 3200), (3200, 4000), (4000, 5000), (5000, 7000)]
tot = {b: 0.0 for b in bands}
for a, b in zip(rows, rows[1:]):
    dt = (b[0] - a[0]) / 1000.0
    if 0 < dt <= 8:
        for lo, hi in bands:
            if lo <= abs(a[1]) < hi:
                tot[(lo, hi)] += dt
total = sum(tot.values())
print(f"samples {len(rows)}, logged time {total/60:.1f} min")
print("rpm band        minutes   share")
for (lo, hi), s in tot.items():
    lab = "~0 (stopped)" if hi == 30 else f"{lo}-{hi}"
    print(f"{lab:14s} {s/60:8.1f}   {100*s/total:5.1f}%")
vals = sorted(r[1] for r in rows if abs(r[1]) > 30)
print("moving samples:", len(vals), " median rpm:", vals[len(vals)//2], " 90th percentile:", vals[int(len(vals)*0.9)], " max:", vals[-1])
print("raw value range (raw_djzs = rpm + 15000):", min(r[2] for r in rows), "to", max(r[2] for r in rows))
near = [r for r in rows if 2900 <= r[1] <= 3100]
print("samples between 2900 and 3100 rpm:", len(near))
