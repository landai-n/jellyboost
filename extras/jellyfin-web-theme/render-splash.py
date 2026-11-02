"""Renders jellyboost-splash.svg to jellyboost-splash.png (1920x1080).

The SVG is the design source, but its background is a faint glow spread over a dark frame: at 8 bits
a renderer quantises it into visible rings. So the background layers (#101010 ground, glow, vignette)
are recomputed here in float and dithered — the reason the app paints its glows with Paint.isDither —
and only the foreground (mark + wordmark) is taken from the SVG via rsvg-convert. Keep the stops below
in step with the SVG's <radialGradient>s.

Usage: python3 render-splash.py   (needs rsvg-convert, numpy, Pillow)
"""
import pathlib
import re
import subprocess
import tempfile

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
SVG = HERE / "jellyboost-splash.svg"
PNG = HERE / "jellyboost-splash.png"
W, H = 1920, 1080

GROUND = np.array([16, 16, 16], float) / 255
# (offset, rgb, alpha) — the SVG's #glow, centred (960, 470), r 760.
GLOW = [(0.0, "AA5CC3", 0.34), (0.3, "8A6FD0", 0.18), (0.6, "00A4DC", 0.07), (1.0, "00A4DC", 0.0)]
GLOW_CENTER, GLOW_R = (960, 470), 760
# The SVG's #vignette: black, 0 at 0.55 of r 1150 rising to 0.55 at the edge.
VIGNETTE_CENTER, VIGNETTE_R, VIGNETTE_START, VIGNETTE_MAX = (960, 540), 1150, 0.55, 0.55


def rgb(hex_):
    return np.array([int(hex_[i : i + 2], 16) for i in (0, 2, 4)], float) / 255


def radial_t(center, r):
    y, x = np.mgrid[0:H, 0:W].astype(float)
    return np.clip(np.hypot(x - center[0], y - center[1]) / r, 0, 1)


def background():
    out = np.broadcast_to(GROUND, (H, W, 3)).copy()
    t = radial_t(GLOW_CENTER, GLOW_R)
    offs = [s[0] for s in GLOW]
    alpha = np.interp(t, offs, [s[2] for s in GLOW])
    color = np.stack([np.interp(t, offs, [rgb(s[1])[c] for s in GLOW]) for c in range(3)], axis=-1)
    out = out * (1 - alpha[..., None]) + color * alpha[..., None]
    v = radial_t(VIGNETTE_CENTER, VIGNETTE_R)
    vig = np.clip((v - VIGNETTE_START) / (1 - VIGNETTE_START), 0, 1) * VIGNETTE_MAX
    out *= 1 - vig[..., None]
    # Triangular-PDF dither of +-1 LSB before quantising.
    rng = np.random.default_rng(7)
    noise = (rng.random((H, W, 3)) - rng.random((H, W, 3))) / 255
    return Image.fromarray(np.clip(np.round((out + noise) * 255), 0, 255).astype(np.uint8), "RGB")


def foreground():
    src = SVG.read_text(encoding="utf-8")
    src = re.sub(r'\s*<rect [^>]*/>', "", src)
    with tempfile.TemporaryDirectory() as tmp:
        fg_svg, fg_png = pathlib.Path(tmp, "fg.svg"), pathlib.Path(tmp, "fg.png")
        fg_svg.write_text(src, encoding="utf-8")
        subprocess.run(["rsvg-convert", "-w", str(W), "-h", str(H), str(fg_svg), "-o", str(fg_png)], check=True)
        return Image.open(fg_png).convert("RGBA")


if __name__ == "__main__":
    img = background().convert("RGBA")
    img.alpha_composite(foreground())
    img.convert("RGB").save(PNG, optimize=True)
    print(PNG)
