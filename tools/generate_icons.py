#!/usr/bin/env python3
"""Generates the toolbar icons (64x64 white glyphs, anti-aliased) used by IconButton.

Run from the repository root:  python3 tools/generate_icons.py [name ...]
With no names it rewrites every icon this script draws; with names (e.g. "relief") only those, so icons you
have replaced by hand (scale, regen, ...) are never touched.
Output: common/src/main/resources/assets/kirox_map/textures/gui/icons/<name>.png (+ .mcmeta for smooth scaling).
Edit the drawing functions below to restyle an icon, or just replace the PNG with your own 64x64 white-on-transparent file.
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "common/src/main/resources/assets/kirox_map/textures/gui/icons")
K = 8            # supersampling
N = 64           # final size
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


class Canvas:
    def __init__(self):
        self.img = Image.new("L", (N * K, N * K), 0)
        self.d = ImageDraw.Draw(self.img)

    def _p(self, pts):
        return [(x * K, y * K) for x, y in pts]

    def circle(self, cx, cy, r, v=255, width=0):
        box = [(cx - r) * K, (cy - r) * K, (cx + r) * K, (cy + r) * K]
        if width:
            self.d.ellipse(box, outline=v, width=int(width * K))
        else:
            self.d.ellipse(box, fill=v)

    def line(self, a, b, w, v=255):
        self.d.line(self._p([a, b]), fill=v, width=int(w * K))
        self.circle(a[0], a[1], w / 2, v)
        self.circle(b[0], b[1], w / 2, v)

    def rrect(self, x0, y0, x1, y1, r, v=255, width=0):
        box = [x0 * K, y0 * K, x1 * K, y1 * K]
        if width:
            self.d.rounded_rectangle(box, radius=r * K, outline=v, width=int(width * K))
        else:
            self.d.rounded_rectangle(box, radius=r * K, fill=v)

    def poly(self, pts, v=255):
        self.d.polygon(self._p(pts), fill=v)

    def smooth(self, pts, v=255, steps=14):
        out = []
        n = len(pts)
        for i in range(n):
            p0, p1, p2, p3 = pts[(i - 1) % n], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
            for s in range(steps):
                t = s / steps
                out.append(tuple(0.5 * ((2 * p1[j]) + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t * t
                                        + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t ** 3) for j in range(2)))
        self.poly(out, v)

    def arc(self, cx, cy, r, a0, a1, w, v=255):
        box = [(cx - r) * K, (cy - r) * K, (cx + r) * K, (cy + r) * K]
        self.d.arc(box, a0, a1, fill=v, width=int(w * K))
        for a in (a0, a1):
            self.circle(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)), w / 2, v)

    def text(self, s, cx, cy, size, v=255):
        f = ImageFont.truetype(FONT, int(size * K))
        self.d.text((cx * K, cy * K), s, font=f, fill=v, anchor="mm")

    def save(self, name):
        small = self.img.resize((N, N), Image.LANCZOS)
        rgba = Image.new("RGBA", (N, N), (255, 255, 255, 0))
        rgba.putalpha(small)
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, name + ".png")
        rgba.save(path)
        with open(path + ".mcmeta", "w") as f:
            f.write('{"texture": {"blur": true}}\n')


def overworld():
    c = Canvas()
    c.circle(32, 32, 11)
    for i in range(8):
        a = math.radians(i * 45)
        c.line((32 + 18 * math.cos(a), 32 + 18 * math.sin(a)), (32 + 26 * math.cos(a), 32 + 26 * math.sin(a)), 4.5)
    c.save("overworld")


def nether():
    c = Canvas()
    c.smooth([(32, 4), (42, 18), (50, 30), (50, 44), (42, 56), (32, 60), (22, 56), (14, 44), (14, 32), (22, 24), (26, 30), (28, 16)])
    c.smooth([(32, 32), (38, 42), (38, 50), (32, 54), (26, 50), (26, 42)], 0)
    c.save("nether")


def end():
    c = Canvas()
    c.circle(32, 32, 25, width=5.5)
    c.circle(32, 32, 10)
    c.save("end")


def grid():
    c = Canvas()
    c.rrect(8, 8, 56, 56, 6, width=5)
    for p in (24, 40):
        c.line((p, 10), (p, 54), 3.5)
        c.line((10, p), (54, p), 3.5)
    c.save("grid")


def slime():
    c = Canvas()
    c.rrect(8, 10, 56, 56, 12)
    c.rrect(18, 24, 27, 35, 2, 0)
    c.rrect(37, 24, 46, 35, 2, 0)
    c.rrect(26, 42, 38, 47, 2, 0)
    c.save("slime")


def labels():
    c = Canvas()
    c.poly([(6, 14), (40, 14), (58, 32), (40, 50), (6, 50)])
    c.circle(18, 32, 5, 0)
    c.save("labels")


def waypoint_labels():
    c = Canvas()
    c.circle(32, 17, 13)
    c.poly([(21, 25), (43, 25), (32, 40)])
    c.circle(32, 17, 5, 0)
    c.rrect(6, 44, 58, 60, 5)
    c.rrect(14, 50, 50, 54, 2, 0)
    c.save("waypoint_labels")


def waypoint_tags():
    # a dark name plate with two text lines (name, distance) and a small pointer
    c = Canvas()
    c.rrect(6, 8, 58, 44, 6)
    c.rrect(14, 16, 50, 23, 2, 0)
    c.rrect(20, 29, 44, 36, 2, 0)
    c.poly([(26, 44), (38, 44), (32, 56)])
    c.save("waypoint_tags")


def structures():
    c = Canvas()
    c.rrect(10, 28, 54, 58, 2)
    for x in (10, 27, 44):
        c.rrect(x, 14, x + 10, 30, 1.5)
    c.rrect(26, 40, 38, 58, 6, 0)
    c.poly([(26, 46), (38, 46), (38, 58), (26, 58)], 0)
    c.save("structures")


def biomes():
    c = Canvas()
    c.poly([(3, 54), (24, 18), (35, 36), (43, 26), (61, 54)])
    c.circle(48, 12, 6.5)
    c.save("biomes")


def waypoint():
    c = Canvas()
    c.circle(32, 24, 18)
    c.poly([(16.5, 32), (47.5, 32), (32, 60)])
    c.circle(32, 24, 7, 0)
    c.save("waypoint")


def help_():
    c = Canvas()
    c.circle(32, 32, 27)
    c.text("?", 32, 34, 40, 0)
    c.save("help")


def close():
    c = Canvas()
    c.line((14, 14), (50, 50), 8)
    c.line((50, 14), (14, 50), 8)
    c.save("close")


def seed():
    c = Canvas()
    c.line((32, 58), (32, 30), 4.5)
    c.smooth([(31, 34), (20, 20), (6, 18), (10, 32), (22, 38)])
    c.smooth([(33, 28), (44, 12), (58, 8), (56, 24), (44, 32)])
    c.save("seed")


def zoom(plus):
    c = Canvas()
    c.circle(27, 27, 20, width=6)
    c.line((42, 42), (57, 57), 8)
    c.line((18, 27), (36, 27), 5)
    if plus:
        c.line((27, 18), (27, 36), 5)
    c.save("zoom_in" if plus else "zoom_out")


def center():
    c = Canvas()
    c.circle(32, 32, 17, width=5.5)
    for a, b in (((32, 5), (32, 19)), ((32, 45), (32, 59)), ((5, 32), (19, 32)), ((45, 32), (59, 32))):
        c.line(a, b, 5)
    c.circle(32, 32, 4.5)
    c.save("center")


def copy():
    c = Canvas()
    c.rrect(24, 6, 57, 40, 6, width=5)
    c.rrect(5, 21, 43, 61, 8, 0)      # clear a halo around the front sheet
    c.rrect(9, 25, 39, 57, 5)
    c.save("copy")


def relief():
    c = Canvas()
    # a mountain with a lit face (translucent) and a shadowed face (solid), split along the ridge
    c.poly([(4, 56), (32, 8), (32, 56)])
    c.poly([(32, 8), (60, 56), (32, 56)], 130)
    c.line((32, 8), (32, 56), 2.5, 0)
    c.save("relief")


def reset():
    c = Canvas()
    c.arc(32, 32, 21, 35, 335, 6.5)
    th = math.radians(335)
    p = (32 + 21 * math.cos(th), 32 + 21 * math.sin(th))
    t = (-math.sin(th), math.cos(th))
    n = (math.cos(th), math.sin(th))
    c.poly([(p[0] + t[0] * 13, p[1] + t[1] * 13), (p[0] + n[0] * 12 - t[0] * 2, p[1] + n[1] * 12 - t[1] * 2),
            (p[0] - n[0] * 12 - t[0] * 2, p[1] - n[1] * 12 - t[1] * 2)])
    c.save("reset")


ICONS = {"overworld": overworld, "nether": nether, "end": end, "grid": grid, "slime": slime, "labels": labels,
         "waypoint_labels": waypoint_labels, "structures": structures, "biomes": biomes, "waypoint": waypoint,
         "help": help_, "close": close, "seed": seed, "zoom_in": lambda: zoom(True), "zoom_out": lambda: zoom(False),
         "center": center, "copy": copy, "reset": reset, "relief": relief, "waypoint_tags": waypoint_tags}

import sys
names = sys.argv[1:] or list(ICONS)
for name in names:
    ICONS[name]()
print("icons written to", os.path.abspath(OUT))
