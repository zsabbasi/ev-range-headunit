"""Draws the EV Range launcher icon (no image libraries needed) at all Android densities."""
import math
import os
import struct
import sys
import zlib

BG = (14, 22, 33)
GREEN = (61, 220, 132)
GREY = (42, 58, 77)
WHITE = (234, 240, 247)
BOLT = [(0.555, 0.285), (0.395, 0.545), (0.485, 0.545), (0.435, 0.735), (0.615, 0.465), (0.52, 0.465)]


def in_poly(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def in_round_rect(x, y, lo, hi, r):
    if x < lo or x > hi or y < lo or y > hi:
        return False
    cx = min(max(x, lo + r), hi - r)
    cy = min(max(y, lo + r), hi - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def sample(x, y):
    """Returns (r, g, b, a) for a point in 0..1 icon space."""
    if not in_round_rect(x, y, 0.04, 0.96, 0.22):
        return (0, 0, 0, 0)
    color = BG
    dx, dy = x - 0.5, y - 0.5
    d = math.hypot(dx, dy)
    if 0.30 <= d <= 0.41:
        theta = math.degrees(math.atan2(dy, dx))  # 0 = right, 90 = down (screen coordinates)
        t = ((theta - 135.0) % 360.0) / 270.0
        if t <= 1.0:
            color = GREEN if t <= 0.72 else GREY
    if in_poly(x, y, BOLT):
        color = WHITE
    return color + (255,)


def render(size, ss=3):
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0
            for sy in range(ss):
                for sx in range(ss):
                    c = sample((px + (sx + 0.5) / ss) / size, (py + (sy + 0.5) / ss) / size)
                    r += c[0] * c[3]
                    g += c[1] * c[3]
                    b += c[2] * c[3]
                    a += c[3]
            n = ss * ss
            if a == 0:
                row += bytes((0, 0, 0, 0))
            else:
                row += bytes((int(r / a), int(g / a), int(b / a), int(a / n)))
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    out = sys.argv[1]
    for name, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = os.path.join(out, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), size, render(size))
        print("wrote", name, size)
    # a large preview to look at
    write_png(os.path.join(out, "preview_512.png"), 512, render(512, 2))
    print("preview written")
