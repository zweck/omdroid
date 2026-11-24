# Brief BRAND: a new Omarchy-style logo and a rewritten README for Omdroid

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`. You cannot view images. The parent can: your job is to make every
visual decision checkable as TEXT (ASCII previews, numbers), and the parent picks the final logo
from your contact sheet.

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`, read `AGENTS.md`).
Your report, created in your first calls and updated after EVERY item: `docs/brand/REPORT.md`.

## What this is

The project was "Phoshdroid": one Android APK that ran postmarketOS + Phosh under proot. On this
branch it now boots **Omarchy** (Hyprland + the Omarchy shell, Arch guest) instead, and the GitHub
repo was renamed today to `zweck/omdroid`. The product name is now **Omdroid** (wordmark:
`OMDROID`). Phil asked for two things: rewrite the README, and redesign the logo to be "more
omarchy-esque". The Android package id stays `com.phoshdroid.app`; do not write that it changed.

## Hard rules

- You own `README.md`, everything under `docs/brand/`, and nothing else. Two other agents are
  working in this tree right now (one owns `app/**`, one owns `rootfs/**` and `docs/omarchy/**`).
  Never write under `app/`, `rootfs/`, `docs/omarchy/`, `termux-x11/`, `termux-app/`. Android icon
  resources are STAGED under `docs/brand/`; the parent copies them into `app/` later.
- No `adb`, no gradle, no git command that changes anything (no add, commit, stash, checkout,
  restore, clean, rm). Read-only network is fine (`gh release list -R zweck/omdroid`, `curl` GETs);
  nothing that posts, pushes, comments or edits anything remote.
- Do not delete or modify the old assets (`docs/phoshdroid-logo.svg`, `docs/phoshdroid.gif`); the
  README just stops using them. List them in the report as "now unused".
- Scratch: `~/.hermes/cache/scratch/omarchy/brand/`.
- If a terminal command is BLOCKED waiting for approval, do not retry or rephrase it: put the
  logic in a script file with `write_file` and run that.

## Part 1 — the logo

### Study the reference first (read, do not copy)
Omarchy's own brand files are on this machine:
- `/usr/share/omarchy/logo.txt` — the wordmark as block characters. `cat` it.
- `/usr/share/omarchy/icon.txt` — the square icon as block characters. `cat` it.
- `/usr/share/omarchy/logo.svg` — the wordmark as one filled path. Every coordinate is a multiple
  of 15 in a 1215x285 viewBox (an 81x19 grid).
- Palette (Tokyo Night, Omarchy's default theme):
  `~/.hermes/cache/scratch/omarchy/upstream/usr/share/omarchy/themes/tokyo-night/colors.toml`
  (`background #1a1b26`, `accent/blue #7aa2f7`, `foreground #a9b1d6`, `bright_foreground #c0caf5`,
  `green #9ece6a`, `magenta #ad8ee6`, `orange #eb927b`, `lighter_background #24283b`, ...).

What makes it "Omarchy": everything sits on one square grid; shapes are flat fills with one
uniform stroke thickness; edges are horizontal or vertical only, and corners are cut by small
STEPS (staircase chamfers), never curves; no gradients, no shadows, no outlines, one colour on a
dark ground; heavy, compact, all-caps letters; the icon is a square frame of constant-width
strokes with deliberate gaps. The current Phoshdroid logo (`docs/phoshdroid-logo.svg`: three
isometric tiles with gradients in Android green) is the opposite of all that.

**Draw your own shapes.** Do not copy path data, glyph outlines or the icon layout from Omarchy's
files: this must read as a sibling in the same visual language, not as Omarchy's logo. Omdroid is
not affiliated with Omarchy.

### How to build it: bitmaps in a generator script
Write `docs/brand/build_brand.py` (Python 3 standard library + PIL, which is installed; PNG export
with `/usr/sbin/rsvg-convert`). Every shape is defined IN THE SCRIPT as an ASCII bitmap (rows of
`#` and `.`), so you can read your own drawing as text. The script turns bitmaps into SVG (merge
cells into rectangles or one rectilinear path; integer coordinates only) and writes every output
below. Running it twice must give byte-identical SVGs.

Make **three clearly different icon concepts** (A, B, C), each a square mark on a grid of at most
32x32 cells, plus ONE shared wordmark `OMDROID` in block capitals (uniform stroke, stepped
corners, common cap height, even letter spacing; `O`, `M`, `D`, `R`, `I` drawn by you). Seeds, to
use or replace — the three must not be variations of one idea:
- a blocky Android-robot head (antenna nubs, two eye gaps) inside or fused with a square frame
  that has an Omarchy-style break in it;
- an `OM` monogram built from the wordmark's own strokes inside a square;
- a phone-shaped frame holding a tiled window layout (one master pane, stacked panes): "a tiling
  desktop inside a phone".
For each concept write two or three sentences of rationale in the report and paste its ASCII
bitmap there.

### Outputs (all written by the script)
```
docs/brand/concepts/<a|b|c>/icon.svg                 mark only, transparent ground, colour = accent
docs/brand/concepts/<a|b|c>/icon-tile.svg            mark on a #1a1b26 square with 1/6 padding
docs/brand/concepts/<a|b|c>/lockup-dark.svg          mark + OMDROID wordmark, for dark pages
docs/brand/concepts/<a|b|c>/lockup-light.svg         same, colours for white pages
docs/brand/concepts/<a|b|c>/icon-512.png, icon-48.png   rendered from icon-tile.svg
docs/brand/concepts/<a|b|c>/ascii.txt                the mark as block characters (two chars per cell)
docs/brand/concepts/<a|b|c>/android/ic_launcher_foreground.xml   vector drawable, 108x108 viewport
docs/brand/concepts/<a|b|c>/android/ic_launcher_background.xml   solid #1a1b26
docs/brand/concepts/<a|b|c>/android/ic_launcher_monochrome.xml   same shape, one colour (themed icons)
docs/brand/concepts/<a|b|c>/android/ic_launcher.xml  adaptive-icon with background, foreground, monochrome
docs/brand/omdroid-logo.txt                          the wordmark as block characters, at most 80 columns
docs/brand/contact-sheet.png                         the sheet the parent judges, see below
docs/brand/omdroid-logo-dark.svg, omdroid-logo-light.svg, omdroid-icon.svg   PROVISIONAL copies of concept A
```
Look at how the existing Android files are written before generating yours (read-only):
`app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`,
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

`contact-sheet.png`: one row per concept, labelled `A`, `B`, `C` in large text. Each row shows,
left to right: the icon tile at 512 px, the icon tile at 48 px enlarged 4x with nearest-neighbour
(so the parent sees real small-size pixels), the icon on a circular mask at 192 px (how a launcher
crops it), `lockup-dark` on `#1a1b26`, `lockup-light` on `#ffffff`. Width at most 2400 px.

### Checks the script must run and print (and you paste into the report)
A concept that fails a check is fixed, not excused.
1. Grid: every number in every generated SVG path/rect is an integer; only horizontal and
   vertical segments (assert on the generated commands).
2. Palette: every colour in every output is one of the palette values (plus `#ffffff` and
   `#1a1b26` grounds).
3. Contrast (WCAG relative-luminance formula, compute it in the script): mark vs its ground is at
   least 4.5 in `icon-tile`, `lockup-dark` and `lockup-light` (pick a darker palette colour for the
   light lockup if the accent fails on white; print the ratios).
4. Small size: in `icon-48.png` the thinnest stroke is at least 2 px wide (cells x scale), and
   between 18 % and 70 % of the tile's pixels belong to the mark.
5. Launcher safe zone: in the Android foreground, every filled cell lies inside the centred
   66x66 square of the 108x108 viewport.
6. Android XML: each file parses as XML; `pathData` uses only `M`, `H`, `V`, `L`, `Z` with numbers.
7. PNGs exist, have the expected pixel size, and are not a single colour.
8. The three marks differ: for each pair, at least 25 % of cells differ after scaling both
   bitmaps to a common 32x32 grid.
9. `omdroid-logo.txt` is at most 80 columns and every line has the same width.

## Part 2 — the README

Rewrite `README.md` from scratch for Omdroid as it is on THIS branch. Read first:
`AGENTS.md`, the current `README.md` (to see what existed and Phil's voice), then
`docs/omarchy/ARCHITECTURE.md`, `CONTRACT.md`, `payload-findings.md`, `device-bringup-report.md`,
`launch-contract.md`, `app/build.gradle.kts`, `rootfs/package-omarchy-rootfs.sh`, `LICENSE*`, and
`gh release list -R zweck/omdroid` (the published releases are the old Phosh builds).

### Rules for the text
- **Every factual sentence must come from a file you read.** In the report keep a table: README
  claim -> file and line. A claim you cannot source is left out of the README and listed in the
  report under UNVERIFIED (licences of third-party projects included: quote them only from a
  licence file or header you actually read).
- Be exact about the state. It is an alpha that has been run on ONE device (Xiaomi tablet
  `25091RP04G`, Android 16, Adreno GPU, 3200x2136). What was measured working there: wallpaper and
  bar, opening a window, hardware/IME keyboard text, touch. What is not there yet or not proven:
  the bar's audio widget has no data, features that need systemd are missing, other devices and
  screen sizes are being worked on right now, no Omarchy build has been released (build from
  source), sideload only, about 1.5 GB of guest image in the APK and about 4.1 GB once extracted.
  Take the numbers from the docs or the code, not from this brief, and cite them (reading files
  under `app/` and `rootfs/` is fine and useful, e.g. the size comment in
  `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt` near `requiredBytes`; you only may
  not WRITE there).
- Say plainly what it is and is not: a nested stack (X server in the app -> cage -> Hyprland ->
  Omarchy shell) on unrooted Android through proot; not a native compositor, not a VM, not
  affiliated with Omarchy, Hyprland or Termux. Credit the upstream projects it is built from
  (`BlackFireAlex/omarchy-android` for the guest payload and the patched Hyprland/aquamarine/Mesa,
  Omarchy, Termux:X11, proot, cage/wlroots) with links.
- Sections, in this order: logo + name + one-line description; what it is (one short paragraph);
  status (the honest table: works / not yet); requirements; how it works (the chain, one Mermaid
  diagram at most); build from source (the real commands from `AGENTS.md` and the packager script,
  including submodules and the fact that the rootfs repack script is machine-specific); project
  layout; troubleshooting only for failures the docs actually record; credits; licence.
- Header: use a `<picture>` element so GitHub shows `docs/brand/omdroid-logo-dark.svg` on dark
  pages and `docs/brand/omdroid-logo-light.svg` on light ones. Put `docs/brand/omdroid-logo.txt`
  nowhere in the README (it is for terminals).
- Screenshots: reference exactly `docs/screenshots/desktop.png` and `docs/screenshots/terminal.png`
  with honest alt text. The parent adds those two files after the next build; do not create them.
- Voice: plain, direct, short sentences, no hype, no emoji in headings, no "blazing", no
  exclamation marks. Under 170 lines. No badges that would be false today (no release badge for a
  build that is not released).
- Links to the repo use `https://github.com/zweck/omdroid`.

## Report format (`docs/brand/REPORT.md`)

```
# Brand + README report
HEAD: <git rev-parse --short HEAD>   Model that ran: deepseek-flash

## STATUS TABLE
| Item | Status | Proof |
| L1 generator runs, deterministic | PASS/FAIL/PENDING | sha256 of two runs |
| L2 three concepts + wordmark | ... | paths |
| L3 checks 1-9 | ... | the printed lines, per concept |
| L4 contact sheet | ... | path, pixel size |
| R1 README rewritten | ... | line count |
| R2 every claim sourced | ... | number of claims, number left out |

## Concepts (per concept: rationale, ASCII bitmap, check numbers)
## Wordmark (ASCII, cell size, stroke)
## README claim -> source table
## UNVERIFIED (left out of the README)
## For the parent to judge by eye (what to look at on the contact sheet, your own doubts per concept)
## Now unused / follow-ups (old assets, app label still "Phoshdroid", etc.)
## Log
```

Flip a row the moment its proof exists. You have roughly 90 tool calls: write the generator in one
file and iterate by running it, do not hand-edit generated files, and when you are near the end
write the table's final state first.

## Final reply

At most 10 lines: status per item, the contact-sheet path, which concept you would pick and why
in one sentence, anything in the README you had to leave out, the report path.


---

## ROUND 2 (added by the parent, who has now looked at `contact-sheet.png`)

Round 1 was accepted as a first pass: generator, checks, README and report are good, keep them.
What follows is what the eye sees and the numbers did not catch. Change the bitmaps in
`build_brand.py`, re-run, update the report (new section "Round 2", keep round 1). Do NOT touch
`README.md` in this round.

### V1. The wordmark is the main miss: it is thin and condensed, the opposite of Omarchy
Seen: `OMDROID` reads as a light, tall, narrow pixel font next to a heavy icon. Measured from
your own `omdroid-logo.txt`: stroke 2 cells on a cap height of 19 (ratio 0.105), letters 9 wide.
Omarchy's wordmark in `/usr/share/omarchy/logo.txt` is stroke 3 on a cap height of 8 (0.375) with
letters about as wide as they are tall: a slab of ink with small holes in it.
Redraw all five glyphs (`O`, `M`, `D`, `R`, `I`), still your own shapes:
- cap height 8 to 10 cells, stroke 3 cells, stroke / cap height between 0.30 and 0.40;
- `O`, `D`, `R` at least 0.9 x cap height wide, `M` wider than `O`, `I` exactly one stroke wide;
- outer corners cut by ONE one-cell step; counters (the holes) small and rectangular;
- `M` must not be mistaken for `H` or `N` when you read the ASCII; `R` must not read as `P` or `K`;
- gap between letters 1 or 2 cells, the same everywhere.
New checks to add and print: the stroke ratio; glyph widths; in the lockup scaled to 440 px wide
(the README size) the thinnest wordmark stroke is at least 4 px; ink coverage of the wordmark's
bounding box is at least 45 %.
Lockup: wordmark cap height is 55-70 % of the icon's height, centred vertically on it, gap
between icon and wordmark = 2 strokes.
`omdroid-logo.txt`: one character per cell horizontally; you may use half blocks (`▀ ▄ █`) for two
cells per text row, like Omarchy does. At most 80 columns and at most 12 lines.

### V2. Concept A reads as a rabbit (or a bottle), not as an Android head
Seen: the two long bars that run from the frame's top edge down into the head look like ears, and
the head is taller than it is wide. Keep the frame and its bottom break. Redraw the head:
- a dome clearly WIDER than tall (about 2:1), flat bottom edge, stepped top corners;
- two eye gaps in the lower half;
- two SHORT antenna stubs (2 or 3 cells long) on the dome's upper left and right, each stepped one
  cell outward, not touching the frame;
- at least 2 empty cells between the head (antennas included) and the frame on every side.
Add a check: the head's bounding box is at least 1.6 times wider than tall, and no head cell is
adjacent to a frame cell.

### V3. Concept B: rebuild it from the NEW `O` and `M`
The old tall `O` and `M` go away with the old wordmark. Place the new glyphs in the square (side
by side or stacked, your call); it must stay above the 18 % coverage floor at 48 px.

### V4. Concept C is the cleanest of the three; only match its weight
Give its frame the same stroke as the other two (3 cells) and re-check the pane gaps at 48 px
(at least 2 px each). Leave the idea alone.

### V5. The contact sheet cuts off its last column
Seen: the light lockup ends at `OMDROI`, clipped by the right edge (the lockup PNGs themselves are
complete, 1524x512). Fix the layout and add a check that every pasted element lies inside the
canvas. Also write `docs/brand/contact-sheet-wordmark.png`: the wordmark alone, large, on
`#1a1b26` and on white; then each concept's lockup at exactly 440 px and at 200 px wide on both
grounds (these are real sizes: README header and a small badge), each row labelled.

### Deliverables of this round
All round-1 outputs regenerated (same paths), both contact sheets, every check (old 1-9 plus the
new ones) printed and green, report updated with the new ASCII of the wordmark and of A, B, C.
`PROVISIONAL` stays `a` unless the parent says otherwise.

Final reply, at most 8 lines: what changed per item V1-V5 with its decisive number, both sheet
paths, and any item you could not meet.
