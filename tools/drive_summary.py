"""One-page summary of a drive from the feed log (ev_log.csv) and, optionally, the app's GPS/rpm log.

    py -I drive_summary.py ev_log.csv [gps_rpm_log.csv] [start "YYYY-MM-DD HH:MM:SS"] [km_per_rev]

Reports energy used and recovered, distance (from calibrated motor rpm and, if the GPS log is given, from
GPS speed), consumption both ways, top speeds, and how closely the two distances agree. Start the window
before the first movement: a window that cuts off the beginning of a drive gives wrong totals.
Default km per motor revolution is the calibrated 0.0001722 (31.0 km/h at 3000 rpm).
"""
import csv
import sys

K_DEFAULT = 0.0001722


def read_log(path, start):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try:
                if r["time"] >= start:
                    rows.append((int(r["epoch_ms"]), int(r["volt"]), int(r["amp"]), int(r["soc"]), abs(int(r["rpm"]))))
            except (ValueError, KeyError):
                continue
    return rows


def read_gps(path, start):
    out = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try:
                if r["time"] >= start:
                    out.append((int(r["epoch_ms"]), abs(int(r["rpm"])), float(r["gps_kmh"])))
            except (ValueError, KeyError):
                continue
    return out


def main():
    args = [a for a in sys.argv[1:]]
    if not args:
        sys.exit(__doc__)
    log = args[0]
    gps_path = args[1] if len(args) > 1 and args[1].lower().endswith(".csv") else None
    rest = [a for a in args[1:] if a != gps_path]
    start = rest[0] if rest else "2000-01-01 00:00:00"
    k = float(rest[1]) if len(rest) > 1 else K_DEFAULT

    rows = read_log(log, start)
    if len(rows) < 2:
        sys.exit("not enough rows in the chosen window")
    wh_out = wh_reg = revs = secs = 0.0
    for a, b in zip(rows, rows[1:]):
        dt = (b[0] - a[0]) / 1000.0
        if 0 < dt <= 8:  # longer steps are idle logging or gaps
            e = (a[1] * a[2] + b[1] * b[2]) / 2000.0 * dt / 3.6
            if e >= 0:
                wh_out += e
            else:
                wh_reg += -e
            revs += (a[4] + b[4]) / 2.0 * dt / 60.0
            secs += dt
    net = wh_out - wh_reg
    km_rpm = revs * k
    print(f"window: {len(rows)} rows, {secs / 60:.1f} min of 2 s logging")
    print(f"SOC {rows[0][3]} -> {rows[-1][3]} %  = {(rows[0][3] - rows[-1][3]) * 190:.0f} Wh at 19 kWh (whole percent, +/-190)")
    print(f"energy: out {wh_out:.0f} Wh, regen {wh_reg:.0f} Wh ({100 * wh_reg / max(wh_out, 1):.0f} % recovered), net {net:.0f} Wh")
    print(f"distance by rpm x {k:.7f} km/rev: {km_rpm:.2f} km  ->  {net / max(km_rpm, 1e-9) / 10:.1f} kWh/100 km")
    print(f"max rpm {max(r[4] for r in rows)} -> {max(r[4] for r in rows) * k * 60:.0f} km/h; 105 km/h would be {105 / (k * 60):.0f} rpm")

    if gps_path:
        g = read_gps(gps_path, start)
        km_g = km_r = cov = top = 0.0
        for a, b in zip(g, g[1:]):
            dt = (b[0] - a[0]) / 1000.0
            if 0 < dt <= 6:
                km_g += (a[2] + b[2]) / 2 / 3600 * dt
                km_r += (a[1] + b[1]) / 2 * dt / 60 * k
                cov += dt
            top = max(top, a[2])
        if km_g > 0:
            print(f"\nGPS log ({len(g)} rows, {cov / 60:.1f} min with a fix): GPS distance {km_g:.2f} km, rpm distance on the same rows "
                  f"{km_r:.2f} km (ratio {km_r / km_g:.3f})")
            print(f"consumption by GPS distance: {net / km_g / 10:.1f} kWh/100 km;  top GPS speed {top:.0f} km/h")


if __name__ == "__main__":
    main()
