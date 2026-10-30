"""Rebases the timestamps of an ev_log.csv to a neutral start so a log can be shared without revealing
when you drove. Relative timing, energy, rpm and SOC are untouched. Optionally keeps only rows from a
given epoch (ms) onwards.

    py -I anonymize_log.py in.csv out.csv [--from-epoch-ms N]

The new start is 2025-01-01 08:00:00 (UTC). The log has no coordinates.
"""
import csv
import sys
from datetime import datetime, timedelta, timezone

NEW_START = datetime(2025, 1, 1, 8, 0, 0, tzinfo=timezone.utc)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    from_ms = 0
    if "--from-epoch-ms" in sys.argv:
        from_ms = int(sys.argv[sys.argv.index("--from-epoch-ms") + 1])
    with open(src, newline="", encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        header = next(reader)
        rows = [r for r in reader if len(r) > 3 and r[0].isdigit() and int(r[0]) >= from_ms]
    if not rows:
        sys.exit("no rows selected")
    first = int(rows[0][0])
    shift_ms = int(NEW_START.timestamp() * 1000) - first
    with open(dst, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        for r in rows:
            ms = int(r[0]) + shift_ms
            r[0] = str(ms)
            r[1] = (NEW_START + timedelta(milliseconds=ms - int(NEW_START.timestamp() * 1000))).strftime("%Y-%m-%d %H:%M:%S")
            w.writerow(r)
    print(f"wrote {len(rows)} rows to {dst}")


if __name__ == "__main__":
    main()
