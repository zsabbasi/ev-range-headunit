"""Reports the real power limits seen in an ev_log.csv: peak discharge and regen power, percentiles, and
whether regen plateaus (a cap) rather than just peaking once.

    py -I power_peaks.py ev_log.csv [start "YYYY-MM-DD HH:MM:SS"]

Power in kW = volt x amp / 1000 (positive = discharge, negative = regen).
"""
import csv
import sys


def load(path, start):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try:
                if r["time"] >= start:
                    rows.append((int(r["epoch_ms"]), r["time"], int(r["volt"]), int(r["amp"]), int(r["rpm"]),
                                 int(r["soc"])))
            except (ValueError, KeyError):
                continue
    return rows


def pct(sorted_vals, p):
    return sorted_vals[min(len(sorted_vals) - 1, max(0, int(round(p / 100.0 * (len(sorted_vals) - 1)))))]


def main():
    path = sys.argv[1]
    start = sys.argv[2] if len(sys.argv) > 2 else "2000-01-01 00:00:00"
    rows = load(path, start)
    if not rows:
        sys.exit("no rows")
    moving = [r for r in rows if abs(r[4]) > 30 or r[3] != 0]
    print(f"rows {len(rows)}, moving {len(moving)}, {rows[0][1]} -> {rows[-1][1]}")
    kw = [(r[2] * r[3] / 1000.0, r) for r in rows]
    dis = sorted(k for k, _ in kw if k > 0)
    reg = sorted(-k for k, _ in kw if k < 0)
    print(f"\ndischarge samples {len(dis)}  regen samples {len(reg)}")
    if dis:
        print("discharge kW  p50 %.1f  p90 %.1f  p99 %.1f  max %.1f" % (pct(dis, 50), pct(dis, 90), pct(dis, 99), dis[-1]))
    if reg:
        print("regen kW      p50 %.1f  p90 %.1f  p99 %.1f  max %.1f" % (pct(reg, 50), pct(reg, 90), pct(reg, 99), reg[-1]))
    amps = sorted(r[3] for r in rows)
    print(f"current A     min {amps[0]}  max {amps[-1]}")
    print(f"max |rpm|     {max(abs(r[4]) for r in rows)}")
    print(f"voltage       min {min(r[2] for r in rows)}  max {max(r[2] for r in rows)}")

    if reg:
        top = reg[-1]
        near = [k for k in reg if k >= 0.9 * top]
        print(f"\nregen plateau check: {len(near)} samples within 10 % of the maximum {top:.1f} kW")
    top_d = sorted(kw, key=lambda x: -x[0])[:5]
    print("\nfive strongest discharge samples:")
    for k, r in top_d:
        print(f"  {r[1]}  {k:5.1f} kW  {r[3]:4d} A  {r[2]} V  {abs(r[4])} rpm")
    top_r = sorted(kw, key=lambda x: x[0])[:5]
    print("five strongest regen samples:")
    for k, r in top_r:
        print(f"  {r[1]}  {k:5.1f} kW  {r[3]:4d} A  {r[2]} V  {abs(r[4])} rpm")


if __name__ == "__main__":
    main()
