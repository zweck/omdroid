#!/usr/bin/env python3
"""Host half of probe.sh: turn the guest's PROBE-JSON line into facts and one verdict.

PASS means every size in the chain agrees:
  X root == cage window (or the root rounded down to even numbers) == Hyprland monitor, the monitor's logical size (pixels / scale) is
  integral, and the shell's layers fit it (background covers it, the bar spans one edge).
Exit status 0 on PASS, 1 on FAIL. `-q` prints only the VERDICT line.
"""
import json
import sys

quiet = "-q" in sys.argv[1:]
raw = sys.stdin.read()
data = None
for line in raw.splitlines():
    if line.startswith("PROBE-JSON: "):
        data = json.loads(line[len("PROBE-JSON: "):])
if data is None:
    tail = " | ".join(raw.strip().splitlines()[-3:])
    print("VERDICT: FAIL the probe did not run in the guest: " + tail[-300:])
    sys.exit(1)

facts, reasons = [], []
x, hypr = data["x"], data["hypr"]

root = x.get("root")
wins = [w for w in x.get("windows", []) if w["w"] >= 64 and w["h"] >= 64]
win = wins[0] if wins else None
if x.get("error"):
    reasons.append("X: " + x["error"])
elif root is None:
    reasons.append("X root has no geometry")
if root:
    facts.append("xroot %dx%d" % (root["w"], root["h"]))
if win:
    facts.append("cage-window %dx%d @%d,%d" % (win["w"], win["h"], win["x"], win["y"]))
    if len(wins) > 1:
        facts.append("(%d top-level windows >= 64 px: more than one compositor on the display?)" % len(wins))
elif not x.get("error"):
    reasons.append("no compositor window on the X root (cage is not running)")
# The guest may round the window down to even numbers (launch-contract.md section 1).
if root and win:
    even = (root["w"] - root["w"] % 2, root["h"] - root["h"] % 2)
    if (win["x"], win["y"]) != (0, 0) or (win["w"], win["h"]) not in ((root["w"], root["h"]), even):
        reasons.append("cage window %dx%d@%d,%d != X root %dx%d (not resized to the root yet)"
                       % (win["w"], win["h"], win["x"], win["y"], root["w"], root["h"]))

logical = None
if hypr.get("error"):
    reasons.append("Hyprland: " + hypr["error"])
mons = [m for m in (hypr.get("monitors") or []) if not m.get("disabled")]
if mons:
    m = mons[0]
    scale = float(m.get("scale") or 1)
    lw, lh = m["width"] / scale, m["height"] / scale
    logical = (lw, lh)
    facts.append("monitor %s %dx%d@%.2f scale %.2f -> logical %gx%g transform %s"
                 % (m.get("name"), m["width"], m["height"], m.get("refreshRate", 0), scale, lw, lh, m.get("transform")))
    if len(mons) > 1:
        facts.append("(%d monitors)" % len(mons))
    if win and (m["width"], m["height"]) != (win["w"], win["h"]):
        reasons.append("Hyprland monitor %dx%d != cage window %dx%d (Hyprland has not followed the parent)"
                       % (m["width"], m["height"], win["w"], win["h"]))
    if abs(lw - round(lw)) > 0.01 or abs(lh - round(lh)) > 0.01:
        reasons.append("logical size %gx%g is not integral at scale %.2f" % (lw, lh, scale))
elif not hypr.get("error"):
    reasons.append("Hyprland reports no enabled monitor")

layers = []
for output in (hypr.get("layers") or {}).values():
    for level, items in (output.get("levels") or {}).items():
        for item in items:
            layers.append((level, item.get("namespace"), item.get("w"), item.get("h")))
for level, ns, w, h in layers:
    facts.append("layer L%s %s %sx%s" % (level, ns, w, h))

if logical and not hypr.get("error"):
    lw, lh = logical
    bg = [l for l in layers if l[1] == "omarchy-background"]
    bar = [l for l in layers if l[1] == "omarchy-bar"]
    if not bg:
        reasons.append("no omarchy-background layer (shell not up, or it loaded without plugins)")
    elif abs(bg[0][2] - lw) > 1 or abs(bg[0][3] - lh) > 1:
        reasons.append("background layer %sx%s != logical %gx%g (stale after a resize)" % (bg[0][2], bg[0][3], lw, lh))
    if not bar:
        reasons.append("no omarchy-bar layer (shell not up)")
    elif abs(bar[0][2] - lw) > 1 and abs(bar[0][3] - lh) > 1:
        reasons.append("bar layer %sx%s spans neither edge of logical %gx%g (stale after a resize)"
                       % (bar[0][2], bar[0][3], lw, lh))

clients = hypr.get("clients")
facts.append("clients %s" % (len(clients) if isinstance(clients, list) else "?"))
facts.append("ready-marker %s" % (repr(data.get("ready")) if data.get("ready") is not None else "absent"))

if not quiet:
    for f in facts:
        print(f)
if reasons:
    print("VERDICT: FAIL " + "; ".join(reasons))
    sys.exit(1)
print("VERDICT: PASS")
