"""Fit motor rpm to GPS speed: finds the time offset (sync) and km per motor revolution.

Input: gps_rpm_log.csv written by the EV Range app while moving with a GPS fix:
    epoch_ms,time,rpm,gps_kmh,gps_alt_m,gps_acc_m,amp,volt

Usage:
    py -I calibrate_speed.py gps_rpm_log.csv
    py -I calibrate_speed.py --selftest        (simulates a drive with a known answer)

What it reports:
  * best time offset between the rpm series and the GPS speed series (seconds)
  * speed per rpm, km per motor revolution, and "speed at 3000 rpm" (the Settings value)
  * how well a single ratio fits (residual), and whether the ratio changes with speed band
    (a gear change or slip would show up there) or with road gradient (incline effect)
Only the standard library is used.
"""
import csv
import math
import random
import sys


def load(path):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    "t": int(r["epoch_ms"]) / 1000.0,
                    "rpm": abs(int(r["rpm"])),
                    "v": float(r["gps_kmh"]),
                    "alt": float(r["gps_alt_m"]) if r.get("gps_alt_m") else None,
                    "acc": float(r["gps_acc_m"]) if r.get("gps_acc_m") else None,
                })
            except (ValueError, KeyError):
                continue
    rows.sort(key=lambda x: x["t"])
    return rows


def interp(rows, key, t):
    """Linear interpolation of rows[key] at time t; None if t is outside or inside a long gap."""
    lo, hi = 0, len(rows) - 1
    if t < rows[0]["t"] or t > rows[hi]["t"]:
        return None
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if rows[mid]["t"] <= t:
            lo = mid
        else:
            hi = mid
    a, b = rows[lo], rows[hi]
    if b["t"] - a["t"] > 6.0 or a[key] is None or b[key] is None:
        return None
    w = (t - a["t"]) / (b["t"] - a["t"]) if b["t"] > a["t"] else 0.0
    return a[key] + w * (b[key] - a[key])


def fit(rows, lag, min_v=5.0, min_rpm=100):
    """Through-origin least squares v = k * rpm using gps speed at time t + lag."""
    sxy = sxx = 0.0
    pairs = []
    for r in rows:
        if r["rpm"] < min_rpm:
            continue
        v = interp(rows, "v", r["t"] + lag)
        if v is None or v < min_v:
            continue
        pairs.append((r["rpm"], v, r))
        sxy += r["rpm"] * v
        sxx += r["rpm"] ** 2
    if len(pairs) < 20 or sxx == 0:
        return None
    k = sxy / sxx
    ss = sum((v - k * rpm) ** 2 for rpm, v, _ in pairs)
    mean_v = sum(v for _, v, _ in pairs) / len(pairs)
    tot = sum((v - mean_v) ** 2 for _, v, _ in pairs)
    return {"k": k, "rms": math.sqrt(ss / len(pairs)), "r2": 1 - ss / tot if tot > 0 else 0.0,
            "n": len(pairs), "pairs": pairs}


def median(xs):
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2


def analyse(rows):
    rows = [r for r in rows if r["acc"] is None or r["acc"] <= 20]
    print(f"usable rows: {len(rows)}")
    best = None
    print("\nlag (s)   rms residual (km/h)   R2     pairs")
    lag = -6.0
    while lag <= 6.0001:
        f = fit(rows, lag)
        if f:
            mark = ""
            if best is None or f["rms"] < best[1]["rms"]:
                best = (lag, f)
            print(f"{lag:6.1f}   {f['rms']:12.3f}   {f['r2']:8.4f}   {f['n']:5d}")
        lag += 1.0
    if best is None:
        print("\nNot enough moving samples with a GPS fix (need at least 20 above 5 km/h).")
        return
    # refine around the best whole second
    center = best[0]
    lag = center - 1.0
    while lag <= center + 1.0001:
        f = fit(rows, lag)
        if f and f["rms"] < best[1]["rms"]:
            best = (lag, f)
        lag += 0.25
    lag, f = best
    k = f["k"]
    print(f"\nBEST OFFSET: {lag:+.2f} s   (positive: GPS speed at t+offset matches the rpm read at t)")
    print(f"speed per rpm        : {k:.6f} km/h per rpm")
    print(f"km per motor rev     : {k / 60.0:.7f}")
    print(f"speed at 3000 rpm    : {k * 3000:.2f} km/h   <-- value for Settings")
    print(f"fit quality          : rms {f['rms']:.2f} km/h, R2 {f['r2']:.4f}, {f['n']} samples")
    ratios = [v / rpm for rpm, v, _ in f["pairs"]]
    med = median(ratios)
    mad = median([abs(x - med) for x in ratios])
    print(f"robust (median) ratio: {med * 3000:.2f} km/h at 3000 rpm, spread (MAD) {mad * 3000:.2f}")

    print("\nratio by speed band (should be flat if there is one fixed ratio):")
    for lo, hi in ((5, 20), (20, 40), (40, 60), (60, 200)):
        sel = [v / rpm for rpm, v, _ in f["pairs"] if lo <= v < hi]
        if len(sel) >= 8:
            print(f"  {lo:3d}-{hi:3d} km/h : {median(sel) * 3000:6.2f} km/h at 3000 rpm   ({len(sel)} samples)")

    # gradient: altitude change over about 10 s windows
    grade_groups = {"downhill (< -2 %)": [], "flat": [], "uphill (> +2 %)": []}
    for rpm, v, r in f["pairs"]:
        a0 = interp(rows, "alt", r["t"] - 5)
        a1 = interp(rows, "alt", r["t"] + 5)
        if a0 is None or a1 is None or v < 10:
            continue
        dist = v / 3.6 * 10.0
        if dist < 20:
            continue
        g = (a1 - a0) / dist * 100.0
        key = "downhill (< -2 %)" if g < -2 else "uphill (> +2 %)" if g > 2 else "flat"
        grade_groups[key].append(v / rpm)
    shown = False
    for key, sel in grade_groups.items():
        if len(sel) >= 8:
            if not shown:
                print("\nratio by road gradient (GPS altitude is noisy; treat small differences as noise):")
                shown = True
            print(f"  {key:18s}: {median(sel) * 3000:6.2f} km/h at 3000 rpm   ({len(sel)} samples)")

    default = 0.000211 * 3000 * 60
    print(f"\nfor comparison: the app's old default is {default:.1f} km/h at 3000 rpm; "
          f"the dashboard figure you quoted was 32.0 km/h at 3000 rpm")


def selftest():
    """Simulated drive: true ratio 32.0 km/h at 3000 rpm, GPS speed lags the rpm by 1.5 s."""
    random.seed(7)
    true_k = 32.0 / 3000.0
    lag_true = 1.5
    t0 = 1_700_000_000.0

    def speed(t):  # smooth stop-and-go profile, km/h
        x = (t - t0) / 40.0
        return max(0.0, 36 + 28 * math.sin(x) + 10 * math.sin(2.3 * x))

    rows = []
    t = t0
    while t < t0 + 1200:
        rpm = speed(t) / true_k + random.gauss(0, 12)
        gps = speed(t - lag_true) + random.gauss(0, 0.4)
        rows.append({"t": t, "rpm": abs(rpm), "v": max(0.0, gps), "alt": None, "acc": 4.0})
        t += 2.0 + random.uniform(-0.1, 0.1)
    print("SELFTEST: truth = 32.00 km/h at 3000 rpm, offset +1.50 s\n")
    analyse(rows)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--selftest":
        selftest()
    else:
        analyse(load(sys.argv[1]))
