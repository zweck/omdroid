# Brand + README report

HEAD: abbd055   Model that ran: deepseek-flash

## STATUS TABLE

| Item | Status | Proof |
| --- | --- | --- |
| L1 generator runs, deterministic | PASS | `docs/brand/build_brand.py`, two consecutive runs: aggregate sha256 of all 44 generated files (svgs, pngs, xml, txt) = `6f8afe51a013f6a8c58cd16a988a6c96de7a312132d1829da74fa0c88ec874f5` on both runs. Every file byte-identical (44/44 by `sha256sum`). |
| L2 three concepts + wordmark | PASS | `docs/brand/concepts/{a,b,c}/` (icon.svg, icon-tile.svg, lockup-dark.svg, lockup-light.svg, icon-512.png, icon-48.png, ascii.txt, android/*.xml); one shared wordmark in all six lockups; `docs/brand/omdroid-logo.txt`. |
| L3 checks 1-9 | PASS | all 27 check lines printed PASS, `SUMMARY checks failed: none` (lines quoted per concept below). |
| L4 contact sheet | PASS | `docs/brand/contact-sheet.png`, 1770x1584, one row per concept, labels A/B/C at 64 px. |
| R1 README rewritten | PASS | `README.md`, 167 lines (limit 170). |
| R2 every claim sourced | PASS | 34 claims in the table below; 6 things left out (see UNVERIFIED). |

Provisional files (`omdroid-logo-dark.svg`, `omdroid-logo-light.svg`, `omdroid-icon.svg`) are concept A, emitted by the script from the `CHOSEN = "a"` constant. Change that one value and re-run to switch.

## Concepts

All three are 32x32 cell bitmaps; the tile is 48x48 cells (8 cells = 1/6 padding on every side), 16 SVG units per cell. Stroke widths are in cells.

### Concept A — robot head fused into a broken frame

A three-cell square frame with one deliberate break in the bottom edge, and a blocky
Android-style head inside it: a three-step stepped dome, two antenna nubs plugged into the
frame's top bar (so head and frame are one object), and two eye gaps. The break is
bottom-right of centre so the silhouette is not symmetrical. This is the concept the
provisional files and the README header use.

```
    ████████████████████████████████████████████████████████    
  ████████████████████████████████████████████████████████████  
████████████████████████████████████████████████████████████████
██████              ██████████    ██████████              ██████
██████              ██████████    ██████████              ██████
██████                ██████        ██████                ██████
██████                ██████        ██████                ██████
██████                ██████        ██████                ██████
██████                ██████        ██████                ██████
██████                ██████        ██████                ██████
██████                ████████████████████                ██████
██████                ████████████████████                ██████
██████            ████████████████████████████            ██████
██████            ████████████████████████████            ██████
██████          ████████████████████████████████          ██████
██████          ████████████████████████████████          ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ██████      ████████████      ██████        ██████
██████        ██████      ████████████      ██████        ██████
██████        ██████      ████████████      ██████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████        ████████████████████████████████████        ██████
██████                                                    ██████
████████████████████████████████████              ██████████████
  ██████████████████████████████████              ████████████  
    ████████████████████████████████              ██████████    
```

Checks: 1 integer M/H/V/Z only; 2 palette `#1a1b26 #414868 #7aa2f7 #c0caf5 #ffffff`;
3 accent/ground 6.79, dark lockup 6.79, light lockup `#414868`/white 8.93; 4 thinnest stroke
3 cells = 3.0 px at 48 px, mark 27.9 % of tile; 5 all cells inside 21..87; 6 XML parses;
7 PNGs 512²/48² , raster matches bitmap 0 cell mismatches.

### Concept B — OM monogram

The heaviest and most typographic of the three: a stepped square O (stroke 3) whose right
side is separated by a two-cell gap from an M built from the same stroke, with the V's
counter narrowing as it descends to a solid foot. No outer frame at all, so it is the one
concept that reads as a wordmark fragment rather than a badge.

```
                                                                
                                                                
                                                                
                                                                
      ██████████████████      ████████████      ████████████    
    ██████████████████████    ████████████      ████████████    
  ██████████████████████████  ████████████      ████████████    
  ██████              ██████  ████████████      ████████████    
  ██████              ██████  ████████████      ████████████    
  ██████              ██████  ████████████      ████████████    
  ██████              ██████  ████████████      ████████████    
  ██████              ██████  ████████████      ████████████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████    ██████████    ██████    
  ██████              ██████  ██████      ██████      ██████    
  ██████              ██████  ██████      ██████      ██████    
  ██████              ██████  ██████      ██████      ██████    
  ██████              ██████  ██████      ██████      ██████    
  ██████              ██████  ██████      ██████      ██████    
  ██████████████████████████  ██████      ██████      ██████    
    ██████████████████████    ██████      ██████      ██████    
      ██████████████████      ██████      ██████      ██████    
                                                                
                                                                
                                                                
                                                                
```

Checks: 2 same palette set; 3 same contrast ratios (6.79 / 6.79 / 8.93); 4 thinnest stroke
3 cells = 3.0 px, mark 18.7 % of tile (the lightest of the three, just over the 18 % floor);
5-7 PASS, 0 raster mismatches.

### Concept C — a tiling desktop inside a phone

A portrait phone frame (stroke 2) with a six-cell break in the top edge standing in for the
speaker, and a master-plus-stack tiling layout inside: one tall master pane on the left, two
stacked panes on the right, two-cell gaps between panes and bezel. The most literal reading
of the product, and the one that survives 48 px best after A.

```
                                                                
            ██████████████            ██████████████            
          ████████████████            ████████████████          
        ████                                        ████        
        ████                                        ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████                    ████        
        ████    ████████████████                    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████    ████████████████    ████████████    ████        
        ████                                        ████        
        ████                                        ████        
          ████████████████████████████████████████████          
            ████████████████████████████████████████            
```

Checks: 2 same palette set; 3 same contrast ratios; 4 thinnest stroke 2 cells = 2.0 px, mark
20.5 % of tile; 5-7 PASS, 0 raster mismatches.

Check 8 (pairwise difference): A/B 51.9 %, A/C 54.2 %, B/C 47.5 % of 1024 cells — all far
over the 25 % floor.

## Wordmark

`OMDROID`, cap height 19 cells, stroke 2, 79 cells wide (the `omdroid-logo.txt` limit is 80
columns, so the text file is one character per cell; the mark bitmaps are two characters per
cell). Letters: O, M, D, R, I, reused for the second O and D. Corners are cut by one-cell
steps, the M's V is a staircase that closes to a solid foot, the R's leg steps right one cell
per two rows. 618 cells lit. In the lockups the wordmark is 12 units per cell (228 units cap
height) beside a 512-unit mark, 64 units apart, optically centred on the mark's height.

```
  █████     ████      ████   ███████     ████████      █████     ██   ███████  
 ███████    ████      ████   ████████    ██     ██    ███████    ██   ████████ 
██     ██   ████      ████   ██     ██   ██     ██   ██     ██   ██   ██     ██
██     ██   ████      ████   ██     ██   ██     ██   ██     ██   ██   ██     ██
██     ██   ████      ████   ██     ██   ██     ██   ██     ██   ██   ██     ██
██     ██   ██ ██    ██ ██   ██     ██   ████████    ██     ██   ██   ██     ██
██     ██   ██ ██    ██ ██   ██     ██   ██          ██     ██   ██   ██     ██
██     ██   ██ ██    ██ ██   ██     ██   ██          ██     ██   ██   ██     ██
██     ██   ██ ██    ██ ██   ██     ██   ██ ██       ██     ██   ██   ██     ██
██     ██   ██ ██    ██ ██   ██     ██   ██ ██       ██     ██   ██   ██     ██
██     ██   ██  ██  ██  ██   ██     ██   ██  ██      ██     ██   ██   ██     ██
██     ██   ██  ██  ██  ██   ██     ██   ██  ██      ██     ██   ██   ██     ██
██     ██   ██  ██  ██  ██   ██     ██   ██   ██     ██     ██   ██   ██     ██
██     ██   ██  ██  ██  ██   ██     ██   ██   ██     ██     ██   ██   ██     ██
██     ██   ██  ██  ██  ██   ██     ██   ██    ██    ██     ██   ██   ██     ██
██     ██   ██   ████   ██   ██     ██   ██    ██    ██     ██   ██   ██     ██
██     ██   ██   ████   ██   ██     ██   ██     ██   ██     ██   ██   ██     ██
 ███████    ██   ████   ██   ████████    ██     ██    ███████    ██   ████████ 
  █████     ██   ████   ██   ███████     ██     ██     █████     ██   ███████  
```

Colours: accent `#7aa2f7` for the mark everywhere except the light lockup, which uses
`#414868` because the accent fails on white (2.0:1); the dark lockup's wordmark is
`#c0caf5`, the light one is `#414868`.

## README claim -> source table

| # | Claim in README | Source |
| --- | --- | --- |
| 1 | Runs Arch Linux ARM with Omarchy (Hyprland + Quickshell shell) | `docs/omarchy/ARCHITECTURE.md:23-31`; `docs/omarchy/payload-findings.md:145-146` |
| 2 | Not a native compositor, not a VM | `docs/omarchy/ARCHITECTURE.md:12-21` |
| 3 | X server in the app hosts cage; cage hosts Hyprland | `rootfs/overlay/etc/phoshdroid/start.sh:2-12`; `rootfs/package-omarchy-rootfs.sh:78-83` |
| 4 | proot, unprivileged uid, app data dir | `docs/omarchy/CONTRACT.md:34-42`; `AGENTS.md:3` |
| 5 | No root, no unlocked bootloader, no Magisk | `AGENTS.md:3` (unrooted, proot); the bootloader/Magisk part is an entailment of proot needing neither |
| 6 | Run on exactly one device, 3200x2136 tablet, Adreno | `docs/omarchy/device-bringup-report.md:2,10`; `docs/omarchy/CONTRACT.md:100` |
| 7 | No Omarchy build released; releases are Phoshdroid builds | `gh release list -R zweck/omdroid`: `Phoshdroid 0.0.13-alpha` latest, 2026-05-28 |
| 8 | Wallpaper and bar draw once the X activity is in front | `device-bringup-report.md:6-7` (bar INK 1.58 %, DESKTOP INK 91.35 %) |
| 9 | A plain cold start stops on the all-files Settings page | `device-bringup-report.md:31-42`, `:70` |
| 10 | Window opens (`foot`, 1576x838) | `device-bringup-report.md:9` |
| 11 | Keyboard typing into a window works | `device-bringup-report.md:9` (typed `hello`, `/tmp/typed.txt`) |
| 12 | Touch moves the pointer to the tapped point | `device-bringup-report.md:9` (cursor y 192 -> 429) |
| 13 | Bar and wallpaper survive a shell restart | `device-bringup-report.md:6` (`omarchy-restart-shell`: distinct 421, INK 1.58 %) |
| 14 | Bar audio widget has no data; no `omarchy-proot-audio` | `device-bringup-report.md:81-84`; `CONTRACT.md:104-105`; `payload-findings.md:240-243` |
| 15 | systemd-dependent things dead: NetworkManager, polkit, user units | `payload-findings.md:227-235`; `device-bringup-report.md:83-84`; `ARCHITECTURE.md:53-64` |
| 16 | Audio bridge designed, not demonstrated | `ARCHITECTURE.md:34-36` (design only); no measurement in any report |
| 17 | Other devices / screen sizes not proven | only `device-bringup-report.md` and `r2-*` briefs target serial `<tablet-serial>` |
| 18 | Android 12 (API 31) or newer | `app/build.gradle.kts:12` (`minSdk = 31`) |
| 19 | arm64 | `app/src/main/jniLibs/arm64-v8a/` |
| 20 | Adreno / Mesa KGSL accelerated path, software fallback, no virgl | `CONTRACT.md:100-101`; `payload-findings.md:72,288-298`; `start.sh:39-46` |
| 21 | ~6.5 GB free required | `app/.../LauncherActivity.kt:417-421` (`requiredBytes = 6_500_000_000L`) |
| 22 | ~1.5 GB gzipped asset, ~4.1 GB extracted, 83,151 files | `payload-findings.md:160-167`; `LauncherActivity.kt:417-418` |
| 23 | APK ~1.6 GB, sideload only | `ARCHITECTURE.md:98` |
| 24 | Package id still `com.phoshdroid.app`, rename separate | `app/build.gradle.kts:8,11`; `ARCHITECTURE.md:79` |
| 25 | Hyprland has no X11 backend; aquamarine has 4 backends; no `/dev/dri` | `ARCHITECTURE.md:14-19` |
| 26 | weston's x11 backend dies on `shmget`; cage uses X Present; pixman | `start.sh:2-12`, `:66-72`; `package-omarchy-rootfs.sh:78-83` |
| 27 | No systemd: Hyprland exec'd under `dbus-run-session`, `systemctl` fails | `ARCHITECTURE.md:53-64`; `payload-findings.md:227-235`; `CONTRACT.md:73-80` |
| 28 | Geometry: X root authoritative, cage follows root, Hyprland follows; ready marker | `docs/omarchy/launch-contract.md:8-27,29-44,59-72` |
| 29 | Build commands (clone --recursive, packager, bootstrap, assembleDebug) | `AGENTS.md:16-40`; `rootfs/package-omarchy-rootfs.sh:1-24`; `rootfs/build-bootstrap.sh:1-12` |
| 30 | CI gate `lintDebug` + `test`, CI does not assemble | `AGENTS.md:29-30`, `:68` |
| 31 | Packager caches `~/omarchy-payload`, `OMARCHY_PAYLOAD_DIR` | `rootfs/package-omarchy-rootfs.sh:19` |
| 32 | Old pmOS packager hardcodes a chroot path and needs sudo | `AGENTS.md:63` |
| 33 | Debug only: `compressReleaseAssets` OOMs, release signs with debug keystore | `AGENTS.md:33` |
| 34 | Licences quoted: repo MIT, Termux:GPLv3, Termux-app:GPLv3-only, upstream bundle MIT | `LICENSE:1-3`; `termux-x11/LICENSE:1-2`; `termux-app/LICENSE.md:1`; `package-omarchy-rootfs.sh:4`; `payload-findings.md:43` |

Troubleshooting entries map to claims 9 (plus the recorded `am start` intent,
`device-bringup-report.md:78`), 13 (`start.sh:59-63`, `device-bringup-report.md:15-29,85-86`)
and 14. Every external link in Credits was fetched this session: `omarchy.org`,
`github.com/basecamp/omarchy`, `cage-kiosk/cage`, `wlroots/wlroots`, `archlinuxarm.org`,
`quickshell.org`, `proot-me.github.io`, `BlackFireAlex/omarchy-android`,
`termux/termux-x11`, `termux/termux-app`, `zweck/omdroid` — all HTTP 200.

## UNVERIFIED (left out of the README)

1. **The test tablet's model and Android version.** The brief says Xiaomi `25091RP04G` /
   Android 16; no file in this tree names the tablet's model or OS. `docs/omarchy/device-debug.md:1`
   and `ARCHITECTURE.md:47` describe the *phone* (`SM-F976B`, Android 17), which is not the
   device these reports were measured on. The README therefore says "a 3200x2136 Android
   tablet with an Adreno GPU" and nothing more. If you want the model in the README, it needs
   one line from you or from `adb shell getprop ro.product.model`.
2. **"Other devices and screen sizes are being worked on right now"** (brief). No document in
   the tree says so, so the README says only "not proven".
3. **Licences of cage, wlroots, proot, Omarchy, Quickshell, Hyprland, Arch Linux ARM.** Not
   read, so not quoted; those entries credit the project and link it only. The four quoted
   licences are the ones with a file behind them (see claim 34).
4. **Whether the hand-over fix works.** `LauncherActivity.kt` carries an untested change
   (`device-bringup-report.md:59,75-76`); the README presents the cold-start bug as current,
   which is what the last APK does.
5. **The padding extras in the troubleshooting command.** `device-bringup-report.md:78`
   records `--ei phoshdroid_top_padding_px 160`, while `launch-contract.md:24-27` says there
   is no top gap any more. I quoted the recorded command and labelled it "recorded during
   bring-up on the test tablet" rather than picking a winner.
6. **Exact free-space path and the 1.5 vs 1.6 GB wording.** The guest is 1,595,969,731 B
   (1.49 GiB); the README follows `LauncherActivity.kt` ("~1.5 GB") for the asset and
   `ARCHITECTURE.md` ("~1.6 GB") for the APK, which are different things (asset vs APK).

## For the parent to judge by eye

`docs/brand/contact-sheet.png`, 1770x1584, one row per concept. Row contents, left to right:
the icon tile at 512 px, the real 48 px render blown up 4x with nearest-neighbour, the tile
under a circular launcher mask at 192 px, the dark lockup on `#1a1b26`, the light lockup on
white. I cannot view images; everything below is what the numbers and the bitmaps say, plus
where I expect trouble.

- **A**: the strongest silhouette at 48 px (a frame is a frame at any size) and the only one
  that carries the Android-robot read. My doubt: the head's dome steps are 2 cells each, so at
  48 px they are a 2 px staircase — it may read as a rounded rectangle rather than a dome, and
  the eyes are 3 px squares, which is the smallest feature in any concept. If it looks muddy,
  the dome is one bitmap edit away from a wider first step.
- **B**: the best type, the worst small size. 18.7 % coverage is just over the 18 % floor and
  the M's counter narrows to 2 cells, so at 48 px it may read as three columns. It is the
  concept I would expect a designer to like at 512 px and a phone to lose at 48 px.
- **C**: the most literal (a phone holding a tiling desktop) and the second best at 48 px.
  My doubt: the top-edge break is a 6-cell notch, which at 48 px is a 6 px gap — it may read as
  a bitten edge rather than a speaker. Also the master pane is 8 cells against 6 for the
  stacked panes, an asymmetry that is deliberate but could look like a mistake.
- **Cross-concept**: all three sit on the same 3-cell (A, B) or 2-cell (C) stroke; C's thinner
  frame is deliberate (phones read thin) but it makes C the lightest-looking lockup next to A
  and B. The wordmark is shared, so the lockup comparison isolates the mark.

**My pick: A**, because a square frame survives 48 px, the break gives it the Omarchy "gap"
tell, and the head is the only silhouette here that says "Android" without a caption.

## Now unused / follow-ups

- `docs/phoshdroid-logo.svg` and `docs/phoshdroid.gif` are no longer referenced by the README
  (kept, not modified, as instructed). `docs/phoshdroid.mp4` was already unreferenced.
- The app label is still `Phoshdroid` (`app/src/main/res/values/strings.xml:3`), and the
  package id is still `com.phoshdroid.app`. Both are outside this brief's write scope.
- Android icon resources are staged, not installed: `docs/brand/concepts/<x>/android/` holds
  `ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml` and
  `ic_launcher.xml`. Copy the chosen concept's four files into
  `app/src/main/res/{drawable,mipmap-anydpi-v26}/` — note the existing launcher drawables use
  a `<shape>` background, and `mipmap-anydpi-v26/ic_launcher.xml` currently has no
  `<monochrome>` entry, so this is an addition, not a drop-in overwrite.
- `README.md` references `docs/screenshots/desktop.png` and `docs/screenshots/terminal.png`;
  neither exists yet (you are adding them after the next build). Until then GitHub shows two
  broken images.
- If you pick B or C, change `CHOSEN = "a"` in `docs/brand/build_brand.py` and re-run: the
  three provisional files and the README header then point at that concept.
- The contact sheet is 1770x1584 at 1x. The 48 px column is the part that matters; if you want
  a zoom of any single row I can emit one.

## Log

- Read brief, created this report, HEAD abbd055, device/adb untouched, no git writes.
- Read references: Omarchy `logo.txt`, `icon.txt`, `logo.svg` (grid 81x19, 15-unit cells, 1-cell
  stroke, 2-step chamfers), tokyo-night `colors.toml`, existing Android launcher XMLs.
- Wrote `docs/brand/build_brand.py` (bitmaps -> rects -> M/H/V/Z paths; rsvg-convert for PNGs).
- First run: check 1/6 false failure (`\bd="` matched `xmlns:android="http://..."`); fixed the
  regex, added a raster-vs-bitmap assertion to check 7 (0 cell mismatches on all three).
- Rewrote A's head as a three-step dome after reading the first ASCII output: the flat slab
  read as a rectangle with two slots.
- Fixed a real wordmark bug found the same way: the M's band-2 rows had the stem broken
  (`#.##` instead of `##.##`), so the left stem was split in the ASCII. Re-ran; 618 cells lit.
- Read for the README: `AGENTS.md`, old `README.md`, `docs/omarchy/{ARCHITECTURE,CONTRACT,launch-contract,payload-findings,device-bringup-report}.md`,
  `app/build.gradle.kts`, `rootfs/package-omarchy-rootfs.sh`, `rootfs/build-bootstrap.sh`,
  `rootfs/overlay/etc/phoshdroid/start.sh`, `LICENSE`, `termux-{x11,app}/LICENSE*`,
  `LauncherActivity.kt` (`requiredBytes`), `gh release list -R zweck/omdroid`.
- Wrote `README.md` (167 lines), trimmed from 177 to clear the 170-line limit without dropping
  a claim.

---

# Round 2

Round 1 is kept above unchanged. This round changes bitmaps only; `README.md` was not touched
(it still points at `docs/brand/omdroid-logo-dark.svg`, which is regenerated from the new
wordmark, so the header stays consistent).

## Round 2 status table

| Item | Status | Decisive number |
| --- | --- | --- |
| V1 wordmark redrawn | PASS | stroke 3 / cap 10 = **0.300** (was 2/19 = 0.105); widths O9 M15 D9 R9 I3; ink **65.7 %** of the box (was 55.2 %); thinnest stroke at 440 px = **13.6 px**; lockup cap **62.5 %** of the icon, centred, gap **192 = 2 strokes** |
| V2 concept A head | PASS | head bbox **22x13 = 1.69:1** (1.6 required; head alone 2.2:1), **0** head cells touching the frame, gaps L2 R2 T7 B6 |
| V3 concept B from the new O and M | PASS | **498 cells = 48.6 % of the mark, 21.6 % of the tile** (floor 18 %), from the new M at 2x (424) and the new O at 1x (74) |
| V4 concept C weight | PASS | frame stroke **3 cells** (was 2), smallest interior gap **2 cells = 2 px at 48**, coverage 21.2 % |
| V5 contact sheets | PASS | `contact-sheet.png` **2220x1632**, 15 elements, all inside (was clipping the light lockup at "OMDROI"); `contact-sheet-wordmark.png` **2008x535**, 14 elements, all inside |

Determinism after the rewrite: two consecutive runs, aggregate `find docs/brand … | sha256sum |
sha256sum` = `b0ea4c41e5633c40cb45b152bcacd55a1acd6507500bc5c7690f771fa6947676` on both (the
round-1 digest above is superseded). Every round-1 output path is regenerated; checks 1-9 plus
the new V-checks all print PASS and `SUMMARY checks failed: none`.

## V1 — the wordmark

All five glyphs redrawn on a **cap height of 10 cells with a 3-cell stroke** (ratio 0.300, inside
the 0.30-0.40 band). Widths: O 9, M 15, D 9, R 9, I 3 — so O, D and R are exactly 0.9 x cap, the M
is wider than the O, and the I is exactly one stroke. Letter gap 2 cells everywhere. Outer corners
are cut by one one-cell step; counters are 3 cells wide and 4 cells tall.

What makes each glyph what it is, in the stepped vocabulary:

- **M** — a solid top with a 3-cell notch, then the V's arms step inward and merge into a single
  centre column that reaches the baseline. Not an H (an H has a mid-height bar and no top notch),
  not an N (no diagonal, and the symmetry is vertical), not a W (the notch is at the top).
- **D** — square left edge (no step at all on the left), chamfered right side, so it cannot be
  confused with the O in the same word.
- **R** — closed bowl over rows 0-4, then a leg that steps right two cells to the baseline: not a
  P (the leg leaves the stem) and not a K (no upper arm).

Metrics: 493 of 750 cells lit (**65.7 %** ink in the bounding box, limit 45 %); 75 cells wide so
the text file is 75 columns; at the README size (lockup scaled to 440 px wide) the thinnest
wordmark stroke is **13.6 px**.

Lockup geometry: 3104x512 units, cap height 320 = **62.5 %** of the icon's 512 (band 55-70 %),
vertically centred (96 units above and below), gap between icon and wordmark **192 units = exactly
two strokes**.

`omdroid-logo.txt` is now half-block art: **5 text rows of 75 columns** (limits 12 and 80), one
character per cell horizontally.

## V2 — concept A

Frame unchanged: stroke 3, one break in the bottom edge (cells x18..24). The head is new:

- a **22 x 10 dome** — flat bottom, two-step shoulders, so it is 2.2:1 on its own and 1.69:1
  including the antenna stubs;
- two 3x3 eye gaps in the lower half (rows 18-20 of a head that spans rows 13-22);
- two **3-cell antenna stubs** whose tips step one cell outward, and which stop 7 cells short of
  the frame's top bar.

The rabbit read came from the old antennae: two 7-cell bars running from the frame's top bar down
into a head that was taller than it was wide. Both are gone. The checker walks the 8-neighbourhood
of every head cell and finds **0** contacts with the frame, with 2 empty cells on the left and
right, 7 above and 6 below.

## V3 — concept B

Rebuilt from the new `O` and `M` bitmaps, but the two glyphs cannot share the square at the same
scale, and the numbers show why:

- both at cap 10 (wordmark scale): 26x10 cells = 180 cells = **7.8 %** of the tile, far under 18 %;
- the M at cap 10 is 15 wide, the O 9 wide. Side by side at 2x: 18 + 2 + 30 = **50 cells** of width
  (square is 32); stacked at 2x: 20 + 2 + 20 = **42 rows**.

So B is the M at 2x (30x20, 424 cells) over the O at wordmark scale (9x10, 74 cells): **498 cells =
21.6 % of the tile**, with both glyphs legible and the M dominant. The two alternatives and their
numbers are in "What I could not meet" below; both are a two-line edit.

## V4 — concept C

The idea is untouched (notch in the top edge, master pane left, two stacked panes right). The frame
went from stroke 2 to **stroke 3** to match A and B, which meant re-cutting the pane area so every
gap stays at 2 cells: gap to the bezel 2, master-to-stack 2, stack-to-bezel 2, and the two stacked
panes separated by 2 rows. The new check measures the smallest interior gap: **2 cells = 2 px at
48 px**. Coverage 21.2 %, thinnest stroke 3 cells = 3 px.

## V5 — contact sheets

The old sheet computed its canvas from the sum of image widths but placed the blocks with 24 px of
padding each and started at `label + margin`, so the last block overran the right edge — that is
the clipped "OMDROI". Both sheets now go through one composer that records every pasted rectangle,
sizes the canvas from the maximum extent, and asserts every element lies inside it; each sheet
prints `elements N all-inside PASS`.

- `docs/brand/contact-sheet.png` — 2220x1632 (round-1 width limit 2400 respected), 3 rows, 5
  elements each: 512 tile, 48 px x4 nearest-neighbour, circular mask, lockup on `#1a1b26`, lockup
  on white.
- `docs/brand/contact-sheet-wordmark.png` — 2008x535: row `WORD` = the wordmark alone at 900 px
  wide on `#1a1b26` and on white; rows `A`, `B`, `C` = that concept's lockup at exactly **440 px**
  (README header size) and **200 px** (badge size), each on both grounds. All labelled.

Standalone wordmark renders are new deliverables: `docs/brand/omdroid-wordmark-dark.svg` and
`-light.svg` (2400x320 units).

## What I could not meet exactly

1. **B has no single-scale composition.** Both glyphs at one scale are either 7.8 % of the tile (cap
   10) or 50 cells wide / 42 rows deep (cap 20). The floor of 18 % and the 32-cell square cannot
   both hold. I chose M-dominant (21.6 %). The O-dominant stack (O at 2x over M at 1x) is 402 cells
   = **17.45 %**, which misses the floor by 0.55 points; one glyph only (M at 2x) is 424 cells =
   **18.4 %**. Say the word and I will switch to whichever you prefer.
2. **The M has 1-cell gaps** between each stem and the arm that leaves it (rows 2-3), because a
   stepped V separates from a vertical stem immediately. At 440 px that gap is 5.9 px against a
   13.6 px stroke, so it holds, but it is the busiest passage in the wordmark. Omarchy's own M
   avoids it by dropping the V and drawing a solid top bar over three stems — I kept the V because
   the brief asked for the M not to read as an H.

## For the parent to judge by eye (round 2)

Open `contact-sheet-wordmark.png` first: the `WORD` row is the piece that changed most, at 900 px
wide on both grounds. Then the 440 px rows — that is the README header, the size that matters. The
200 px rows are the badge test: A and C should survive, B's small O is the risk (it is 9 cells of
32 and sits under a mark twice its size).

- **Wordmark**: I expect the D/O pair to be the reassuring part (they differ only at the left
  corner, as in any slab) and the M the contentious one, per the note above. If the M reads as four
  bars, the fix is the Omarchy arrangement, not a redraw.
- **A**: the dome is wide and low with the eyes low, which should read as a head rather than a
  bottle; the antennae are stubs, not ears. My doubt is that at 48 px the 2-cell step of the antenna
  tips is the thinnest thing in the set (2 px).
- **B**: the M is now the visual anchor and the small O sits under it like a base mark. It is a
  legitimate monogram, but it is not two letters of equal standing, and that is exactly what the
  floor forced.
- **C**: unchanged in idea, heavier in the frame; nothing here should surprise you.

## Round 2 log

- Rewrote `build_brand.py`: new 10-row glyphs, composed A (frame + head as separate bitmaps), B
  built from the glyph cells at 1x/2x, C at stroke 3, `Sheet` composer with bounds checking, new
  V-checks, standalone wordmark SVGs, both sheets.
- First run: CHECK6 failed on `ic_launcher_background.xml` (I had written `xmlns=` where
  `xmlns:android=` was needed, so `android:shape` was an unbound prefix). Fixed the attribute.
- HEAD_A had one garbled row from a counting leftover; fixed before the first run, then read all
  three ASCII previews and the half-block wordmark to confirm the drawings (the M's stems, the R's
  leg, the D's square left edge, A's stub antennae).
- Moved B's M from row 1 to row 0 so the pair spans rows 0-31 with a 2-row gap instead of sitting
  1 row low; coverage unchanged at 21.6 %.
- Determinism re-proved after every edit; final digest `b0ea4c41…` on two consecutive runs.

## New ASCII (round 2)

### Wordmark — `docs/brand/omdroid-logo.txt`, half blocks, 75 columns, 5 lines

```
▄███████▄  ██████   ██████  ███████▄   ███████▄   ▄███████▄  ███  ███████▄ 
███▀▀▀███  ███ ███ ███ ███  ███▀▀▀███  ███   ███  ███▀▀▀███  ███  ███▀▀▀███
███   ███  ███  █████  ███  ███   ███  ███▀███▀   ███   ███  ███  ███   ███
███▄▄▄███  ███   ███   ███  ███▄▄▄███  ███  ███   ███▄▄▄███  ███  ███▄▄▄███
▀███████▀  ███   ███   ███  ███████▀   ███   ███  ▀███████▀  ███  ███████▀ 
```

### Concept A — `docs/brand/concepts/a/ascii.txt`

```
    ████████████████████████████████████████████████████████    
  ████████████████████████████████████████████████████████████  
████████████████████████████████████████████████████████████████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████      ██████                            ██████      ██████
██████      ██████                            ██████      ██████
██████        ██████                        ██████        ██████
██████          ████████████████████████████████          ██████
██████      ████████████████████████████████████████      ██████
██████    ████████████████████████████████████████████    ██████
██████    ████████████████████████████████████████████    ██████
██████    ████████████████████████████████████████████    ██████
██████    ████████      ████████████████      ████████    ██████
██████    ████████      ████████████████      ████████    ██████
██████    ████████      ████████████████      ████████    ██████
██████    ████████████████████████████████████████████    ██████
██████    ████████████████████████████████████████████    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
██████                                                    ██████
████████████████████████████████████              ██████████████
  ██████████████████████████████████              ████████████  
    ████████████████████████████████              ██████████    
```

### Concept B — `docs/brand/concepts/b/ascii.txt`

```
  ████████████████████████            ████████████████████████  
  ████████████████████████            ████████████████████████  
  ████████████████████████            ████████████████████████  
  ████████████████████████            ████████████████████████  
  ████████████    ████████████    ████████████    ████████████  
  ████████████    ████████████    ████████████    ████████████  
  ████████████    ████████████    ████████████    ████████████  
  ████████████    ████████████    ████████████    ████████████  
  ████████████        ████████████████████        ████████████  
  ████████████        ████████████████████        ████████████  
  ████████████        ████████████████████        ████████████  
  ████████████        ████████████████████        ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
  ████████████            ████████████            ████████████  
                                                                
                                                                
                        ██████████████                          
                      ██████████████████                        
                      ██████████████████                        
                      ██████      ██████                        
                      ██████      ██████                        
                      ██████      ██████                        
                      ██████      ██████                        
                      ██████████████████                        
                      ██████████████████                        
                        ██████████████                          
```

### Concept C — `docs/brand/concepts/c/ascii.txt`

```
                                                                
            ████████████████            ████████████            
          ██████████████████            ██████████████          
        ████████████████████            ████████████████        
        ██████                                    ██████        
        ██████                                    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████                  ██████        
        ██████    ██████████████                  ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████    ██████████████    ██████████    ██████        
        ██████                                    ██████        
        ██████                                    ██████        
        ████████████████████████████████████████████████        
          ████████████████████████████████████████████          
            ████████████████████████████████████████            
                                                                
```

## Decision (2026-11-24)

Phil chose **concept A** (Android head in a square frame with the bottom-edge break). It is what
`CHOSEN = "a"` in `build_brand.py` emits as `omdroid-logo-{dark,light}.svg` and `omdroid-icon.svg`,
what the README header shows, and what the app ships: `app/src/main/res/drawable/ic_launcher_{foreground,background,monochrome}.xml`
and `mipmap-anydpi-v26/ic_launcher.xml` are byte-identical to `concepts/a/android/`. Concepts B and
C stay in `concepts/` as the record of what was considered. The constant was renamed from
`PROVISIONAL`; the generator's outputs are byte-identical before and after the rename.
