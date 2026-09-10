#!/usr/bin/env python3
"""Regenerate every raster brand asset from the reference artwork.

Source artwork
    ``brand/ic_logo_reference.png``  -- the neon-red YouTube mark on a dark
    background. Drop a replacement file with the same name into ``brand/``
    and re-run to re-skin the app.

Outputs (all densities, high resolution)
    ``res/mipmap-<dpi>/ic_launcher.png``            legacy squircle icon
    ``res/mipmap-<dpi>/ic_launcher_round.png``      legacy round icon
    ``res/mipmap-<dpi>/ic_launcher_background.png`` adaptive background layer
    ``res/mipmap-<dpi>/ic_launcher_foreground.png`` adaptive foreground layer
    ``res/drawable-<dpi>/ic_logo.png``              splash / settings logo

The adaptive layers (API 26+) are referenced from
``mipmap-anydpi-v26/ic_launcher*.xml``; devices below API 26 fall back to the
squircle/round PNGs.

Requires: Pillow, numpy.

Run from the repository root:

    python3 tools/generate_icons.py
"""

from __future__ import annotations

import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")
REFERENCE = os.path.join(ROOT, "brand", "ic_logo_reference.png")

# density bucket -> pixel sizes (Android launcher icon spec)
ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# adaptive icon layers are 108dp
ADAPTIVE_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# splash logo is displayed at 128dp
SPLASH_SIZES = {"mdpi": 120, "hdpi": 180, "xhdpi": 240, "xxhdpi": 360, "xxxhdpi": 480}

# luminance above this is considered artwork (the reference background is a
# near-black #0B0B0B, the neon mark is far brighter)
ART_THRESHOLD = 64
# supersampling factor for shape masks (anti-aliased edges)
SUPER = 4


# ---------------------------------------------------------------------------
# artwork extraction
# ---------------------------------------------------------------------------

def load_artwork() -> np.ndarray:
    img = Image.open(REFERENCE).convert("RGBA")
    arr = np.asarray(img, dtype=np.uint8)
    lum = arr[:, :, :3].max(axis=2)
    ys, xs = np.nonzero(lum >= ART_THRESHOLD)
    if len(xs) == 0 or len(ys) == 0:
        raise SystemExit("reference artwork has no bright pixels; check %s" % REFERENCE)
    cropped = arr[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return cropped


def soft_alpha(arr: np.ndarray) -> np.ndarray:
    """Replace alpha with a luminance ramp so the neon glow stays translucent
    while the mark body itself is opaque. Lets the logo sit on any dark
    surface without a hard rectangular halo."""
    lum = arr[:, :, :3].max(axis=2).astype(np.int32)
    return np.clip((lum - 20) * 5, 0, 255).astype(np.uint8)


def square_canvas(cropped: np.ndarray, size: int, pad_ratio: float = 0.04) -> np.ndarray:
    """Centre the cropped artwork on a transparent square canvas with a small
    glow margin, then scale to `size` px."""
    h, w = cropped.shape[:2]
    side = int(max(h, w) * (1.0 + 2 * pad_ratio))
    oy, ox = (side - h) // 2, (side - w) // 2
    out = np.zeros((side, side, 4), dtype=np.uint8)
    out[oy:oy + h, ox:ox + w, :3] = cropped[:, :, :3]
    out[oy:oy + h, ox:ox + w, 3] = soft_alpha(cropped)
    img = Image.fromarray(out, "RGBA").resize((size, size), Image.LANCZOS)
    return np.asarray(img, dtype=np.uint8)


def resize_art(cropped: np.ndarray, box: int) -> np.ndarray:
    """Scale the (non-square) artwork so it fits inside `box` px, soft alpha."""
    h, w = cropped.shape[:2]
    scale = box / float(max(h, w))
    nh, nw = int(round(h * scale)), int(round(w * scale))
    art = np.empty((nh, nw, 4), dtype=np.uint8)
    art[:, :, :3] = np.asarray(
        Image.fromarray(cropped[:, :, :3], "RGB").resize((nw, nh), Image.LANCZOS))
    art[:, :, 3] = np.asarray(
        Image.fromarray(soft_alpha(cropped), "L").resize((nw, nh), Image.LANCZOS))
    return art


# ---------------------------------------------------------------------------
# surfaces
# ---------------------------------------------------------------------------

def background(size: int) -> np.ndarray:
    """Radial neon-vignette used behind the mark (icons + adaptive layer)."""
    y, x = np.mgrid[0:size, 0:size]
    c = (size - 1) / 2.0
    d = np.clip(np.sqrt((x - c) ** 2 + (y - c) ** 2) / (size * 0.72), 0.0, 1.0)
    out = np.empty((size, size, 4), dtype=np.uint8)
    out[:, :, 0] = np.interp(d, [0.0, 0.55, 1.0], [58, 27, 13])
    out[:, :, 1] = np.interp(d, [0.0, 0.55, 1.0], [8, 4, 2])
    out[:, :, 2] = np.interp(d, [0.0, 0.55, 1.0], [10, 5, 3])
    out[:, :, 3] = 255
    return out


def shape_mask(size: int, shape: str) -> np.ndarray:
    """Anti-aliased alpha mask: rounded square or circle."""
    big = size * SUPER
    img = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(img)
    if shape == "round":
        draw.ellipse((0, 0, big - 1, big - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=255)
    img = img.resize((size, size), Image.LANCZOS)
    return np.asarray(img, dtype=np.uint8)


def paste_centered(base: np.ndarray, art: np.ndarray) -> None:
    bh, bw = base.shape[:2]
    ah, aw = art.shape[:2]
    oy, ox = (bh - ah) // 2, (bw - aw) // 2
    a = art[:, :, 3:4].astype(np.float32) / 255.0
    base_a = base[oy:oy + ah, ox:ox + aw, 3:4].astype(np.float32) / 255.0
    base[oy:oy + ah, ox:ox + aw, :3] = (
        art[:, :, :3].astype(np.float32) * a
        + base[oy:oy + ah, ox:ox + aw, :3].astype(np.float32) * (1 - a)
    ).astype(np.uint8)
    # keep the union of both alpha channels (matters for transparent layers)
    base[oy:oy + ah, ox:ox + aw, 3] = (np.maximum(a, base_a)[:, :, 0] * 255).astype(np.uint8)


def legacy_icon(cropped: np.ndarray, size: int, shape: str) -> np.ndarray:
    base = background(size)
    paste_centered(base, resize_art(cropped, int(size * 0.80)))
    base[:, :, 3] = shape_mask(size, shape)
    return base


def adaptive_foreground(cropped: np.ndarray, size: int) -> np.ndarray:
    """Transparent layer; the mark sits inside the 66dp safe zone."""
    canvas = np.zeros((size, size, 4), dtype=np.uint8)
    paste_centered(canvas, resize_art(cropped, int(size * 0.62)))
    return canvas


def splash_logo(cropped: np.ndarray, size: int) -> np.ndarray:
    return square_canvas(cropped, size)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def write_png(path: str, arr: np.ndarray) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(arr, "RGBA").save(path, "PNG", optimize=True)
    print("wrote %s (%dx%d)" % (os.path.relpath(path, ROOT), arr.shape[1], arr.shape[0]))


def main() -> None:
    cropped = load_artwork()
    print("cropped artwork: %dx%d from %s"
          % (cropped.shape[1], cropped.shape[0], os.path.relpath(REFERENCE, ROOT)))

    for density, size in ICON_SIZES.items():
        out_dir = os.path.join(RES_DIR, "mipmap-" + density)
        write_png(os.path.join(out_dir, "ic_launcher.png"), legacy_icon(cropped, size, "square"))
        write_png(os.path.join(out_dir, "ic_launcher_round.png"), legacy_icon(cropped, size, "round"))

    for density, size in ADAPTIVE_SIZES.items():
        out_dir = os.path.join(RES_DIR, "mipmap-" + density)
        write_png(os.path.join(out_dir, "ic_launcher_background.png"), background(size))
        write_png(os.path.join(out_dir, "ic_launcher_foreground.png"), adaptive_foreground(cropped, size))

    for density, size in SPLASH_SIZES.items():
        out_dir = os.path.join(RES_DIR, "drawable-" + density)
        write_png(os.path.join(out_dir, "ic_logo.png"), splash_logo(cropped, size))


if __name__ == "__main__":
    main()
