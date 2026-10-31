"""Lines up the probe's value changes with the button taps recorded during a parked test.

    py -I probe_analyze.py carinfo_probe.csv markers.csv

Prints one time-ordered list of events: each marker (a button you tapped) and every value that changed, with
the time since the first reading. Values that change on almost every reading (counters, noise) are listed
separately instead of flooding the timeline. A partial last line (the log was being written) is ignored.
"""
import csv
import sys


def load_probe(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    header = rows[0]
    out = []
    for r in rows[1:]:
        if len(r) != len(header):
            continue
        try:
            out.append([int(x) for x in r])
        except ValueError:
            continue
    return header, out


def load_markers(path):
    marks = []
    try:
        with open(path, newline="", encoding="utf-8-sig") as f:
            for r in list(csv.reader(f))[1:]:
                if len(r) == 2 and r[0].isdigit():
                    marks.append((int(r[0]), r[1]))
    except FileNotFoundError:
        pass
    return marks


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    header, rows = load_probe(sys.argv[1])
    marks = load_markers(sys.argv[2]) if len(sys.argv) > 2 else []
    if not rows:
        sys.exit("no complete rows")
    t0 = rows[0][0]
    print(f"{len(rows)} readings over {(rows[-1][0] - t0) / 1000:.0f} s, {len(marks)} markers\n")

    events = []  # (time_ms, text)
    noisy = []
    for c in range(1, len(header)):
        vals = [r[c] for r in rows]
        if min(vals) == max(vals):
            continue
        changes = [(rows[i][0], vals[i - 1], vals[i]) for i in range(1, len(rows)) if vals[i] != vals[i - 1]]
        if len(changes) > 0.3 * len(rows) and len(changes) > 25:
            noisy.append((header[c], len(changes), min(vals), max(vals)))
            continue
        for t, a, b in changes:
            events.append((t, f"{header[c]}: {a} -> {b}"))
    for t, label in marks:
        events.append((t, f"*** MARKER: {label} ***"))
    events.sort(key=lambda e: (e[0], not e[1].startswith("***")))
    print("time since first reading | event")
    for t, text in events:
        print(f"{(t - t0) / 1000:8.1f} s | {text}")
    if noisy:
        print("\nvalues that change almost every reading (counters or noise):")
        for name, n, lo, hi in noisy:
            print(f"  {name}: {n} changes, range {lo}..{hi}")
    print("\nvalues that never changed: " + str(sum(1 for c in range(1, len(header)) if len({r[c] for r in rows}) == 1)) + " of " + str(len(header) - 1))
    first = rows[0]
    last = rows[-1]
    for c in range(1, len(header)):
        if header[c].split(":")[1] in ("Stalls(gear)", "DriveMode", "Distance", "HandBrake", "FootBrake", "ACC", "ChargeSta"):
            print(f"  {header[c]}: first {first[c]}, last {last[c]}")


if __name__ == "__main__":
    main()
