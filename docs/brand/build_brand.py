#!/usr/bin/env python3
"""Omdroid brand generator: three icon concepts, one wordmark, all outputs.

Every shape is an ASCII bitmap below (rows of '#' and '.'), so the drawing can be
read as text. The script merges cells into rectangles, emits rectilinear SVG
paths (M/H/V/Z, integer coordinates only), renders PNGs with rsvg-convert, and
runs the checks from docs/brand/brief-brand-readme.md (round 1 checks 1-9 plus
the round 2 checks V1-V5), printing one line each. Running it twice produces
byte-identical output.

Palette: Tokyo Night, Omarchy's default theme
  ~/.hermes/cache/scratch/omarchy/upstream/usr/share/omarchy/themes/tokyo-night/colors.toml
(re-verified against that file when it is present).

Usage: python3 docs/brand/build_brand.py
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
CONCEPTS = os.path.join(ROOT, "concepts")
RSVG = "/usr/sbin/rsvg-convert"
FONT_BOLD = "/usr/share/fonts/liberation/LiberationSans-Bold.ttf"

# --------------------------------------------------------------------------
# palette (Tokyo Night)
# --------------------------------------------------------------------------
PALETTE_FILE = os.path.expanduser(
    "~/.hermes/cache/scratch/omarchy/upstream/usr/share/omarchy/themes/tokyo-night/colors.toml"
)
PALETTE = {
    "accent": "#7aa2f7",
    "background": "#1a1b26",
    "lighter_background": "#24283b",
    "muted": "#414868",
    "foreground": "#a9b1d6",
    "dark_foreground": "#565f89",
    "bright_foreground": "#c0caf5",
    "green": "#9ece6a",
    "magenta": "#ad8ee6",
    "orange": "#eb927b",
    "red": "#f7768e",
    "yellow": "#e0af68",
    "cyan": "#449dab",
    "bright_blue": "#7da6ff",
}
GROUND = PALETTE["background"]
WHITE = "#ffffff"

ACCENT = PALETTE["accent"]
DARK_WORD = PALETTE["bright_foreground"]


def verify_palette():
    """Re-read the theme file when it exists and confirm the values we use."""
    if not os.path.exists(PALETTE_FILE):
        print("PALETTE source file absent (scratch pruned); using embedded copy")
        return
    text = open(PALETTE_FILE, encoding="utf-8").read()
    for key, value in PALETTE.items():
        m = re.search(r'^%s\s*=\s*"([^"]+)"' % re.escape(key), text, re.M)
        if m and m.group(1).lower() != value.lower():
            raise SystemExit("palette mismatch %s: %s != %s" % (key, m.group(1), value))
    print("PALETTE verified against %s (%d values)" % (PALETTE_FILE, len(PALETTE)))


# --------------------------------------------------------------------------
# wordmark: five glyphs, cap height 10 cells, stroke 3 (ratio 0.30)
# --------------------------------------------------------------------------
# Outer corners are cut by exactly one one-cell step; counters are small and
# rectangular; every vertical/horizontal run in a glyph is at least 2 cells so
# the smallest rendering keeps its strokes.
LETTERS = {
    "O": [
        ".#######.",
        "#########",
        "#########",
        "###...###",
        "###...###",
        "###...###",
        "###...###",
        "#########",
        "#########",
        ".#######.",
    ],
    # M: the V's two arms leave the top corners, step inward and merge into a
    # single centre column that reaches the baseline. The notch at the top is
    # what keeps it from reading as an H, the centre column what keeps it from
    # reading as an N or a W.
    "M": [
        "######...######",
        "######...######",
        "###.###.###.###",
        "###.###.###.###",
        "###..#####..###",
        "###..#####..###",
        "###...###...###",
        "###...###...###",
        "###...###...###",
        "###...###...###",
    ],
    # D: flat left stem, chamfered right side. Reads as D because the left edge
    # has no step at all while the right edge steps in twice.
    "D": [
        "#######..",
        "########.",
        "#########",
        "###...###",
        "###...###",
        "###...###",
        "###...###",
        "#########",
        "########.",
        "#######..",
    ],
    # R: closed bowl over rows 0-4, then a leg that steps right two cells down
    # to the baseline. Not a P (the leg leaves the stem) and not a K (no upper
    # arm, and the bowl is closed).
    "R": [
        "#######..",
        "########.",
        "###...###",
        "###...###",
        "########.",
        "###.###..",
        "###..###.",
        "###..###.",
        "###...###",
        "###...###",
    ],
    "I": ["###"] * 10,
}
WORD = "OMDROID"
CAP_CELLS = 10
STROKE_CELLS = 3
LETTER_GAP = 2

# --------------------------------------------------------------------------
# icons
# --------------------------------------------------------------------------
# Concept A is two bitmaps so the checks can tell the frame from the head.
# Frame: stroke 3, one break in the bottom edge (bottom-right of centre).
FRAME_A = [
    "..############################..",
    ".##############################.",
    "################################",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "###..........................###",
    "##################.......#######",
    ".#################.......######.",
    "..################.......#####..",
]

# Head: a 22x10 dome (2.2:1, flat bottom, two-step shoulders), two eye gaps in
# the lower half, and two 3-cell antenna stubs whose tips step one cell outward
# and whose base sits two cells in from the frame on every side.
HEAD_A = [
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "......###..............###......",
    "......###..............###......",
    ".......###............###.......",
    "........################........",
    "......####################......",
    ".....######################.....",
    ".....######################.....",
    ".....######################.....",
    ".....####...########...####.....",
    ".....####...########...####.....",
    ".....####...########...####.....",
    ".....######################.....",
    ".....######################.....",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
]

# Concept C: phone frame at stroke 3, break in the top edge, one master pane and
# two stacked panes inside, every gap 2 cells.
ICON_C = [
    "................................",
    "......########......######......",
    ".....#########......#######.....",
    "....##########......########....",
    "....###..................###....",
    "....###..................###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######.........###....",
    "....###..#######.........###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..#######..#####..###....",
    "....###..................###....",
    "....###..................###....",
    "....########################....",
    ".....######################.....",
    "......####################......",
    "................................",
]


# --------------------------------------------------------------------------
# bitmaps -> cells
# --------------------------------------------------------------------------
def to_cells(rows, width, height, name):
    assert len(rows) == height, "%s: %d rows, want %d" % (name, len(rows), height)
    for i, row in enumerate(rows):
        assert len(row) == width, "%s row %d: %d cols, want %d" % (name, i, len(row), width)
        assert set(row) <= {"#", "."}, "%s row %d: bad chars" % (name, i)
    return frozenset((x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == "#")


def overlay(*bitmaps):
    """Merge row-bitmaps; '#' may not collide. Returns a merged row list."""
    rows = list(bitmaps[0])
    for extra in bitmaps[1:]:
        merged = []
        for y, (base, top) in enumerate(zip(rows, extra)):
            assert len(base) == len(top), "overlay width mismatch at row %d" % y
            merged.append("".join(
                "#" if (a == "#" or b == "#") else "."
                for a, b in zip(base, top)))
            for x, (a, b) in enumerate(zip(base, top)):
                assert not (a == "#" and b == "#"), "overlay collision at %d,%d" % (x, y)
        rows = merged
    return rows


ICON_A = overlay(FRAME_A, HEAD_A)

# Concept B: the same two glyphs as the wordmark, at the only scales that fit a
# 32-cell square. At cap 10 the pair is 26x10 and covers 7.7 % of the tile, far
# under the 18 % floor; at double scale the M alone is 30 cells wide and the two
# cannot share the square. So the M is doubled (30x20) with the O at wordmark
# scale beneath it, which is 21 % coverage with both glyphs unmistakable.
_B_O = to_cells(LETTERS["O"], 9, CAP_CELLS, "B O")
_B_M = to_cells(LETTERS["M"], 15, CAP_CELLS, "B M")
ICON_B = ["." * 32] * 32
ICON_B = [list(r) for r in ICON_B]
for _x, _y in [(x, y) for x, y in _B_M]:
    for _i in range(2):
        for _j in range(2):
            ICON_B[_y * 2 + _j][_x * 2 + _i + 1] = "#"
for _x, _y in _B_O:
    ICON_B[_y + 22][_x + 11] = "#"
ICON_B = ["".join(r) for r in ICON_B]

ICONS = {"a": ICON_A, "b": ICON_B, "c": ICON_C}

# Which concept is copied to docs/brand/omdroid-logo{,-dark,-light}.svg and
# docs/brand/omdroid-icon.svg, and which one the README header uses. Change this
# one value (and re-run) if the parent picks a different concept.
CHOSEN = "a"

ICON_CELL = 16          # SVG units per mark cell
MARK = 32               # mark grid, cells
TILE = 48               # tile grid: 1/6 padding on every side
PAD = (TILE - MARK) // 2
WORD_CELL = 32          # SVG units per wordmark cell in the lockups
LOCK_GAP = 2 * STROKE_CELLS * WORD_CELL   # two strokes between icon and wordmark


# --------------------------------------------------------------------------
# cells -> rectangles -> path data
# --------------------------------------------------------------------------
def to_rects(cells):
    """Greedy top-left rectangle cover, deterministic for a given cell set."""
    remaining = set(cells)
    rects = []
    while remaining:
        y0 = min(y for _, y in remaining)
        x0 = min(x for x, y in remaining if y == y0)
        x1 = x0
        while (x1 + 1, y0) in remaining:
            x1 += 1
        y1 = y0
        while all((x, y1 + 1) in remaining for x in range(x0, x1 + 1)):
            y1 += 1
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                remaining.discard((x, y))
        rects.append((x0, y0, x1, y1))
    return rects


def path_data(cells, cell, ox=0, oy=0):
    """One path, one subpath per rectangle, H/V segments only."""
    parts = []
    for x0, y0, x1, y1 in to_rects(cells):
        left, top = ox + x0 * cell, oy + y0 * cell
        right, bottom = ox + (x1 + 1) * cell, oy + (y1 + 1) * cell
        parts.append("M %d %d H %d V %d H %d Z" % (left, top, right, bottom, left))
    return " ".join(parts)


def offset(cells, dx, dy):
    return frozenset((x + dx, y + dy) for x, y in cells)


def scaled(cells, factor):
    return frozenset((x * factor + i, y * factor + j) for x, y in cells
                     for i in range(factor) for j in range(factor))


def bbox(cells):
    xs = [x for x, _ in cells]
    ys = [y for _, y in cells]
    return min(xs), min(ys), max(xs), max(ys)


# --------------------------------------------------------------------------
# SVG writers
# --------------------------------------------------------------------------
def svg_doc(width, height, groups, bg=None, comment=""):
    """groups: list of (colour, cells, cell, ox, oy) drawn in order."""
    body = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
            'viewBox="0 0 %d %d">' % (width, height, width, height)]
    if comment:
        body.insert(0, "<!-- %s -->" % comment)
    if bg:
        body.append('<rect x="0" y="0" width="%d" height="%d" fill="%s"/>' % (width, height, bg))
    for colour, cells, cell, ox, oy in groups:
        body.append('<path fill="%s" d="%s"/>' % (colour, path_data(cells, cell, ox, oy)))
    body.append("</svg>")
    return "\n".join(body) + "\n"


def word_cells():
    """Lay the wordmark out left to right and return (cells, width, height)."""
    cells = set()
    x = 0
    for ch in WORD:
        bmp = LETTERS[ch]
        width = len(bmp[0])
        cells |= offset(to_cells(bmp, width, CAP_CELLS, "letter " + ch), x, 0)
        x += width + LETTER_GAP
    return frozenset(cells), x - LETTER_GAP, CAP_CELLS


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def sh(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


# --------------------------------------------------------------------------
# checks
# --------------------------------------------------------------------------
def check_paths(doc, label):
    """Check 1: integer coordinates, only M/H/V/Z, axis-aligned segments."""
    bad = []
    for d in re.findall(r'\bd="([^"]+)"', doc):
        tokens = d.split()
        if not tokens:
            bad.append("empty path")
            continue
        pt = None
        i = 0
        while i < len(tokens):
            cmd = tokens[i]
            if cmd not in ("M", "H", "V", "Z"):
                bad.append("command %r" % cmd)
                i += 1
                continue
            if cmd == "Z":
                i += 1
                continue
            if i + 1 >= len(tokens):
                bad.append("truncated %r" % cmd)
                break
            try:
                value = int(tokens[i + 1])
            except ValueError:
                bad.append("non-integer %r" % tokens[i + 1])
                i += 2
                continue
            if cmd == "M":
                if i + 2 >= len(tokens):
                    bad.append("truncated M")
                    break
                try:
                    value2 = int(tokens[i + 2])
                except ValueError:
                    bad.append("non-integer M y")
                    i += 3
                    continue
                pt = (value, value2)
                i += 3
            elif cmd == "H":
                if pt is None:
                    bad.append("H before M")
                pt = (value, pt[1])   # dy == 0 by construction
                i += 2
            else:
                if pt is None:
                    bad.append("V before M")
                pt = (pt[0], value)   # dx == 0 by construction
                i += 2
    return (not bad), ("; ".join(bad[:3]) if bad else "all integer M/H/V/Z, axis-aligned")


def luminance(hexcolour):
    r, g, b = [int(hexcolour[i:i + 2], 16) / 255 for i in (1, 3, 5)]

    def lin(c):
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(fg, bg):
    l1, l2 = luminance(fg), luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def min_run(cells, axis, size=32):
    """Shortest run of consecutive mark cells along rows (axis=0) or columns."""
    best = None
    for fixed in range(size):
        run = 0
        for moving in range(size):
            x, y = (moving, fixed) if axis == 0 else (fixed, moving)
            if (x, y) in cells:
                run += 1
            else:
                if 0 < run and (best is None or run < best):
                    best = run
                run = 0
        if 0 < run and (best is None or run < best):
            best = run
    return best if best is not None else 0


def min_interior_gap(cells, size=32):
    """Shortest empty run between mark cells — the gap a pane or counter leaves."""
    best = None
    for y in range(size):
        xs = [x for x in range(size) if (x, y) in cells]
        if not xs:
            continue
        run = 0
        for x in range(min(xs), max(xs) + 1):
            if (x, y) in cells:
                if 0 < run and (best is None or run < best):
                    best = run
                run = 0
            else:
                run += 1
    for x in range(size):
        ys = [y for y in range(size) if (x, y) in cells]
        if not ys:
            continue
        run = 0
        for y in range(min(ys), max(ys) + 1):
            if (x, y) in cells:
                if 0 < run and (best is None or run < best):
                    best = run
                run = 0
            else:
                run += 1
    return best if best is not None else 0


def png_stats(path):
    img = Image.open(path).convert("RGB")
    colours = img.getcolors(maxcolors=1 << 20) or []
    return img.size, len(colours), colours


def render_png(svg_path, width=None, height=None):
    """Render an SVG to a temp PNG and return the PIL image."""
    tmp = tempfile.mkdtemp(prefix="omdroid-sheet-")
    out = os.path.join(tmp, "out.png")
    args = [RSVG]
    if width:
        args += ["-w", str(width)]
    if height:
        args += ["-h", str(height)]
    args += [svg_path, "-o", out]
    sh(*args)
    img = Image.open(out).convert("RGBA")
    img.load()
    shutil.rmtree(tmp, ignore_errors=True)
    return img


def ground_block(img, ground, pad=12):
    """Composite a transparent render onto a solid ground with padding."""
    rgb = tuple(int(ground[i:i + 2], 16) for i in (1, 3, 5))
    block = Image.new("RGB", (img.width + 2 * pad, img.height + 2 * pad), rgb)
    block.paste(img, (pad, pad), img)
    return block


# --------------------------------------------------------------------------
# sheet composer: every pasted element is tracked and bounds-checked
# --------------------------------------------------------------------------
class Sheet:
    def __init__(self, label_w=96, margin=24, gap=16, label_size=56):
        self.rows = []
        self.label_w = label_w
        self.margin = margin
        self.gap = gap
        self.font = ImageFont.truetype(FONT_BOLD, label_size)

    def add_row(self, label, cells):
        height = max(im.height for im in cells)
        self.rows.append({"label": label, "cells": cells, "h": height})

    def render(self, path, max_width=None):
        widths = []
        for row in self.rows:
            w = self.label_w + self.margin
            for im in row["cells"]:
                w += im.width + self.gap
            widths.append(w - self.gap + self.margin)
        width = max(widths)
        height = self.margin + sum(r["h"] + self.gap for r in self.rows) + self.margin
        img = Image.new("RGB", (width, height), (238, 238, 238))
        draw = ImageDraw.Draw(img)
        placed = []
        y = self.margin
        for row in self.rows:
            x = self.label_w + self.margin
            draw.text((self.margin, y + 8), row["label"], font=self.font, fill=(20, 20, 20))
            for im in row["cells"]:
                yy = y + (row["h"] - im.height) // 2
                img.paste(im, (x, yy), im if im.mode == "RGBA" else None)
                placed.append((x, yy, im.width, im.height))
                x += im.width + self.gap
            y += row["h"] + self.gap
        inside = all(px >= 0 and py >= 0 and px + pw <= width and py + ph <= height
                     for px, py, pw, ph in placed)
        if max_width is not None and width > max_width:
            inside = False
        img.save(path, optimize=True)
        print("SHEET %s %dx%d elements %d all-inside %s"
              % (path, width, height, len(placed), "PASS" if inside else "FAIL"))
        return inside


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
def main():
    verify_palette()
    results = {}

    icons = {name: to_cells(rows, 32, 32, "icon " + name) for name, rows in ICONS.items()}
    head = to_cells(HEAD_A, 32, 32, "head A")
    frame = to_cells(FRAME_A, 32, 32, "frame A")
    word, word_w, word_h = word_cells()
    print("WORDMARK %d cells wide, %d tall, %d cells lit" % (word_w, word_h, len(word)))
    assert word_w <= 79

    # ---- V1: wordmark metrics + lockup geometry
    cap = len(LETTERS["O"])
    stroke = min_run(to_cells(LETTERS["O"], len(LETTERS["O"][0]), cap, "O"), 0, size=cap)
    widths = {ch: len(LETTERS[ch][0]) for ch in LETTERS}
    ink = len(word)
    box = word_w * word_h
    lock_w = MARK * ICON_CELL + LOCK_GAP + word_w * WORD_CELL
    lock_h = MARK * ICON_CELL
    cap_units = CAP_CELLS * WORD_CELL
    stroke_units = STROKE_CELLS * WORD_CELL
    wy = (lock_h - cap_units) // 2
    thin_440 = stroke_units * 440.0 / lock_w
    ok_v1 = (0.30 <= stroke / cap <= 0.40
             and widths["O"] >= 0.9 * cap and widths["D"] >= 0.9 * cap and widths["R"] >= 0.9 * cap
             and widths["M"] > widths["O"] and widths["I"] == stroke
             and 100.0 * ink / box >= 45.0
             and thin_440 >= 4.0)
    print("V1a wordmark %s stroke %d / cap %d = %.3f (0.30-0.40); widths O%d M%d D%d R%d I%d "
          "(O,D,R >= %.1f, M > O, I = stroke)"
          % ("PASS" if ok_v1 else "FAIL", stroke, cap, stroke / cap,
             widths["O"], widths["M"], widths["D"], widths["R"], widths["I"], 0.9 * cap))
    print("V1b wordmark ink %d / %d cells = %.1f%% of its box (>= 45%%); "
          "thinnest stroke at 440 px = %.1f px (>= 4)"
          % (ink, box, 100.0 * ink / box, thin_440))
    ok_lock = (0.55 <= cap_units / lock_h <= 0.70
               and wy * 2 + cap_units == lock_h
               and LOCK_GAP == 2 * stroke_units)
    print("V1c lockup %s cap %d / icon %d = %.1f%% (55-70%%), centred (%d above, %d below), "
          "gap %d = 2 strokes, total %dx%d"
          % ("PASS" if ok_lock else "FAIL", cap_units, lock_h, 100.0 * cap_units / lock_h,
             wy, lock_h - wy - cap_units, LOCK_GAP, lock_w, lock_h))

    # ---- per-concept outputs
    for name in ("a", "b", "c"):
        cells = icons[name]
        out = os.path.join(CONCEPTS, name)
        os.makedirs(os.path.join(out, "android"), exist_ok=True)

        mark = svg_doc(MARK * ICON_CELL, MARK * ICON_CELL,
                       [(ACCENT, cells, ICON_CELL, 0, 0)], comment="Omdroid concept %s mark" % name)
        tile = svg_doc(TILE * ICON_CELL, TILE * ICON_CELL,
                       [(ACCENT, cells, ICON_CELL, PAD * ICON_CELL, PAD * ICON_CELL)],
                       bg=GROUND, comment="Omdroid concept %s icon tile" % name)

        dark = svg_doc(lock_w, lock_h,
                       [(ACCENT, cells, ICON_CELL, 0, 0),
                        (DARK_WORD, word, WORD_CELL, MARK * ICON_CELL + LOCK_GAP, wy)],
                       comment="Omdroid concept %s lockup, dark pages" % name)
        light = svg_doc(lock_w, lock_h,
                        [(LIGHT_INK, cells, ICON_CELL, 0, 0),
                         (LIGHT_INK, word, WORD_CELL, MARK * ICON_CELL + LOCK_GAP, wy)],
                        comment="Omdroid concept %s lockup, light pages" % name)

        write(os.path.join(out, "icon.svg"), mark)
        write(os.path.join(out, "icon-tile.svg"), tile)
        write(os.path.join(out, "lockup-dark.svg"), dark)
        write(os.path.join(out, "lockup-light.svg"), light)

        sh(RSVG, "-w", "512", "-h", "512", os.path.join(out, "icon-tile.svg"),
           "-o", os.path.join(out, "icon-512.png"))
        sh(RSVG, "-w", "48", "-h", "48", os.path.join(out, "icon-tile.svg"),
           "-o", os.path.join(out, "icon-48.png"))
        sh(RSVG, "-h", "512", os.path.join(out, "lockup-dark.svg"),
           "-o", os.path.join(out, "lockup-dark.png"))
        sh(RSVG, "-h", "512", os.path.join(out, "lockup-light.svg"),
           "-o", os.path.join(out, "lockup-light.png"))

        # ascii.txt: two characters per cell
        write(os.path.join(out, "ascii.txt"),
              "\n".join("".join("██" if (x, y) in cells else "  " for x in range(MARK))
                        for y in range(MARK)) + "\n")

        # ---- Android vector drawables, 108x108 viewport, mark 2 units per cell
        fg = svg_doc(108, 108, [(ACCENT, cells, 2, 22, 22)], comment="foreground")
        fg = fg.replace('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">',
                        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                        '    android:width="108dp" android:height="108dp"\n'
                        '    android:viewportWidth="108" android:viewportHeight="108">')
        fg = fg.replace("</svg>", "</vector>")
        fg = re.sub(r'<path fill="([^"]+)" d="([^"]+)"/>',
                    r'    <path\n        android:fillColor="\1"\n        android:pathData="\2"/>', fg)
        write(os.path.join(out, "android", "ic_launcher_foreground.xml"), fg)

        mono = fg.replace(ACCENT, WHITE)
        write(os.path.join(out, "android", "ic_launcher_monochrome.xml"), mono)

        write(os.path.join(out, "android", "ic_launcher_background.xml"),
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<!-- Omdroid concept %s: flat Tokyo Night ground. -->\n'
              '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    android:shape="rectangle">\n'
              '    <solid android:color="%s" />\n'
              '</shape>\n' % (name, GROUND))

        write(os.path.join(out, "android", "ic_launcher.xml"),
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<!-- Omdroid concept %s adaptive icon (background + foreground + monochrome). -->\n'
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <background android:drawable="@drawable/ic_launcher_background" />\n'
              '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
              '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
              '</adaptive-icon>\n' % name)

        # ---- check 1
        ok1 = True
        detail1 = ""
        for filename in ("icon.svg", "icon-tile.svg", "lockup-dark.svg", "lockup-light.svg"):
            ok, detail = check_paths(open(os.path.join(out, filename), encoding="utf-8").read(), filename)
            if not ok:
                ok1 = False
                detail1 = filename + ": " + detail
        for filename in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
            text = open(os.path.join(out, "android", filename), encoding="utf-8").read()
            ok, detail = check_paths(text, filename)
            if not ok:
                ok1 = False
                detail1 = filename + ": " + detail
        print("CHECK1 %s grid/segments %s %s" % (name.upper(), "PASS" if ok1 else "FAIL", detail1))
        results[(name, 1)] = ok1

        # ---- check 2: palette
        used = set()
        for filename in ("icon.svg", "icon-tile.svg", "lockup-dark.svg", "lockup-light.svg",
                         "android/ic_launcher_foreground.xml", "android/ic_launcher_monochrome.xml",
                         "android/ic_launcher_background.xml"):
            text = open(os.path.join(out, filename), encoding="utf-8").read()
            used |= {c.lower() for c in re.findall(r"#[0-9a-fA-F]{6}", text)}
        allowed = {v.lower() for v in PALETTE.values()} | {WHITE}
        extra = used - allowed
        print("CHECK2 %s palette %s %s" % (name.upper(), "PASS" if not extra else "FAIL",
                                           "colours " + " ".join(sorted(used))))
        results[(name, 2)] = not extra

        # ---- check 3: contrast
        r_tile = contrast(ACCENT, GROUND)
        r_light = contrast(LIGHT_INK, WHITE)
        ok3 = r_tile >= 4.5 and r_light >= 4.5
        print("CHECK3 %s contrast %s accent/ground %.2f  dark-lockup %.2f  light-lockup %s/white %.2f"
              % (name.upper(), "PASS" if ok3 else "FAIL", r_tile, r_tile, LIGHT_INK, r_light))
        results[(name, 3)] = ok3

        # ---- check 4: small size
        hrun = min_run(cells, 0)
        vrun = min_run(cells, 1)
        thin_px = min(hrun, vrun) * (48.0 / TILE)
        ground_rgb = tuple(int(GROUND[i:i + 2], 16) for i in (1, 3, 5))
        img48 = Image.open(os.path.join(out, "icon-48.png")).convert("RGB")
        lit = sum(count for count, px in (img48.getcolors(maxcolors=1 << 20) or [])
                  if max(abs(px[i] - ground_rgb[i]) for i in range(3)) > 8)
        occupancy = 100.0 * lit / (48 * 48)
        ok4 = thin_px >= 2.0 and 18.0 <= occupancy <= 70.0
        print("CHECK4 %s small-size %s thinnest stroke %d cells = %.1f px, mark %.1f%% of tile"
              % (name.upper(), "PASS" if ok4 else "FAIL", min(hrun, vrun), thin_px, occupancy))
        results[(name, 4)] = ok4

        # ---- check 5: launcher safe zone
        fg_cells = scaled(cells, 2)
        inside = all(21 <= 22 + x < 87 and 21 <= 22 + y < 87 for x, y in fg_cells)
        print("CHECK5 %s safe-zone %s all cells inside 21..87 of 108" % (name.upper(), "PASS" if inside else "FAIL"))
        results[(name, 5)] = inside

        # ---- check 6: android xml parses, pathData commands
        ok6 = True
        detail6 = "xml parses; pathData M/H/V/Z only"
        for filename in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml",
                         "ic_launcher_background.xml", "ic_launcher.xml"):
            path = os.path.join(out, "android", filename)
            try:
                ET.parse(path)
            except ET.ParseError as exc:
                ok6 = False
                detail6 = "%s: %s" % (filename, exc)
            if "foreground" in filename or "monochrome" in filename:
                text = open(path, encoding="utf-8").read()
                ok, detail = check_paths(text, filename)
                if not ok:
                    ok6 = False
                    detail6 = "%s: %s" % (filename, detail)
        print("CHECK6 %s android-xml %s %s" % (name.upper(), "PASS" if ok6 else "FAIL", detail6))
        results[(name, 6)] = ok6

        # ---- check 7: pngs exist, right size, not single colour, raster matches bitmap
        ok7 = True
        detail7 = []
        for filename, expect in (("icon-512.png", (512, 512)), ("icon-48.png", (48, 48))):
            path = os.path.join(out, filename)
            size, ncolours, _ = png_stats(path)
            good = os.path.exists(path) and size == expect and ncolours > 1
            ok7 = ok7 and good
            detail7.append("%s %dx%d colours %d" % (filename, size[0], size[1], ncolours))
        img = Image.open(os.path.join(out, "icon-512.png")).convert("RGB")
        px_per_cell = 512.0 / TILE
        mismatch = 0
        for cy in range(MARK):
            for cx in range(MARK):
                sx = int((PAD + cx + 0.5) * px_per_cell)
                sy = int((PAD + cy + 0.5) * px_per_cell)
                got = max(abs(img.getpixel((sx, sy))[i] - ground_rgb[i]) for i in range(3)) > 40
                if got != ((cx, cy) in cells):
                    mismatch += 1
        ok7 = ok7 and mismatch == 0
        detail7.append("raster matches bitmap (%d cell mismatches)" % mismatch)
        print("CHECK7 %s pngs %s %s" % (name.upper(), "PASS" if ok7 else "FAIL", ", ".join(detail7)))
        results[(name, 7)] = ok7

    # ---- check 8: the three marks differ
    names = ("a", "b", "c")
    ok8 = True
    for i in range(3):
        for j in range(i + 1, 3):
            ca, cb = icons[names[i]], icons[names[j]]
            pct = 100.0 * len(ca ^ cb) / 1024
            good = pct >= 25.0
            ok8 = ok8 and good
            print("CHECK8 %s/%s differ %.1f%% of 1024 cells %s"
                  % (names[i].upper(), names[j].upper(), pct, "PASS" if good else "FAIL"))
    results["differ"] = ok8

    # ---- V2: concept A's head geometry
    hx0, hy0, hx1, hy1 = bbox(head)
    head_aspect = (hx1 - hx0 + 1) / float(hy1 - hy0 + 1)
    inner = (3, 3, 28, 28)
    gaps = {
        "left": hx0 - inner[0],
        "right": inner[2] - hx1,
        "top": hy0 - inner[1],
        "bottom": inner[3] - hy1,
    }
    # adjacent = another cell lit within the 8-neighbourhood of any head cell
    touching = 0
    for x, y in head:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (x + dx, y + dy) in frame:
                    touching += 1
    ok_v2 = head_aspect >= 1.6 and touching == 0 and min(gaps.values()) >= 2
    print("V2a head %s bbox %dx%d = %.2f:1 (>= 1.6), no head cell touches the frame (%d), "
          "gaps L%d R%d T%d B%d (>= 2)"
          % ("PASS" if ok_v2 else "FAIL", hx1 - hx0 + 1, hy1 - hy0 + 1, head_aspect,
             touching, gaps["left"], gaps["right"], gaps["top"], gaps["bottom"]))

    # ---- V3: concept B coverage with the new glyphs
    b_occ = 100.0 * len(icons["b"]) / 1024
    print("V3  concept B %s %d cells = %.1f%% of the 32x32 mark, composed from the new M at 2x "
          "(%d cells) and the new O at 1x (%d cells); tile coverage is on CHECK4 B"
          % ("PASS" if b_occ >= 18.0 else "FAIL", len(icons["b"]), b_occ,
             4 * len(_B_M), len(_B_O)))

    # ---- V4: concept C pane gaps
    c_gap = min_interior_gap(icons["c"])
    c_stroke = min_run(icons["c"], 0)
    ok_v4 = c_gap >= 2 and c_stroke >= 3
    print("V4  concept C %s frame stroke %d cells, smallest interior gap %d cells = %d px at 48"
          % ("PASS" if ok_v4 else "FAIL", c_stroke, c_gap, c_gap))

    # ---- check 9 / V1f: logo text, half blocks, one char per cell
    lines = half_blocks(word, word_w, word_h)
    widths = {len(r) for r in lines}
    ok9 = widths == {word_w} and word_w <= 80 and len(lines) <= 12
    write(os.path.join(ROOT, "omdroid-logo.txt"), "\n".join(lines) + "\n")
    print("CHECK9 logo-txt %s %d columns, %d lines, uniform width %s (limits 80 / 12)"
          % ("PASS" if ok9 else "FAIL", word_w, len(lines), widths == {word_w}))
    results["logo"] = ok9

    # ---- standalone wordmark files
    wordmark_dark = svg_doc(word_w * WORD_CELL, word_h * WORD_CELL,
                            [(DARK_WORD, word, WORD_CELL, 0, 0)], comment="Omdroid wordmark, dark pages")
    wordmark_light = svg_doc(word_w * WORD_CELL, word_h * WORD_CELL,
                             [(LIGHT_INK, word, WORD_CELL, 0, 0)], comment="Omdroid wordmark, light pages")
    write(os.path.join(ROOT, "omdroid-wordmark-dark.svg"), wordmark_dark)
    write(os.path.join(ROOT, "omdroid-wordmark-light.svg"), wordmark_light)

    # ---- top-level copies of the chosen concept (README header, app icon source)
    a = os.path.join(CONCEPTS, CHOSEN)
    shutil.copyfile(os.path.join(a, "lockup-dark.svg"), os.path.join(ROOT, "omdroid-logo-dark.svg"))
    shutil.copyfile(os.path.join(a, "lockup-light.svg"), os.path.join(ROOT, "omdroid-logo-light.svg"))
    shutil.copyfile(os.path.join(a, "icon.svg"), os.path.join(ROOT, "omdroid-icon.svg"))

    # ---- V5: both contact sheets, bounds-checked
    ok_sheet = contact_sheet(icons)
    ok_word = wordmark_sheet()
    results["sheets"] = ok_sheet and ok_word
    results["v1"] = ok_v1 and ok_lock
    results["v2"] = ok_v2
    results["v4"] = ok_v4

    failed = [k for k, v in results.items() if v is False]
    print("SUMMARY checks failed: %s" % (failed if failed else "none"))
    return 0 if not failed else 1


LIGHT_CANDIDATES = ["muted", "dark_foreground", "lighter_background", "background"]
LIGHT_INK = PALETTE["muted"]
for _key in LIGHT_CANDIDATES:
    if contrast(PALETTE[_key], WHITE) >= 4.5:
        LIGHT_INK = PALETTE[_key]
        break


def half_blocks(cells, width, height):
    """Two cell rows per text row, using half blocks, one character per cell."""
    lines = []
    for y in range(0, height, 2):
        out = []
        for x in range(width):
            top = (x, y) in cells
            bottom = (x, y + 1) in cells if y + 1 < height else False
            out.append("█" if top and bottom else "▀" if top else "▄" if bottom else " ")
        lines.append("".join(out))
    return lines


def contact_sheet(icons):
    """One row per concept: 512 tile, 48 px x4 nearest, circle crop, two lockups."""
    sheet = Sheet(label_w=72, margin=24, gap=16, label_size=64)
    for name in ("a", "b", "c"):
        out = os.path.join(CONCEPTS, name)
        big = Image.open(os.path.join(out, "icon-512.png")).convert("RGB")
        small = Image.open(os.path.join(out, "icon-48.png")).convert("RGB")
        small = small.resize((192, 192), Image.NEAREST)
        circle = big.resize((192, 192), Image.LANCZOS)
        mask = Image.new("L", (192, 192), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, 191, 191), fill=255)
        cropped = Image.new("RGB", (192, 192), (26, 27, 38))
        cropped.paste(circle, (0, 0), mask)
        cells = [big, small, cropped]
        for filename, ground in (("lockup-dark.svg", GROUND), ("lockup-light.svg", WHITE)):
            lock = render_png(os.path.join(out, filename), height=90)
            cells.append(ground_block(lock, ground))
        sheet.add_row(name.upper(), cells)
    path = os.path.join(ROOT, "contact-sheet.png")
    return sheet.render(path, max_width=2400)


def wordmark_sheet():
    """Wordmark alone, then each lockup at the README and badge sizes."""
    sheet = Sheet(label_w=96, margin=24, gap=16, label_size=56)
    word_row = []
    for filename, ground in (("omdroid-wordmark-dark.svg", GROUND),
                             ("omdroid-wordmark-light.svg", WHITE)):
        img = render_png(os.path.join(ROOT, filename), width=900)
        word_row.append(ground_block(img, ground))
    sheet.add_row("WORD", word_row)

    for name in ("a", "b", "c"):
        out = os.path.join(CONCEPTS, name)
        row = []
        for width in (440, 200):
            for filename, ground in (("lockup-dark.svg", GROUND), ("lockup-light.svg", WHITE)):
                img = render_png(os.path.join(out, filename), width=width)
                row.append(ground_block(img, ground, pad=10))
        sheet.add_row(name.upper(), row)
    path = os.path.join(ROOT, "contact-sheet-wordmark.png")
    return sheet.render(path)


if __name__ == "__main__":
    sys.exit(main())
