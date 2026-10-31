"""Power available and power used at each speed, from one or more drive windows of the feed log.

    py -I power_by_speed.py LOG START END [LOG START END ...]

Each window is a log file plus the start and end time ("YYYY-MM-DD HH:MM:SS") of a drive, so idle logging
before and after is left out. Speeds use the calibrated ratio (31.0 km/h at 3000 rpm).

Prints: a per-drive summary (top rpm and speed, peak discharge and regen power, peak current), the power
envelope by motor-speed band (the most power ever drawn or regenerated there), and the power used during
steady cruising versus changing speed by speed band. Use it to check how a power bar scale compares with
what the car really does at each speed, and whether the peaks are plateaus (a limit) or one-off spikes.
"""
import csv
import sys

K = 0.0001722 * 60  # km/h per rpm
BANDS = [(0, 1000), (1000, 2000), (2000, 3000), (3000, 4000), (4000, 5000), (5000, 6000),
         (6000, 7000), (7000, 8000), (8000, 10500)]


def load(path, a, b):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try:
                if a <= r["time"] <= b:
                    rows.append((int(r["epoch_ms"]), int(r["volt"]), int(r["amp"]), abs(int(r["rpm"]))))
            except (ValueError, KeyError):
                continue
    return rows


def med(x):
    x = sorted(x)
    return x[len(x) // 2]


def pct(x, q):
    x = sorted(x)
    return x[min(len(x) - 1, int(q * len(x)))]


def main():
    args = sys.argv[1:]
    if len(args) < 3 or len(args) % 3:
        sys.exit(__doc__)
    env_d = {b: 0.0 for b in BANDS}
    env_r = {b: 0.0 for b in BANDS}
    cnt = {b: 0 for b in BANDS}
    cruise, change = {}, {}
    print(f"{'window':34s} {'max rpm':>8s} {'km/h':>6s} {'max kW':>7s} {'max A':>6s} {'regen kW':>9s}")
    for i in range(0, len(args), 3):
        path, a, b = args[i:i + 3]
        rows = load(path, a, b)
        if not rows:
            print(f"{a:34s} (no rows)")
            continue
        kw = [r[1] * r[2] / 1000.0 for r in rows]
        mr = max(r[3] for r in rows)
        print(f"{a:34s} {mr:8d} {mr * K:6.0f} {max(kw):7.1f} {max(r[2] for r in rows):6d} {-min(kw):9.1f}")
        for r, k in zip(rows, kw):
            for bd in BANDS:
                if bd[0] <= r[3] < bd[1]:
                    cnt[bd] += 1
                    env_d[bd] = max(env_d[bd], k)
                    env_r[bd] = max(env_r[bd], -k)
        p3 = list(zip(rows, rows[1:], rows[2:]))
        for p, c, n in p3:
            if not 0 < (n[0] - p[0]) / 1000.0 <= 6:
                continue
            v = c[3] * K
            if v < 5:
                continue
            band = int(v // 10) * 10
            steady = abs(n[3] - p[3]) < 150
            (cruise if steady else change).setdefault(band, []).append(c[1] * c[2] / 1000.0)

    print("\npower envelope by motor speed (most power ever seen in each band):")
    print(f"{'rpm band':>12s} {'km/h':>11s} {'samples':>8s} {'max discharge kW':>17s} {'max regen kW':>13s}")
    for bd in BANDS:
        print(f"{bd[0]:5d}-{bd[1]:<6d} {bd[0] * K:4.0f}-{bd[1] * K:<5.0f} {cnt[bd]:8d} {env_d[bd]:17.1f} {env_r[bd]:13.1f}")

    print("\nspeed band  | steady speed: samples, median kW, p90 kW | changing speed: samples, median kW, p90 kW, max kW")
    for band in sorted(set(cruise) | set(change)):
        c, ch = cruise.get(band, []), change.get(band, [])
        left = f"{len(c):4d} {med(c):7.1f} {pct(c, .9):7.1f}" if len(c) >= 8 else "   -       -       -"
        right = f"{len(ch):4d} {med(ch):7.1f} {pct(ch, .9):7.1f} {max(ch):7.1f}" if len(ch) >= 8 else "   -       -       -       -"
        print(f"{band:3d}-{band + 10:<3d} km/h | {left} | {right}")
    print(f"\nfor reference: 10,000 rpm = {10000 * K:.1f} km/h; 65 km/h = {65 / K:.0f} rpm; 105 km/h = {105 / K:.0f} rpm")


if __name__ == "__main__":
    main()
