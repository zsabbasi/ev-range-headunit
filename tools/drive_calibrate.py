import csv
import sys
from datetime import datetime

path = sys.argv[1]
start = sys.argv[2] if len(sys.argv) > 2 else "2000-01-01 00:00:00"
rows = []
with open(path, newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        try:
            rows.append((int(r["epoch_ms"]), r["time"], int(r["volt"]), int(r["amp"]), int(r["soc"]), int(r["rpm"]),
                         int(r["motor_temp"]), int(r["ctrl_temp"])))
        except (ValueError, KeyError):
            pass
rows = [r for r in rows if r[1] >= start]
print(f"rows: {len(rows)}  {rows[0][1]} -> {rows[-1][1]}")

moving = [r for r in rows if abs(r[5]) > 30 or r[3] != 0]
print(f"moving rows: {len(moving)}  {moving[0][1]} -> {moving[-1][1]}")

MAXDT = 8000
wh_out = wh_reg = revs = 0.0
secs = 0.0
peak_kw = peak_reg = 0.0
max_rpm = 0
for a, b in zip(rows, rows[1:]):
    dt = b[0] - a[0]
    if dt <= 0 or dt > MAXDT:
        continue
    kw = a[2] * a[3] / 1000.0
    e = kw * dt / 3.6e6
    if e >= 0:
        wh_out += e * 1000
    else:
        wh_reg += -e * 1000
    revs += abs(a[5]) * dt / 60000.0
    secs += dt / 1000.0
    peak_kw = max(peak_kw, kw)
    peak_reg = min(peak_reg, kw)
    max_rpm = max(max_rpm, abs(a[5]))

print(f"covered time: {secs/60:.1f} min   max rpm {max_rpm}   peak discharge {peak_kw:.1f} kW   peak regen {peak_reg:.1f} kW")
print(f"energy: out {wh_out:.0f} Wh, regen {wh_reg:.0f} Wh, net {wh_out - wh_reg:.0f} Wh")
print(f"SOC {rows[0][4]} -> {rows[-1][4]}  ({rows[0][4]-rows[-1][4]} %, = {(rows[0][4]-rows[-1][4]) * 190:.0f} Wh at 19 kWh, +/-190)")
print(f"motor revolutions counted: {revs:.0f}")

fac_user = 32.0 / (3000 * 60)      # user: 3000 rpm = 32 km/h  -> km per motor rev
fac_default = 0.000211
for name, fac in (("user's 3000 rpm = 32 km/h", fac_user), ("old default 0.000211", fac_default)):
    km = revs * fac
    net = (wh_out - wh_reg) / 1000
    print(f"{name:28s}: {fac:.6f} km/rev -> {km:5.2f} km -> {net / km * 100:5.1f} kWh/100 km")

gps_km = float(sys.argv[3]) if len(sys.argv) > 3 else None
if gps_km:
    fac_gps = gps_km / revs
    print(f"if the real distance is {gps_km} km (GPS): {fac_gps:.6f} km/rev  "
          f"-> implied speed at 3000 rpm = {fac_gps * 3000 * 60:.1f} km/h")
