#!/usr/bin/env python3
"""Numbers for judging a tablet screenshot without eyes.

usage: shotstat.py <shot.png> [bar_px] [bottom_px]
  bar_px     height of the Omarchy bar in device pixels (26 logical px at scale 2 = 52)
  bottom_px  strip at the bottom to ignore (Termux:X11 extra-key row), default 140

INK = share of pixels in a region that differ from that region's most common colour by
more than 24 in any channel. A bar that is only a background band has INK near 0 %; a bar
with a clock, workspace numbers and tray icons has clearly more. A desktop that is only
Hyprland's default fill (17,17,17) has INK near 0 %; a wallpaper or a window raises it.
"""
import sys
import warnings
from collections import Counter

from PIL import Image

warnings.simplefilter("ignore")


def modal(pixels):
    c = Counter(pixels)
    col, n = c.most_common(1)[0]
    return col, n / len(pixels), len(c)


def ink(pixels, ref, tol=24):
    hit = sum(1 for p in pixels if max(abs(p[0] - ref[0]), abs(p[1] - ref[1]), abs(p[2] - ref[2])) > tol)
    return hit / len(pixels)


def near(a, b, tol=6):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


path = sys.argv[1]
bar_px = int(sys.argv[2]) if len(sys.argv) > 2 else 52
bottom_px = int(sys.argv[3]) if len(sys.argv) > 3 else 140

im = Image.open(path).convert("RGB")
W, H = im.size
print(f"size {W}x{H}")

whole = list(im.resize((320, max(1, H * 320 // W)), Image.Resampling.NEAREST).getdata())
wcol, wshare, wn = modal(whole)
print(f"WHOLE   modal={wcol} share={wshare:.1%} distinct={wn}")

bar = list(im.crop((0, 0, W, bar_px)).getdata())
bcol, bshare, bn = modal(bar)
bink = ink(bar, bcol)
print(f"BAR     top {bar_px}px modal={bcol} share={bshare:.1%} distinct={bn} INK={bink:.2%}")

desk = im.crop((0, bar_px, W, H - bottom_px))
dl = list(desk.resize((400, max(1, desk.size[1] * 400 // W)), Image.Resampling.NEAREST).getdata())
dcol, dshare, dn = modal(dl)
dink = ink(dl, dcol)
print(f"DESKTOP modal={dcol} share={dshare:.1%} distinct={dn} INK={dink:.2%}")

# Left / centre / right thirds of the bar: Omarchy puts workspaces left, clock centre, tray right.
for name, x0, x1 in (("left", 0, W // 3), ("centre", W // 3, 2 * W // 3), ("right", 2 * W // 3, W)):
    seg = list(im.crop((x0, 0, x1, bar_px)).getdata())
    print(f"  bar {name:6s} INK={ink(seg, bcol):.2%}")

hints = []
if wcol[0] > 230 and wcol[1] > 230 and wcol[2] > 230 and wshare > 0.8:
    hints.append("LIGHT SCREEN: the launcher/splash activity is on top, not the X display")
if near(wcol, (0, 0, 0)) and wshare > 0.93:
    hints.append("BLACK: the X display is up but nothing is presented (or the panel is off)")
if near(dcol, (17, 17, 17)) and dshare > 0.95:
    hints.append("DESKTOP is Hyprland's default fill (17,17,17): no wallpaper, no window")
if not near(bcol, dcol) and bink < 0.003:
    hints.append("BAR band is drawn but EMPTY (no text/icons)")
if near(bcol, dcol):
    hints.append("no distinct BAR band at the top")
if bink >= 0.01:
    hints.append("BAR has content (INK >= 1 %)")
if dink >= 0.05 and not near(dcol, (17, 17, 17)):
    hints.append("DESKTOP shows a wallpaper or window (INK >= 5 %)")
for h in hints:
    print("HINT:", h)

# Row bands: where the gaps, the bar, flat fills and the key row sit, for a reader without eyes.
# BAR/DESKTOP above assume the desktop starts at screen row 0; with a gap above it, read BANDS.
SW = 160
small = im.resize((SW, max(1, H // 4)), Image.Resampling.NEAREST)
bands = []
for y in range(small.size[1]):
    cnt = Counter(tuple(int(round(v / 8.0) * 8) for v in small.getpixel((xx, y))) for xx in range(SW))
    col, n = cnt.most_common(1)[0]
    key = ("FLAT", col) if n / SW >= 0.97 else ("VARIED", None)
    if bands and bands[-1][0] == key:
        bands[-1][2] = y
    else:
        bands.append([key, y, y])
print("BANDS (screen rows, 4 px steps; FLAT = one colour across >= 97 % of the row, bands under 12 px dropped):")
for key, y0, y1 in [b for b in bands if (b[2] - b[1] + 1) * 4 >= 12][:24]:
    print(f"  rows {y0 * 4}-{y1 * 4 + 3} {key[0]}{' ' + str(key[1]) if key[1] else ''}")

im.crop((0, 0, W, min(H, 160))).save(path[:-4] + "-bar.png")
