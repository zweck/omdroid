# R3 device report — does the new host build work on the tablet?

HEAD: `abbd055` (branch `omarchy-embed`) · Device: tablet `<tablet-serial>` only (the `SM_F976B` phone was never touched) · Model that ran: deepseek-flash
APK under test: `0.0.13-alpha` (versionCode 13), sha256 `e465686a…e5360`, `lastUpdateTime 2026-11-24 19:01:12`.
Report created before the first item; every row was written in the call that produced its numbers.

## STATUS TABLE

| Item | Status | Numbers |
| --- | --- | --- |
| D1 what is installed | PASS | `INSTALL: PASS` — apk 1714881981 bytes and `INSTALLED` both sha256 `e465686a7901ebbd5466c18d20bb53b6fdaeb43cd72a8be4ec1f532d841e5360` (`already installed`). `screen.sh show` before any change: Physical size 2136x3200, Override density 352, `accelerometer_rotation=1 user_rotation=0`, no `Override size` line. |
| D2 cold start ×5 | PASS | `coldstart.sh 5 --tag n1` → `SUMMARY tag=n1 runs=5: unaided handover 5/5 (2.9..3.0 s), desktop PASS 5/5 (2.4..2.5 s), runs with a foreign activity on top: 0`; `relaunch=0` on all 5. Per run handover/desktop/pid: 1 3.0/2.5/13924 · 2 3.0/2.5/14593 · 3 3.0/2.5/15049 · 4 2.9/2.4/15553 · 5 3.0/2.4/16023. Activity sequence on every run: `.LauncherActivity` → `com.termux.x11.MainActivity`, nothing else — **the all-files settings page never appeared**. `launch:` ms, `session-started` / `ready` / `handover done`: 1 129/2336/2354 · 2 106/2311/2323 · 3 106/2310/2323 · 4 103/2308/2322 · 5 98/2308/2320; ready reason **`marker` on 5/5** (no fallback, timeout or session_died). One full sequence in §D2. |
| D3 geometry, native screen | PASS (one wording correction) | xroot **3200x1971**, cage-window **3200x1970 @0,0**, monitor **3200x1970@120.00 scale 2.00 → logical 1600x985**, layers **omarchy-background 1600x985**, **omarchy-bar 1600x26**, `VERDICT: PASS`. `XLORIE geometry: 3200x1971 (window 3200x2136, bottom gap 165)` == the real X root. Screenshot `n1-5.png`: row 0 is the bar (flat (24,24,40), **not** black), rows 16–35 VARIED (bar content), 36–51 bar fill, 52–1967 VARIED (desktop), **first fully-black row = 1970** at 1 px precision, key bar 1971–2135 (165 rows). Cage window resized exactly once: `fit-x11: 0x200004 1024x768@0,0 -> 3200x1970@0,0` + `confirmed 3200x1970 after 0.00s`; those lines are in `/tmp/omarchy-session.log` — **`/tmp/fit-x11.log` is 0 bytes** (§D3). No second resize on any run. |
| D4 tapping the icon again | PASS | Two rounds: X display on top **0.0 s** and **0.1 s** after the launcher intent, `VERDICT: PASS` both, app pid **16023 unchanged** in both rounds, `pgrep -c Hyprland` **1 before and after** (no second session), foot window still `class foot title omarchy@localhost:~`. After HOME the top activity was `com.miui.home/.launcher.Launcher` with pid still 16023. |
| D5 size change in background | PASS | `rot 0` (portrait): probe PASS **+5.3 s**, pid **16023 unchanged**, foot still listed, xroot 2136x3035 / cage 2136x3034 / monitor 2136x3034 scale 2 → logical 1068x1517, layers 1068x1517 + 1068x26. `rot 1`: PASS **+5.3 s**, pid 16023, foot listed, back to 3200x1971 / 3200x1970 / 1600x985. Hand-over after the intent 0.0 s / 0.1 s. No restart, no relaunch. |
| D6 size changes in foreground | PASS (one observation) | Foreground, foot open, in order: `rot 0` PASS **+2.7 s** (xroot 2136x3035), `rot 1` PASS **+2.6 s** (3200x1971), `set 1200x1920 240` PASS **+2.7 s** (1920x1035), `reset` PASS **+2.6 s** (3200x1971); pid **16023** through all four, `pgrep -c Hyprland` 1, foot still listed, each state re-probed PASS 4 s later. Observation (not an item failure): on the live density change the X view keeps the launch-time 165 px bottom padding while the key bar is 113 px at 240 dpi → **52 px black strip** (`d6-set240.png`: X content ends row 1033, black 1034–1086, key bar 1087–1199). The next launch recomputes it correctly (D7 small-tablet: bottom gap 113, root 1920x1087). Watch log in §D6. |
| D7 screen-size matrix | PASS | 7/7 rows: handover `auto` (2.6–3.2 s), `relaunch=0`, no foreign activity, `VERDICT: PASS`, prediction == X root, monitor == root rounded down to even, first screenshot band never black. Roots: phone-portrait 1080x2203, phone-landscape 2400x883, small-phone 720x1450, fold-inner 2256x2307, small-tablet 1920x1087, odd 1080x2204, native 3200x1971. Full table in §D7. |
| D8 screen off/on | **BLOCKED by lock screen** (app side healthy) | After `KEYCODE_SLEEP` (`mWakefulness=Dozing`) → 15 s → `KEYCODE_WAKEUP` + `wm dismiss-keyguard` (`mWakefulness=Awake`) the tablet shows its secure keyguard: `isKeyguardShowing=true`, `mDreamingLockscreen=true`, `mCurrentFocus=Window{…u0 NotificationShade}`, `mCurrentFocusedWindow = com.android.systemui`, and its UI is `Enter PIN or use fingerprint to unlock` + `PIN area`. The X display did **not** come back on top, and the launcher intent did not bring it forward either (focus stayed NotificationShade). App side unchanged: pid **28876 before and after**, `probe.sh -q` **VERDICT: PASS**, foot still `class foot title omarchy@localhost:~`. PIN not guessed (brief). |
| D9 keyboard and touch | **BLOCKED by lock screen** (not attempted) | The device is on the PIN keyguard (D8), so any `input text` would land in the keyguard's PIN field — that is a blind PIN guess, which the brief forbids and which risks locking the tablet. Setup was done so the item is one command away once someone unlocks: `/data/local/tmp/read-t.sh` pushed and copied to guest `/tmp/read-t.sh`, sha256 `4c5cb7e21eb75686…` identical on host and guest. Remaining steps in §D9. |

## D2 — one complete `launch:` sequence (run 5 of `--tag n1`)

```
11-24 19:03:35.879 I/Phoshdroid(16023): launch: removed stale /data/user/0/com.phoshdroid.app/files/usr/tmp/proot-24101-8ZsUvO
   (11 identical `launch: removed stale …` lines in that millisecond: leftovers from earlier sessions, all removed before start)
11-24 19:03:35.882 I/Phoshdroid(16023): XLORIE geometry: 3200x1971 (window 3200x2136, bottom gap 165)
11-24 19:03:35.883 I/Phoshdroid(16023): launch: x11-socket exists=true 61ms
11-24 19:03:35.920 I/Phoshdroid(16023): launch: session-started 98ms
11-24 19:03:38.131 I/Phoshdroid(16023): launch: ready marker 2308ms
11-24 19:03:38.143 I/Phoshdroid(16023): launch: handover done 2320ms
```

Notes: the ready phase is **2.2 s after** `session-started`, never 0–1 ms, so it is a marker written by this session, not a survivor (confirmed independently: `omarchy-ready` content `ready 1795547018 3200x1970 scale=2` carried the wall-clock second of that run, 8 s before I read it). `prefix-extracted` / `rootfs-extracted` do not appear on these runs because the prefix and rootfs are already extracted (`.extraction_complete` present) — the r2 host checklist's full six-line sequence only exists on the first launch after a fresh install.

## D3 — probe block, bands, geometry line, fit-x11

Run 5 of `--tag n1` (`probe.sh`):

```
xroot 3200x1971
cage-window 3200x1970 @0,0
monitor WAYLAND-1 3200x1970@120.00 scale 2.00 -> logical 1600x985 transform 0
layer L0 omarchy-background 1600x985
layer L2 omarchy-bar 1600x26
clients 0
ready-marker 'ready 1795547018 3200x1970 scale=2'
VERDICT: PASS
```

`shot.sh` BANDS of `n1-5.png` (4 px grid, as the tool prints them):

```
rows 0-15 FLAT (24, 24, 40)
rows 16-35 VARIED
rows 36-51 FLAT (24, 24, 40)
rows 52-1967 VARIED
rows 1968-1999 FLAT (0, 0, 0)
rows 2024-2083 FLAT (0, 0, 0)
rows 2096-2135 FLAT (0, 0, 0)
```

At **1 px** precision (`rows.py`, same screenshot): the first row that is ≥97 % pure black across the width is **1970**; rows 1960–1969 are wallpaper (modal colour (27,18,82)), rows 1970–2135 are black apart from the key glyphs. So the X view occupies rows 0–1970 with row 1970 being the spare odd row the contract says stays black (root 1971 → cage 1970), and **the key bar is rows 1971–2135 = 165 px exactly as predicted**. No top gap; the bar is the top 52 rows.

Host line: `XLORIE geometry: 3200x1971 (window 3200x2136, bottom gap 165)` — identical to the real X root, so no hand-over resize was needed.

**One wording correction to the brief:** the single cage resize is logged in `/tmp/omarchy-session.log`, not `/tmp/fit-x11.log`:

```
/tmp/omarchy-session.log:102:fit-x11: 0x200004 1024x768@0,0 -> 3200x1970@0,0
/tmp/omarchy-session.log:103:fit-x11: confirmed 3200x1970 after 0.00s
/tmp/omarchy-fit: 3200 1970
/tmp/fit-x11.log: 0 bytes
```

`session-child.sh` runs the one-shot `fit-x11.py --wait 10` first; it does the single resize and prints to the session log. `start.sh`'s long-running poller (`fit-x11.py :0 0.25`, session-log line 80) then has nothing to do, so its log stays empty — which is itself the proof that **no second resize happened**. The claim "one line in fit-x11.log, no second resize" is met as "exactly one resize line on the device, and the poller log has none"; only the file name in the brief is wrong.

## D4 — tapping the icon again (two rounds)

Pre-state: `clients: []`, pid 16023, Hyprland processes 1. `hc.sh '/dispatch hl.dsp.exec_cmd("foot")'` → `ok`; `j/clients` then listed foot (`mapped: true`, `class foot`, `title omarchy@localhost:~`).

| Round | top after HOME (5 s) | hand-over after intent | probe | pid | Hyprland count | foot |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `com.miui.home/.launcher.Launcher`, pid 16023 | **0.0 s** | PASS | 16023 (was 16023) | 1 (was 1) | listed |
| 2 | `com.miui.home/.launcher.Launcher`, pid 16023 | **0.1 s** | PASS | 16023 (was 16023) | 1 (was 1) | listed |

## D5 — size change while in the background

| Step | event | hand-over | probe PASS | pid | foot | geometry |
| --- | --- | --- | --- | --- | --- | --- |
| rot 0 (portrait) | app in background, HOME first | 0.0 s | **+5.3 s** | 16023 (same) | listed | xroot 2136x3035, cage 2136x3034, monitor 2136x3034 scale 2 → 1068x1517, layers 1068x1517 + 1068x26 |
| rot 1 | app in background, HOME first | 0.1 s | **+5.3 s** | 16023 (same) | listed | xroot 3200x1971, cage 3200x1970, monitor 1600x985 logical, layers 1600x985 + 1600x26 |

## D6 — size changes while in the foreground (logged repeat, same session)

| Step | probe PASS | pid | Hyprland count | foot | geometry after |
| --- | --- | --- | --- | --- | --- |
| `rot 0` | **+2.7 s** | 16023 | 1 | listed | xroot 2136x3035 / cage 2136x3034 / logical 1068x1517 |
| `rot 1` | **+2.6 s** | 16023 | 1 | listed | xroot 3200x1971 / cage 3200x1970 / logical 1600x985 |
| `set 1200x1920 240` | **+2.7 s** | 16023 | 1 | listed | xroot 1920x1035 / cage 1920x1034 / logical 960x517 |
| `reset` | **+2.6 s** | 16023 | 1 | listed | xroot 3200x1971 / cage 3200x1970 / logical 1600x985 |

Each state was probed again 4 s later: PASS every time. `/tmp/omarchy-watch.log` tail:

```
19:04:51 monitor 2136x3034 scale 2, logical 1068x1517, X root 2136x3035, layers background=1600x985 bar=1600x26
19:04:52 layers do not fit 2136x3034 (background=1600x985 bar=1600x26, need background=1068x1517 bar width 1068): attempt 1/3
19:04:55 act: omarchy-restart-shell rc=0
19:04:55 act: layers fit again 0.1 s after the restart
19:05:02 monitor 3200x1970 scale 2, logical 1600x985, X root 3200x1971, layers background=1068x1517 bar=1068x26
19:05:03 layers do not fit 3200x1970 (...): attempt 1/3
19:05:05 act: omarchy-restart-shell rc=0
19:05:05 act: layers fit again 0.1 s after the restart
19:05:18 monitor 2136x3034 … 19:05:22 act: layers fit again 0.1 s after the restart
19:05:29 monitor 3200x1970 … 19:05:33 act: layers fit again 0.1 s after the restart
19:05:39 monitor 1920x1034 scale 2, logical 960x517, X root 1920x1035 … 19:05:42 act: layers fit again 0.1 s after the restart
19:05:50 monitor 3200x1970 … 19:05:53 act: layers fit again 0.1 s after the restart
```

The guest did exactly what the contract says: one shell restart per size change, layers back 0.1 s later, no session restart (pid constant).

## D7 — screen-size matrix (one cold start per row)

| Row | set / rot | handover | desktop | relaunch | foreign | `XLORIE geometry` (gap) | real xroot | monitor (scale → logical) | layers | verdict | first band black? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| phone-portrait | `1080x2400 420`, rot 0 | 2.9 s auto | 3.3 s | 0 | none | 1080x2203 (197) | 1080x2203 | 1080x2202 @2 → 540x1101 | 540x1101 + 540x26 | PASS | no (0–15 bar) |
| phone-landscape | same, rot 1 | 2.7 s auto | 3.1 s | 0 | none | 2400x883 (197) | 2400x883 | 2400x882 @2 → 1200x441 | 1200x441 + 1200x26 | PASS | no (0–15 bar) |
| small-phone | `720x1600 320`, rot 0 | 2.6 s auto | 2.1 s | 0 | none | 720x1450 (150) | 720x1450 | 720x1450 @2 → 360x725 | 360x725 + 360x26 | PASS | no (0–11 bar) |
| fold-inner | `2256x2504 420`, rot 0 | 2.9 s auto | 2.4 s | 0 | none | 2256x2307 (197) | 2256x2307 | 2256x2306 @2 → 1128x1153 | 1128x1153 + 1128x26 | PASS | no (0–23 bar) |
| small-tablet | `1200x1920 240`, rot 1 | 2.9 s auto | 2.4 s | 0 | none | 1920x1087 (113) | 1920x1087 | 1920x1086 @2 → 960x543 | 960x543 + 960x26 | PASS | no (0–15 bar) |
| odd | `1080x2401 420`, rot 0 | 2.7 s auto | 2.1 s | 0 | none | 1080x2204 (197) | 1080x2204 | 1080x2204 @2 → 540x1102 | 540x1102 + 540x26 | PASS | no (0–15 bar) |
| native | `reset` (accel on) | 3.2 s auto | 2.6 s | 0 | none | 3200x1971 (165) | 3200x1971 | 3200x1970 @2 → 1600x985 | 1600x985 + 1600x26 | PASS | no (0–15 bar) |

Every row: prediction == X root, monitor == root rounded down to even, marker written by the session, `VERDICT: PASS`, ready reason `marker`. The parity path was exercised on the native row (odd 1971 → 1970); the `odd` row itself came out even (2401 − 197 = 2204), so it did not add a second odd case.

## D8 — screen off and on (blocked)

```
before: pid=28876 top=com.phoshdroid.app/com.termux.x11.MainActivity hypr_count=1  clients: foot
KEYCODE_SLEEP → mWakefulness=Dozing
15 s → KEYCODE_WAKEUP + wm dismiss-keyguard → mWakefulness=Awake
after wake: top=<empty> pid=28876 (was 28876)
            VERDICT: PASS   clients: class foot title omarchy@localhost:~
            isKeyguardShowing=true  mDreamingLockscreen=true
            mCurrentFocus=Window{ee1ac79 u0 NotificationShade}  mCurrentFocusedWindow = com.android.systemui
uiautomator: "Enter PIN or use fingerprint to unlock" / com.android.keyguard.PasswordTextView
launcher intent sent again → focus stayed NotificationShade, X display did not come back on top
```

The app side survived the screen cycle untouched (same pid, probe PASS, foot open). What is blocked is only the "X display on top" half: the tablet has a secure PIN keyguard and `wm dismiss-keyguard` cannot clear a secure one. PIN not guessed, per the brief.

## D9 — keyboard and touch (blocked by the lock screen)

Not attempted: the device is on the PIN keyguard, so `input text hello` and the taps would go to the keyguard's PIN field. Nothing was typed, so no failed unlock attempt was recorded against the tablet.

Setup is in place, so the item is one command away once the tablet is unlocked (and the launcher intent brings the X display back):

1. `./hc.sh '/dispatch hl.dsp.exec_cmd("foot bash /tmp/read-t.sh")'` — the script is already in guest `/tmp` (`/data/local/tmp/read-t.sh` pushed, `cp` into `files/usr/tmp/read-t.sh`; sha256 `4c5cb7e21eb75686…` identical host and guest, 60 bytes).
2. `adb -s <tablet-serial> shell input text hello` then `input keyevent KEYCODE_ENTER`; `./gx.sh 'cat /tmp/typed.txt'` must print `hello`.
3. `adb -s <tablet-serial> shell input tap 1600 1000`; `./sx.sh 'hyprctl -j cursorpos'` must read (800, 500) ± 4; second point `input tap 400 300` → (200, 150).

For reference, the harness that would measure it is `~/.hermes/cache/scratch/omarchy/r3/` (r3 scratch), and the two commands above are exactly what the brief specifies.

## Failures, exactly

**No functional failure found in D1–D7.** Every claim in "What the new build should do" was reproduced on the device:

1. *Cold start goes splash → X with no other screen, and never the all-files page* — 5/5 runs, activity list per run is `LauncherActivity` → `MainActivity` only (D2), and the same on all 7 matrix rows (D7).
2. *The launcher waits for `/run/user/1000/omarchy-ready` and only then brings the X display forward; `launch:` lines carry ms* — 5/5 ready reason `marker`, ready 2.2 s after `session-started`, hand-over 12–18 ms after the marker (D2, §D2).
3. *No top gap, X at the top, key bar 165 px below it, root 3200x1971, guest 3200x1970, scale 2, logical 1600x985, layers 1600x985 / 1600x26, bar = top 52 rows, key bar rows 1971–2135* — every number reproduced exactly (D3).
4. *`XLORIE geometry` equals the real root, one guest resize* — equal on **12/12** measured launches (5 in D2 + 7 in D7); exactly one resize line on the device in the run inspected (D3, D7).
5. *Rotation or size change never kills the process or restarts the session; `probe.sh` PASS within 15 s* — pid constant across all 6 size events (D5, D6), Hyprland process count constant, foot survived every one of them, PASS in 2.6–5.3 s.
6. *UI scale picked automatically* — scale 2 on all 7 matrix rows.

Two findings, neither a host code fault, both recorded for completeness:

- **F1 (brief wording, not a bug): the one resize line is not in `/tmp/fit-x11.log`.** `fit-x11.log` is 0 bytes; the single resize + `confirmed` lines are in `/tmp/omarchy-session.log` (lines 102–103) because `session-child.sh`'s one-shot `fit-x11.py --wait` performs it first and the poller started by `start.sh` therefore never has to act. The claim it "breaks" is only the file name in this brief; the behaviour (exactly one resize, no second) is correct and the empty poller log is the evidence. Fault: neither side — the two tools' logging is correct, the brief described the old baseline's single-helper arrangement (r2-guest-report G2).
- **F2 (host side, cosmetic, live density change only): the bottom gap is not recomputed when the density changes while the app runs.** After `./screen.sh set 1200x1920 240` in the foreground the window became 1920x1200 but the X view kept the 165 px bottom padding from launch, so `XLORIE` root = 1920x1035 while the key bar at 240 dpi is 113 px → **52 px of black between the desktop and the key bar** (`d6-set240.png`: content ends row 1033, black 1034–1086, bar 1087–1199). A fresh launch at the same emulated screen gets it right (D7 small-tablet: gap 113, root 1920x1087), so it is "stale until the next launch". Which claim it touches: "below it sits only the Termux:X11 extra-key bar, 165 px on this tablet" holds for the native screen and for rotation, but not for a live *density* change. Host-side by nature (the padding is passed once per hand-over; contract §1 computes the gap at hand-over only), and the guest cannot correct a padding it does not own. Not an item failure: D6's criteria (PASS within 15 s, pid unchanged) are met. Suggested host-side follow-up (not done here, host is out of bounds): recompute the padding on the MainActivity configuration change, or accept it and document that density changes need a relaunch.

**D8 and D9 are blocked, not failed**: the tablet's secure PIN keyguard came up on wake and cannot be dismissed over adb without the PIN. The app and guest session were unaffected (same pid, `VERDICT: PASS`, foot window open). No host-side fix is implied by this.

## Changes

None. No tool, guest file, host file, contract or brief was modified (`git status` shows only my new report file added to the working tree). The only writes outside the report are scratch files under `~/.hermes/cache/scratch/omarchy/` (harnesses `harness.sh`, `d4.sh`, `d5.sh`, `d6.sh`, `d7.sh`, `d8.sh`, `rows.py`, `read-t.sh`, logs `d2-coldstart-n1.log`, `d6.log`, `d7-*.log`) and the D9 setup file `/data/local/tmp/read-t.sh` + guest `files/usr/tmp/read-t.sh` (sha256 `4c5cb7e21eb75686…` on both sides, 60 bytes — this is D9 setup the brief asks for, not a guest fix).

## Screenshots for the parent to view

In `~/.hermes/cache/scratch/omarchy/shots/` (each has a `-bar.png` full-resolution top strip):

| File | What it should show |
| --- | --- |
| `n1-1.png` … `n1-5.png` | Native cold starts (D2/D3): Omarchy bar at row 0 with clock/workspaces, wallpaper, **no black band at the top**, black key bar at the bottom 165 rows with `ESC CTRL ALT …` keys. `n1-5.png` is the D3 measurement. |
| `m-phone-portrait-1.png` | 1080x2400 portrait: narrow phone desktop, bar at row 0, key bar under it, no gap. |
| `m-phone-landscape-1.png` | 2400x883: short landscape phone, keyboard bar taking a large share of the height (197 of 1080 px), desktop 2400x882. |
| `m-small-phone-1.png` | 720x1600: smallest desktop measured (360x725 logical). |
| `m-fold-inner-1.png` | 2256x2504: the fold's inner screen, nearly square desktop 1128x1153. |
| `m-small-tablet-1.png` | 1200x1920 @240: the *correct* 113 px gap (contrast with `d6-set240.png`). |
| `m-odd-1.png` | 1080x2401: tall odd source size, root 1080x2204. |
| `m-native-1.png` | Native after `reset` — should match `n1-5.png`. |
| `d6-set240.png` | **F2 evidence**: 1920x1200 with the 52 px black strip between the desktop and the key bar. |

## Not verified

- **D8's "X display on top" half and all of D9** (keyboard, touch, cursor mapping) — blocked by the PIN keyguard. Everything else in D8/D9 that does not need the unlock was measured (pid, probe, foot, script placement).
- **Scale 1 (auto)**: no matrix row qualifies for it (every row has density ≥ 240 dpi and shorter edge / 2 ≥ 360), so only the unit-tested branch exists for it. The device showed scale 2 on 7/7 rows.
- **The `--nudge`-free hand-over with an old guest image** (ready fallback / timeout paths): the installed guest writes the marker, so the fallback was never on the path. Ready reason was `marker` on all 12 launches.
- **Extra-key row count other than 2**: not touched (the host falls back to 2 rows on a cold launcher start, per r2-host-report's known risk); the bar measured 165 px = 2 rows at this density on every run.
- **Post-install first launch** (`prefix-extracted` / `rootfs-extracted` lines): the prefix and rootfs were already extracted on the tablet; not re-exercised (would require deleting the extraction, which the brief forbids).
- **A second display (HDMI/cast)**: not tested.

## `./screen.sh show` after the final reset

```
Physical size: 2136x3200
Physical density: 440
Override density: 352
accelerometer_rotation=1 user_rotation=0
cur=3200x2136 app=3200x2136
original (restored by reset): ORIG_SIZE= ORIG_DENSITY=352 ORIG_ACCEL=1 ORIG_ROT=0
```

No `Override size` line, `accelerometer_rotation=1`, `Override density: 352`, physical size back — the tablet is left as it was found. The only lasting difference from before this run is the PIN keyguard being up (it will need Phil's PIN or fingerprint) and the app restored to its normal native state (a session is running at 3200x1971 with `VERDICT: PASS`).
