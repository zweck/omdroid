# R2 guest report
HEAD: abbd055   Device: <tablet-serial>   Model that ran: deepseek-flash

## STATUS TABLE
| Item | Status | Proof (numbers, command, log line) |
| G1 baseline | PASS | `coldstart.sh 3 --nudge --tag base`: desktop PASS **3/3** at +9.0 s, +9.5 s, +10.2 s (unaided hand-over 2/3). Every run xroot=cage=monitor 3200x1776@120, scale 2, logical 1600x888, layers L0 1600x888 + L2 1600x26, `VERDICT: PASS`, BANDS desktop rows 192-1915 VARIED. |
| G2 deterministic start | PASS | The `sleep 6` was pure delay: a boot with **no wait at all** gave desktop PASS at **+2.8 s** (vs 9.0-10.2 s in G1). Shipped form is the bounded wait: `session-child.sh` runs `fit-x11.py --wait 10 "$DISPLAY"`, which resizes cage's window to the root (each side rounded DOWN to even), confirms the geometry and returns (measured `fit-x11: confirmed 3200x1776 after 0.00s`). Cold starts with the shipped files: **2.9, 3.0, 3.1, 3.2, 3.8, 3.8, 3.8, 3.9 s**; the 5-run `--tag g2` set measured 2.7..5.6 s but with the one-shot failing (`Permission denied`, `python3` fix landed after it), i.e. still no wait, so the shipped path's numbers are the 8 boots above. |
| G3 ready marker | PASS | `omarchy-watch.py` (new, started by `start.sh` before the `exec`) writes `$XDG_RUNTIME_DIR/omarchy-ready` atomically. Boot at 18:19:03: watcher 18:19:06 `ready 3200x1776 scale=2 written`, probe `ready-marker 'ready 1795544346 3200x1776 scale=2'`, i.e. **marker present when the desktop PASSes (+3.0 s)**, and the marker size equals `probe.sh`'s monitor on every run (all 5 final runs + the 7 G5 rows + both G6 boots). `start.sh` removes the stale marker before cage (`rm -f "$XDG_RUNTIME_DIR/omarchy-ready"`). Negative: with the plugin index absent **and** the stale scan file removed, the shell loads zero plugins -> only the bar surface exists, `VERDICT: FAIL no omarchy-background layer`, desktop NEVER over 120 s and **no marker file** (`ls: cannot access '/run/user/1000/omarchy-ready'`); index restored afterwards. |
| G4 live size changes | PASS | Reproduced first: session up, X display on top, one `foot` window; `screen.sh rot 0` -> xroot/cage 2136x2840, monitor 2136x2840, layers stale **1600x888 / 1600x26** -> `VERDICT: FAIL background layer 1600x888 != logical 1068x1420 (stale after a resize)`. Final proof (no in-session command, `g4-live.sh`): `rot 0` PASS **+2.9 s**, `rot 1` PASS **+2.3 s**, `rot 0` PASS **+2.8 s**, `rot 1` PASS **+3.0 s**, `set 1200x1920 240` PASS **+1.9 s**, `reset` PASS **+1.9 s**; `j/clients` still `class foot title omarchy@localhost:~`. Watcher log per step: `monitor 2136x2840 ... layers background=1600x888` -> `layers do not fit ... attempt 1/3` -> `act: omarchy-restart-shell rc=0` -> `layers fit again`. Remedy cost measured separately: restart-shell returns rc=0 and the background layer is back **2.4 s** later. |
| G5 size matrix | PASS | 7/7 cold starts PASS with no in-session help (table below). |
| G6 scales | PASS (informational) | `OMARCHY_SCALE=1`: monitor reports **scale 1.00**, logical 3200x1776, PASS, marker `scale=1`, bar band (rows 140-166, 26 px) distinct 249 INK **1.21 %**. `OMARCHY_SCALE=1.5`: monitor reports **scale 1.60** (not 1.5), logical 2000x1110, PASS, marker `scale=1.6`, bar band (rows 140-182) distinct 599 INK **4.01 %**. Native scale 2 for comparison: bar band 140-192 distinct 309 INK **1.86 %**. `shot.sh`'s fixed BAR band reads 0.00 % in every old-APK boot because its 52 top rows are the APK's black 140 px top gap (bar sits at rows 140-191), so the band figures above are measured on the real bar rows. Nothing permanent changed. |
| G7 bake + clean + final | PASS | Overlay + packager carry every file (table below). Device `sha256sum /etc/phoshdroid/*` equals the repo for all five: start.sh `fe3f35a9…f5cc`, session-child.sh `c6ca9997…0789`, omarchy-plugin-index.sh `aa979217…1fd6`, fit-x11.py `0aaddd91…e13a`, omarchy-watch.py `28aba83d…c93e`. `/etc/phoshdroid` holds only the shipped files (+ `fake-proc`, `guest-packages.txt`); `__pycache__` and my guest `/tmp` scratch removed. Screen reset+show at the end. `coldstart.sh 5 --nudge --tag final`: desktop PASS **5/5 at 3.1, 3.8, 3.8, 3.8, 3.9 s**, unaided hand-over **5/5 (0.9..1.1 s)**, marker present on every run. |

### G5 rows (all cold starts, xroot / monitor / logical / verdict / desktop)
| Row | set + rot | xroot | monitor (scale) | logical | layers | verdict | desktop |
| phone-portrait | `1080x2400 420`, rot 0 | 1080x2040 | 1080x2040 (2) | 540x1020 | 540x1020 + 540x26 | PASS | +10.1 s |
| phone-landscape | `1080x2400 420`, rot 1 | 2400x720 | 2400x720 (2) | 1200x360 | 1200x360 + 1200x26 | PASS | +10.5 s |
| small-phone | `720x1600 320`, rot 0 | 720x1240 | 720x1240 (2) | 360x620 | 360x620 + 360x26 | PASS | +10.3 s |
| fold-inner | `2256x2504 420`, rot 0 | 2256x2144 | 2256x2144 (2) | 1128x1072 | 1128x1072 + 1128x26 | PASS | +9.7 s |
| small-tablet | `1200x1920 240`, rot 1 | 1920x840 | 1920x840 (2) | 960x420 | 960x420 + 960x26 | PASS | +10.4 s |
| odd | `1080x2401 420`, rot 0 | **1080x2041** | **1080x2040** (2) | 540x1020 | 540x1020 + 540x26 | PASS | +10.5 s |
| native | `reset`, rot auto | 3200x1776 | 3200x1776 (2) | 1600x888 | 1600x888 + 1600x26 | PASS | +9.7 s |

Row notes: every row carried `relaunch=1` (the old APK kills itself once after a display-size change, `coldstart.sh` relaunches it — expected per the brief). Values are `height - 360` in portrait because of the old APK's 160+200 px paddings. The odd row is the parity proof: X root **2041** -> cage/monitor **2040**, logical integral, marker `1080x2040`, PASS. BANDS for native: rows 0-139 FLAT (0,0,0), 140-155 bar, 156-171 VARIED, 172-191 bar, 192-1915 VARIED desktop, 1916+ black.

## Contract: anything that is wrong or that you could not meet (with evidence), else "met"

Three points, all measured; the rest is met.

1. **§2 "one enabled monitor whose size equals the X root" cannot hold on an odd root** unless §1's parity rule is applied first: cage's window is the root rounded down to even, so the monitor is one pixel smaller than the root. The watcher therefore compares the monitor against the **even-rounded root** (`target = root & ~1`). Evidence: G5 `odd`, X root 1080x2041, monitor 1080x2040, marker `1080x2040`.
2. **§4 fractional scale: this Hyprland does not accept 1.5.** `OMARCHY_SCALE=1.5` produced `scale 1.60`, logical 2000x1110 (not 2133x1184); `1` and `2` are exact. Keep the host to integers.
3. **§5.1 "host wipes `<prefix>/tmp/omarchy-runtime/`" is load-bearing, not hygiene.** First negative test (index script absent, runtime dir untouched by the old APK): the shell still had its plugins, because `$XDG_RUNTIME_DIR/omarchy-plugin-scan.txt` survived from the previous session, so the desktop came up and the marker was written. Only after removing that file did the negative case behave (`VERDICT: FAIL no omarchy-background layer`, no marker). The new host must wipe it.

## Root causes (one paragraph each, with the number or log line that proves it)

1. **The `sleep 6` in `session-child.sh` was pure delay.** With no wait at all the desktop is correct at **+2.8 s** (baseline 9.0-10.2 s), and the log shows why: the poller resizes cage's window (`fit-x11: 0x200004 1024x768@0,0 -> 3200x1776@0,0`) while cage is still starting, and Hyprland accepts the later ConfigureNotify without starving. The fix keeps a **bounded** wait (`fit-x11.py --wait 10`, measured `confirmed 3200x1776 after 0.00s`) as cheap insurance and starts Hyprland immediately.

2. **The host's geometry hint must not become Hyprland's mode.** `monitors.lua` turns `OMARCHY_OUTPUT_WIDTH/HEIGHT/REFRESH_MHZ` into a forced mode; with it, after a live root change `hyprctl reload` re-applied the stale mode and the monitor jumped to **3200x1776 while cage was 2136x2840** (`VERDICT: FAIL Hyprland monitor 3200x1776 != cage window 2136x2840`). With the new APK the hint will be odd (3200x**1971**) while cage's window must be 3200x**1970**, so a forced mode from the hint would disagree with the parent from the first frame. The guest now unsets the hint before Hyprland starts and hands it the size cage **really has** (`fit-x11.py --wait` writes `/tmp/omarchy-fit`, `session-child.sh` exports it into `OMARCHY_OUTPUT_*`); the refresh hint is kept, so the monitor stays at **120 Hz** (measured; `preferred` instead gave **60 Hz** — 60.00 in the monitor line of the boot that dropped the mode).

3. **The shell's layer surfaces do not follow a monitor resize; only a shell restart moves them.** A 12 s in-session sample after a rotation showed the background/bar layers unchanged at 1068x1420 while the monitor was 3200x1776 (and they were still stale 25 s after the change with the watcher's action disabled), and `hyprctl reload` did not move them either. `omarchy-restart-shell` did: rc=0, background layer back at the right size **2.4 s** later (measured in-session). `Background.qml` only exposes `background.refresh()` (image) over IPC, so no cheaper nudge exists. That is what the watcher uses: stable 1.5 s + layers do not fit -> one restart, log, wait up to 8 s for the layers, at most 6 restarts per rolling minute and 2 s apart.

4. **One boot took 66 s because the one-shot resize inherited `DISPLAY=:1`.** `omarchy-session.log` shows `fit-x11: cannot open :1` followed by Hyprland's banner, and the watcher's own log dates Hyprland's first instance dir 62 s after session start: `XOpenDisplay` retried 300 x 0.2 s before giving up. `:1` is the display Hyprland's Xwayland starts on; `start.sh` now exports `PHOSHDROID_XDISPLAY=:0` and `session-child.sh` passes that, and the one-shot's open retry is 40 x 0.05 s = 2 s. Boots after the fix: 2.9-3.9 s.

## Changes (repo file -> guest path -> packager line)

| Repo file | Guest path | Installed by |
| --- | --- | --- |
| `rootfs/overlay/etc/phoshdroid/fit-x11.py` (changed) | `/etc/phoshdroid/fit-x11.py` | `package-omarchy-rootfs.sh` line 71 (`cp -a`), existing |
| `rootfs/overlay/etc/phoshdroid/session-child.sh` (changed: no fixed sleep, bounded wait, derives the mode from the measured root) | `/etc/phoshdroid/session-child.sh` | line 69 (`cp -a`) + `chmod 755` line 73 |
| `rootfs/overlay/etc/phoshdroid/start.sh` (changed: poll 0.25 s, stale-marker removal, watcher start, unset of the size hints, `PHOSHDROID_XDISPLAY`) | `/etc/phoshdroid/start.sh` | line 68 (`cp -a`) + `chmod 755` line 73 |
| `rootfs/overlay/etc/phoshdroid/omarchy-watch.py` (**new**: ready marker + layer-fit watcher) | `/etc/phoshdroid/omarchy-watch.py` | line 72 (new `cp -a`) + `chmod 755` line 74 |
| `rootfs/package-omarchy-rootfs.sh` | (packager) | the two lines above |

`fit-x11.py` changes: parity (`even()` = root & ~1 applied to every resize), `--wait [max] [display]` one-shot (resize, confirm, write `<w> <h>` to `/tmp/omarchy-fit`, exit 0/1 on the cap), poll interval argument kept.

## What the host side must know (anything the new APK has to do or avoid)

- **Marker**: `$XDG_RUNTIME_DIR/omarchy-ready`, one line `ready <unix-seconds> <w>x<h> scale=<s>`, monitor pixels (both even). `scale` prints as `2` / `1` (integers) and `1.6` for the quantised 1.5 case; parse it as a float. Written once per session, ~1-2 s after Hyprland answers and at the same moment the desktop first passes; not rewritten on a later resize (contract §2).
- **Wipe `<prefix>/tmp/omarchy-runtime/` before the session.** Without it a stale `omarchy-plugin-scan.txt` makes the shell load plugins from the previous session (proven above).
- `OMARCHY_OUTPUT_WIDTH/HEIGHT` are now **ignored** by the guest (unset before Hyprland; the mode is derived from the measured X root). Keep passing them if you like; do not expect them to size anything. `OMARCHY_REFRESH_MHZ` **is** still used (it is what keeps 120 Hz) and `OMARCHY_SCALE` must be `1` or `2`.
- A live size change recovers by **restarting the shell** (wallpaper/bar blink for ~2.4 s). That is expected, not a crash.
- Desktop first-pass timing on this tablet: **3.1-3.9 s** cold (was 9-10 s). Hand-over was unaided 5/5 with the old APK nudge in place for the probe half only — hand-over itself is the host's item.
- The old APK's 140 px top gap is why `shot.sh`'s fixed BAR band reports 0.00 %; with the new APK (no top gap) the bar will be the top 52 rows and that band becomes meaningful again.
- If the session is started with an **odd** X height (1971), expect the last pixel row to stay black (parity rule) and the monitor/marker to read one pixel less.

## Screenshots for the parent to view (path + what it should show)

All under `~/.hermes/cache/scratch/omarchy/shots/`:
- `final-1.png` … `final-5.png` — the five final cold starts: black top gap, bar with workspaces/clock/icons at rows 140-191, wallpaper to row 1915, extra-key row at the bottom.
- `m-odd-1.png` — the odd size (1080x2401 -> X root 2041, cage 2040): full desktop, no flat band.
- `m-phone-portrait-1.png`, `m-phone-landscape-1.png`, `m-small-phone-1.png`, `m-fold-inner-1.png`, `m-small-tablet-1.png`, `m-native-1.png` — the rest of the matrix.
- `g6-s1b-1.png` (scale 1, everything half the size), `g6-s15-1.png` (scale 1.6).
- `base-1.png` (G1 baseline), `g2-nowait-1.png` (no-wait boot).
- `g3-neg2` left no screenshot worth viewing (black desktop by design); its evidence is the probe/`ls` output in the table.

## Not verified / left for the parent

- The **new APK**: hand-over by marker, the no-top-gap/padding geometry (1971 odd height), and the live resize path when the app comes back from the background (the old APK restarts the whole session there, so it could not be exercised).
- Touch/keyboard were not retested this round (round 1 passed them; nothing in these changes touches input).
- `omarchy-restart-shell` running from the watcher is a `hyprctl dispatch` of `omarchy-launch-shell`; it inherits Hyprland's environment, which is why the new shell comes up with the same `OMARCHY_*` values. Verified by the layers coming back at the new size, not by reading the new shell's env.
- The 5-run `--tag g2` set ran while the one-shot was still `Permission denied` (see G2); the shipped path is covered by the 8 later boots instead.
- `rootfs.bin`/APK were not rebuilt and `package-omarchy-rootfs.sh` was not run (out of scope by the brief).

## Log (short, appended as you go)
- G1: probe PASS at 3200x1776; 3 cold starts PASS 9.0/9.5/10.2 s.
- G2: no-wait boot +2.8 s -> sleep 6 is pure delay; bounded wait shipped. A boot with the one-shot at 66 s exposed `fit-x11: cannot open :1` (inherited Xwayland DISPLAY) -> fixed with `PHOSHDROID_XDISPLAY` + 2 s open retry; boots 2.9-3.9 s after that.
- G3: watcher writes the marker 1 s after Hyprland answers, before the probe's PASS; negative (no plugin index + no stale scan file) writes no marker.
- G4: reproduced the stale layer (1600x888 vs logical 1068x1420). `hyprctl reload` re-applied the start mode and made the monitor disagree with cage -> not the remedy. `omarchy-restart-shell` moves the layers (2.4 s). Watcher acts once per size change, capped, rate limited. Final sequence: 2.9/2.3/2.8/3.0/1.9/1.9 s PASS, foot still open.
- G5: 7/7 PASS incl. the odd 2041 -> 2040 parity row; screen reset afterwards.
- G6: scale 1 -> 1.00 (3200x1776), 1.5 -> 1.60 (2000x1110); both PASS; real start.sh pushed back (sha match).
- G7: hashes match, device cleaned, screen reset+show, final 5/5 at 3.1..3.9 s with the marker on every run.
