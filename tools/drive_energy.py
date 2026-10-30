import csv
import sys

rows = []
with open(sys.argv[1], newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        try:
            rows.append((int(r["epoch_ms"]), int(r["volt"]), int(r["amp"]), int(r["soc"]), int(r["rpm"])))
        except ValueError:
            pass

# only the evening drive (after the 20:33 restart), integrate within continuous stretches
start_ms = next(ms for ms, *_ in rows if ms >= 1793365980000 - 1) if False else None
evening = [r for r in rows if r[0] >= rows[0][0] + 13 * 3600 * 1000]  # about 20:11 onward

MAXDT = 8000  # ms: treat longer spacing as a gap
wh_dis = wh_reg = 0.0
revs = 0.0
covered_s = 0.0
for a, b in zip(evening, evening[1:]):
    dt = b[0] - a[0]
    if dt > MAXDT:
        continue
    p = a[1] * a[2]  # W, positive = discharging
    e = p * dt / 3.6e6  # Wh
    if e >= 0:
        wh_dis += e
    else:
        wh_reg += -e
    revs += abs(a[4]) * dt / 60000.0
    covered_s += dt / 1000.0

print(f"covered logging time: {covered_s/60:.1f} min")
print(f"energy discharged: {wh_dis:.0f} Wh   regen recovered: {wh_reg:.0f} Wh   net: {wh_dis - wh_reg:.0f} Wh")
soc0 = evening[0][3]
soc1 = evening[-1][3]
print(f"SOC {soc0} -> {soc1}  ({soc0 - soc1} %)   at 19 kWh that is {(soc0 - soc1) * 190:.0f} Wh (1 % resolution, so +/- 190 Wh)")
print(f"motor revolutions while logged: {revs:.0f}")
for ratio in (7.0, 8.0, 9.0, 10.0):
    for circ in (1.85, 1.95):
        km = revs / ratio * circ / 1000.0
        print(f"  gear ratio {ratio:4.1f}, wheel circ {circ:.2f} m -> {km:.2f} km")
