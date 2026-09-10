#!/usr/bin/env python3
"""Regenerate the raster launcher icons for pre-adaptive-icon devices.

API 26+ picks up ``mipmap-anydpi-v26/ic_launcher.xml``; Android 5.0 - 8.0 needs
real bitmaps in the density buckets. This script writes them using only the
standard library (no Pillow), so it runs anywhere.

Run from the repository root:

    python3 tools/generate_icons.py
"""

from __future__ import annotations

import math
import os
import struct
import zlib

RES_DIR = os.path.join("app", "src", "main", "res")

# density -> icon size in px (matches the Android launcher icon spec)
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

RED = (255, 0, 0)
WHITE = (255, 255, 255)

# 4x4 supersampling gives smooth edges without an imaging library.
SUBSAMPLES = 4


def rounded_rect_sdf(x: float, y: float, half: float, radius: float) -> float:
    """Signed distance from (x, y) to a rounded square centred on the origin."""
    qx = abs(x) - (half - radius)
    qy = abs(y) - (half - radius)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    inside = min(max(qx, qy), 0.0)
    return outside + inside - radius


def circle_sdf(x: float, y: float, radius: float) -> float:
    """Signed distance from (x, y) to a circle centred on the origin."""
    return math.hypot(x, y) - radius


def inside_triangle(px: float, py: float, tri: tuple) -> bool:
    (x1, y1), (x2, y2), (x3, y3) = tri
    d1 = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2)
    d2 = (px - x3) * (y2 - y3) - (x2 - x3) * (py - y3)
    d3 = (px - x1) * (y3 - y1) - (x3 - x1) * (py - y1)
    return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))


def over(fg_rgb, fg_a: float, bg_rgb, bg_a: float):
    """Porter-Duff 'source over' for straight-alpha colours."""
    out_a = fg_a + bg_a * (1.0 - fg_a)
    if out_a <= 0.0:
        return (0, 0, 0), 0.0
    channels = []
    for i in range(3):
        value = (fg_rgb[i] * fg_a + bg_rgb[i] * bg_a * (1.0 - fg_a)) / out_a
        channels.append(int(round(value)))
    return tuple(channels), out_a


def render_icon(size: int, shape: str) -> bytes:
    """Render an RGBA image of the YT Pro mark at `size` px."""
    step = 1.0 / SUBSAMPLES
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            bg_hits = 0
            tri_hits = 0
            for sy in range(SUBSAMPLES):
                sample_y = py + (sy + 0.5) * step - size / 2.0
                for sx in range(SUBSAMPLES):
                    sample_x = px + (sx + 0.5) * step - size / 2.0
                    if shape == "round":
                        dist = circle_sdf(sample_x, sample_y, size / 2.0)
                    else:
                        dist = rounded_rect_sdf(sample_x, sample_y, size / 2.0, size * 0.21)
                    if dist < 0.0:
                        bg_hits += 1
                    triangle = (
                        (size * 0.40 - size / 2.0, size * 0.31 - size / 2.0),
                        (size * 0.68 - size / 2.0, 0.0),
                        (size * 0.40 - size / 2.0, size * 0.69 - size / 2.0),
                    )
                    if inside_triangle(sample_x, sample_y, triangle):
                        tri_hits += 1
            total = float(SUBSAMPLES * SUBSAMPLES)
            colour, alpha = over(RED, bg_hits / total, (0, 0, 0), 0.0)
            colour, alpha = over(WHITE, tri_hits / total, colour, alpha)
            row += bytes(colour) + bytes([int(round(alpha * 255))])
        rows.append(bytes(row))
    return rows_to_png(rows, size, size)


def rows_to_png(rows, width: int, height: int) -> bytes:
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES_DIR, "mipmap-" + density)
        os.makedirs(out_dir, exist_ok=True)
        for shape, name in (("square", "ic_launcher.png"), ("round", "ic_launcher_round.png")):
            path = os.path.join(out_dir, name)
            with open(path, "wb") as handle:
                handle.write(render_icon(size, shape))
            print("wrote %s (%dx%d)" % (path, size, size))


if __name__ == "__main__":
    main()
