"""Generates the AAFin logo: vector-drawable path data and docs/logo-512.png.

The artwork is original: a rounded triangle (nod to Jellyfin) filled with a
purple-to-blue gradient, carrying a white navigation arrowhead (nod to Android Auto).
Run:  python docs/tools/make_logo.py   (needs Pillow)
"""
import math
import os

from PIL import Image, ImageDraw

VIEW = 108.0  # adaptive-icon viewport
TRI = [(54.0, 23.0), (83.0, 73.0), (25.0, 73.0)]  # apex, bottom-right, bottom-left
ARROW = [(54.0, 39.0), (66.5, 66.0), (54.0, 59.5), (41.5, 66.0)]
CORNER = 12.0  # distance along each edge where the corner rounding starts
PURPLE, BLUE, DARK = (0xAA, 0x5C, 0xC3), (0x00, 0xA4, 0xDC), (0x10, 0x10, 0x18)


def toward(p, q, dist):
    length = math.hypot(q[0] - p[0], q[1] - p[1])
    return (p[0] + (q[0] - p[0]) * dist / length, p[1] + (q[1] - p[1]) * dist / length)


def rounded_segments(points, dist):
    """Returns [(start, control, end)] quadratic corners for a closed polygon."""
    n = len(points)
    return [
        (toward(points[i], points[i - 1], dist), points[i], toward(points[i], points[(i + 1) % n], dist))
        for i in range(n)
    ]


def path_data(points, dist):
    segs = rounded_segments(points, dist)
    out = []
    for i, (start, ctrl, end) in enumerate(segs):
        out.append(("M" if i == 0 else "L") + f"{start[0]:.2f},{start[1]:.2f}")
        out.append(f"Q{ctrl[0]:.2f},{ctrl[1]:.2f} {end[0]:.2f},{end[1]:.2f}")
    return " ".join(out) + " Z"


def flatten(points, dist, scale):
    poly = []
    for start, ctrl, end in rounded_segments(points, dist):
        for step in range(17):
            t = step / 16.0
            x = (1 - t) ** 2 * start[0] + 2 * (1 - t) * t * ctrl[0] + t ** 2 * end[0]
            y = (1 - t) ** 2 * start[1] + 2 * (1 - t) * t * ctrl[1] + t ** 2 * end[1]
            poly.append((x * scale, y * scale))
    return poly


def render(size, path):
    big = size * 4
    scale = big / VIEW
    gradient = Image.new("RGB", (big, big))
    px = gradient.load()
    # Linear gradient from the triangle's upper-left (34,32) to bottom-right (80,73).
    x0, y0, x1, y1 = 34 * scale, 32 * scale, 80 * scale, 73 * scale
    dx, dy = x1 - x0, y1 - y0
    span = dx * dx + dy * dy
    for y in range(big):
        for x in range(big):
            t = min(1.0, max(0.0, ((x - x0) * dx + (y - y0) * dy) / span))
            px[x, y] = tuple(round(PURPLE[c] + (BLUE[c] - PURPLE[c]) * t) for c in range(3))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).polygon(flatten(TRI, CORNER, scale), fill=255)
    image = Image.new("RGB", (big, big), DARK)
    image.paste(gradient, (0, 0), mask)
    ImageDraw.Draw(image).polygon(flatten(ARROW, 1.5, scale), fill=(255, 255, 255))
    image.resize((size, size), Image.LANCZOS).save(path)


if __name__ == "__main__":
    print("triangle:", path_data(TRI, CORNER))
    print("arrow:   ", path_data(ARROW, 1.5))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logo-512.png")
    render(512, out)
    print("wrote", os.path.normpath(out))
