import csv
import sys
from datetime import datetime

path = sys.argv[1]
rows = []
with open(path, newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        try:
            rows.append({
                "t": datetime.strptime(r["time"], "%Y-%m-%d %H:%M:%S"),
                "ms": int(r["epoch_ms"]),
                "v": int(r["volt"]), "a": int(r["amp"]), "soc": int(r["soc"]),
                "cmax": int(r["cell_max"]), "cmin": int(r["cell_min"]),
                "mt": int(r["motor_temp"]), "ct": int(r["ctrl_temp"]),
                "rpm": int(r["rpm"]), "sp": int(r["speed_raw"]), "dw": int(r["speed_dw"]),
                "nl": int(r["bms_nlhs"]), "bs": int(r["bms_bsmdfk"]),
                "mx": int(r["motor_djmxdl"]), "nj": int(r["motor_djnjyxw"]), "sj": int(r["motor_sjnj"]),
            })
        except (ValueError, KeyError):
            continue

print(f"rows: {len(rows)}   {rows[0]['t']}  ->  {rows[-1]['t']}")

gaps = [(a["t"], b["t"]) for a, b in zip(rows, rows[1:]) if (b["ms"] - a["ms"]) > 30000]
print("gaps > 30 s:", [(str(a)[11:], str(b)[11:]) for a, b in gaps] or "none")

moving = [r for r in rows if abs(r["rpm"]) > 30 or r["a"] != 0]
print(f"rows with |rpm|>30 or current!=0: {len(moving)}")
if moving:
    print("active window:", moving[0]["t"], "->", moving[-1]["t"])

print("\nstats over all rows:")
for k, label in [("a", "current A"), ("rpm", "rpm"), ("v", "volt"), ("soc", "soc"), ("sp", "speed_raw"),
                 ("dw", "speed_dw"), ("mt", "motor C"), ("ct", "ctrl C"), ("nl", "nlhs"), ("bs", "bsmdfk"),
                 ("mx", "djmxdl"), ("nj", "djnjyxw"), ("sj", "sjnj")]:
    vals = [r[k] for r in rows]
    print(f"  {label:10s} min {min(vals):7d}  max {max(vals):7d}  distinct {len(set(vals))}")

if moving:
    a0 = max(0, rows.index(moving[0]) - 3)
    a1 = min(len(rows), rows.index(moving[-1]) + 4)
    print("\nactive section (every 2nd row):")
    print("time      V    A    kW   SOC  rpm   spd dw  mtC ctC  nl bs mx  nj    sj")
    for r in rows[a0:a1:2]:
        kw = r["v"] * r["a"] / 1000
        print(f"{str(r['t'])[11:]} {r['v']:4d} {r['a']:4d} {kw:6.1f} {r['soc']:3d} {r['rpm']:5d} {r['sp']:4d} {r['dw']:2d} "
              f"{r['mt']:3d} {r['ct']:3d} {r['nl']:3d} {r['bs']:2d} {r['mx']:3d} {r['nj']:4d} {r['sj']:6d}")
