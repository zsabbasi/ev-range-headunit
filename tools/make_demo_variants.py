import csv, sys
src = sys.argv[1]
rows = list(csv.reader(open(src, newline="", encoding="utf-8")))
header, data = rows[0], rows[1:]
last = data[-1]
def variant(name, volt, amp, rpm, soc):
    out = [list(r) for r in data]
    for i in (1, 2):
        r = list(last)
        r[0] = str(int(last[0]) + 3000 * i)
        r[2] = str(volt); r[3] = str(amp); r[4] = str(soc); r[9] = str(rpm)
        r[17] = str(amp + 500); r[18] = str(rpm + 15000)
        out.append(r)
    with open(sys.argv[2] + "/demo_" + name + ".csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n"); w.writerow(header); w.writerows(out)
variant("high", 313, 114, 5000, 70)     # +35.7 kW, the measured acceleration limit
variant("regen", 325, -34, 6900, 70)    # -11.1 kW, the strongest measured regen
variant("mid", 328, 40, 3000, 70)       # +13.1 kW, moderate load
print("variants written")
