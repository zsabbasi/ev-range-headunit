#!/usr/bin/env python3
"""Estimate real EV consumption and range from manual readings, with optional GPS cross-check.

Readings CSV columns (header required):
    time          ISO local time, e.g. 2026-10-29 08:15   (needed only for --gpx)
    odo_km        odometer reading
    soc_pct       dashboard battery percentage
    dash_range_km optional: range the car shows at that moment

Usage:
    python ev_range.py readings.csv
    python ev_range.py readings.csv --capacity 19 --min-soc-drop 5 --gpx drive.gpx
"""
import argparse
import csv
import math
import sys
import xml.etree.ElementTree as ET
from datetime import datetime


def parse_time(s):
    s = s.strip().replace("Z", "+00:00")
    dt = datetime.fromisoformat(s)
    return dt if dt.tzinfo else dt.astimezone()  # naive -> local time zone


def haversine_km(a, b):
    r = 6371.0088
    p1, p2 = math.radians(a[1]), math.radians(b[1])
    dp, dl = p2 - p1, math.radians(b[2] - a[2])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def load_gpx(path):
    pts = []
    for el in ET.parse(path).iter():
        if el.tag.endswith("trkpt"):
            t = next((c.text for c in el if c.tag.endswith("time")), None)
            if t:
                pts.append((parse_time(t), float(el.get("lat")), float(el.get("lon"))))
    pts.sort(key=lambda p: p[0])
    return pts


def gps_km(pts, t0, t1):
    inside = [p for p in pts if t0 <= p[0] <= t1]
    return sum(haversine_km(a, b) for a, b in zip(inside, inside[1:]))


def load_readings(path):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            rows.append({
                "time": parse_time(r["time"]) if r.get("time", "").strip() else None,
                "odo": float(r["odo_km"]),
                "soc": float(r["soc_pct"]),
                "dash": float(r["dash_range_km"]) if r.get("dash_range_km", "").strip() else None,
            })
    return rows


def segments(rows, min_drop):
    """Merge consecutive readings into segments with at least min_drop % SOC used; skip charging."""
    out, anchor = [], rows[0]
    for r in rows[1:]:
        if r["soc"] > anchor["soc"]:
            anchor = r  # charged in between: restart
        elif anchor["soc"] - r["soc"] >= min_drop:
            out.append((anchor, r))
            anchor = r
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--capacity", type=float, default=19.0, help="usable battery kWh (default 19)")
    ap.add_argument("--min-soc-drop", type=float, default=5.0,
                    help="min %% SOC per segment; small drops are too noisy (default 5)")
    ap.add_argument("--window-km", type=float, default=50.0,
                    help="distance used for the recent-driving range estimate (default 50)")
    ap.add_argument("--gpx", help="GPX track to cross-check odometer distance")
    a = ap.parse_args()

    rows = load_readings(a.csv)
    if len(rows) < 2:
        sys.exit("Need at least 2 readings.")
    pts = load_gpx(a.gpx) if a.gpx else None
    segs = segments(rows, a.min_soc_drop)
    if not segs:
        sys.exit(f"No segment with >= {a.min_soc_drop}% SOC drop yet. Drive more or lower --min-soc-drop.")

    print(f"{'start':<16} {'odo km':>7} {'gps km':>7} {'odo/gps':>8} {'SOC%':>5} {'kWh':>5} {'Wh/km':>6} {'range@100%':>10}")
    tot_km = tot_kwh = 0.0
    for s, e in segs:
        km = e["odo"] - s["odo"]
        kwh = (s["soc"] - e["soc"]) / 100 * a.capacity
        whkm = kwh * 1000 / km if km > 0 else float("nan")
        g = ratio = ""
        if pts and s["time"] and e["time"]:
            gk = gps_km(pts, s["time"], e["time"])
            g, ratio = f"{gk:7.1f}", f"{km / gk:8.3f}" if gk > 0 else ""
        start = s["time"].strftime("%m-%d %H:%M") if s["time"] else f"odo {s['odo']:.0f}"
        full = a.capacity * 1000 / whkm if whkm > 0 else float("nan")
        print(f"{start:<16} {km:7.1f} {g:>7} {ratio:>8} {s['soc'] - e['soc']:5.0f} {kwh:5.2f} {whkm:6.0f} {full:10.0f}")
        if km > 0:
            tot_km += km
            tot_kwh += kwh

    whkm = tot_kwh * 1000 / tot_km
    full = a.capacity * 1000 / whkm
    now = rows[-1]
    print("\n--- Summary ---")
    print(f"Distance {tot_km:.1f} km, energy {tot_kwh:.2f} kWh -> {whkm:.0f} Wh/km")
    print(f"Real range on a full {a.capacity:g} kWh pack: {full:.0f} km")
    print(f"Real range at the latest SOC ({now['soc']:.0f}%): {full * now['soc'] / 100:.0f} km")

    # Recent-driving estimate: newest segments until they cover --window-km
    w_km = w_kwh = 0.0
    for s, e in reversed(segs):
        km = e["odo"] - s["odo"]
        if km <= 0:
            continue
        w_km += km
        w_kwh += (s["soc"] - e["soc"]) / 100 * a.capacity
        if w_km >= a.window_km:
            break
    if w_km > 0 and w_kwh > 0:
        w_whkm = w_kwh * 1000 / w_km
        print(f"Last {w_km:.0f} km: {w_whkm:.0f} Wh/km -> range at {now['soc']:.0f}% SOC: "
              f"{a.capacity * now['soc'] / 100 * 1000 / w_whkm:.0f} km")

    shown = [r for r in rows if r["dash"] and r["soc"] > 0]
    if shown:
        implied = [r["dash"] / (r["soc"] / 100) for r in shown]
        avg = sum(implied) / len(implied)
        print(f"Dashboard range implies ~{avg:.0f} km at 100% -> it is "
              f"{(avg / full - 1) * 100:+.0f}% vs measured")
    print("\nNotes: dashboard SOC is rarely linear (especially above 90% and below 15%); "
          "use drives in the 20-90% band and a larger --min-soc-drop for better accuracy. "
          "Range varies with speed, temperature and A/C.")


if __name__ == "__main__":
    main()
