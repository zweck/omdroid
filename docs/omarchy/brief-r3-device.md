# Brief R3-DEVICE: prove the new build on the tablet (launch, geometry, screen sizes)

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`. You cannot view images: judge with the numbers the tools print and
list the screenshots for the parent (it can see them).

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`, read `AGENTS.md`).
Tools: `docs/omarchy/tools/` (run them from that directory).
Background, read once and do not edit: `docs/omarchy/launch-contract.md`,
`docs/omarchy/r2-host-report.md` (what the new app does), `docs/omarchy/r2-guest-report.md`
(what the guest does, and the numbers measured with the OLD app).
Your report, created in your FIRST calls and updated after EVERY item:
`docs/omarchy/r3-device-report.md`. Start it with a STATUS TABLE, one row per item (D1..D9),
`PENDING` until the item has its numbers, then `PASS` / `FAIL` plus the numbers. Write a row in
the same call that produced its numbers: a run that stops early must leave every finished row
in the file. The table is the deliverable.

## Goal

Phil asked for an app that launches predictably and works on different screen sizes. Round 2
rewrote the host app (Kotlin) and the guest session scripts, and both were proven separately:
the guest on the tablet with the OLD app, the host only by unit tests. The parent has now built
and installed the first APK that contains both. **Nothing in the new host code has run on a
device before.** Your job is to find out, with numbers, whether it works, and to hand back an
exact description of every failure. You fix nothing on the host side.

## What the new build should do (each line is a claim to test, not a fact)

- A cold start goes splash -> X display with no other screen in between. In particular the
  Android "all files access" settings page must NOT appear (the old app opened it every time).
- The launcher waits for the guest's ready marker (`/run/user/1000/omarchy-ready`) and only then
  brings the X display (`com.termux.x11.MainActivity`) forward. Logcat tag `Phoshdroid`, lines
  starting `launch:`, carry each phase with milliseconds since the launcher was created.
- No top gap: the X view starts at the top of the screen, and below it sits only the Termux:X11
  extra-key bar, 165 px on this tablet. Predicted X root in landscape: **3200x1971**. The guest
  rounds each side down to even for cage and the monitor: **3200x1970**, scale 2, logical
  1600x985, layers `omarchy-background` 1600x985 and `omarchy-bar` 1600x26. The Omarchy bar is
  the top 52 pixel rows of the screenshot; rows 1971-2135 are the key bar.
- The host prediction is logged as `XLORIE geometry: <w>x<h> (window <W>x<H>, bottom gap <g>)`.
  It should equal the real X root, so the guest resizes cage's window exactly once
  (`/tmp/fit-x11.log`: one line `1024x768@0,0 -> 3200x1970@0,0`, no second resize).
- A rotation or size change while the app runs, or while it is in the background, never kills
  the app process and never restarts the session: the app pid stays the same, open windows stay
  open, and `./probe.sh` is back to `VERDICT: PASS` within 15 s (the guest restarts only its
  shell, so wallpaper and bar blink for 2-3 s; that is expected).
- UI scale is picked automatically: 2 when density >= 240 dpi and the shorter screen edge / 2 is
  at least 360, else 1.

## Hard rules

- Device: ONLY the tablet, serial `<tablet-serial>`; every adb call carries `-s <tablet-serial>` (the tools do
  it for you). A phone (`SM_F976B`) may be attached too: never touch it.
- NEVER `adb uninstall`, `adb install`, `pm clear`, or delete under `files/usr`. The parent
  installed the build; `./install.sh` only confirms it (it prints `already installed` when the
  installed APK has the same sha256 as the local one). If it starts pushing instead, let it finish.
- NEVER run gradle or the packager. NEVER edit `app/**`, `termux-x11/**`, `termux-app/**`, the
  contract or other agents' reports and briefs. A sibling agent is editing `app/**` right now;
  its work is not in the installed build and is none of your concern.
- You may change `docs/omarchy/tools/**` (fix or extend a tool; say what and why in the report)
  and your report. You may change `rootfs/overlay/etc/phoshdroid/**` ONLY to fix a guest-side
  fault you have proven with numbers; then push the same file to the device (`./gpush.sh`),
  re-run the failing item, and show in the report that device and repo hashes match. If in
  doubt whether a fault is host or guest, do not fix it: describe it.
- No git command that changes anything (no add, commit, stash, checkout, restore, clean).
- **Screen overrides are Phil's tablet settings.** After ANY `./screen.sh set` or `rot`, and
  before you finish or stop for any reason, run `./screen.sh reset` and confirm `./screen.sh show`
  prints `Physical size: 2136x3200`, `Override density: 352`, `accelerometer_rotation=1` and no
  `Override size` line. Put that output in the report.
- Never `pkill -f` / `pgrep -f ... | kill` on this machine.
- If a terminal command is BLOCKED waiting for approval, do not retry or rephrase it: note it in
  the report and go on with the next item.
- Do not spend calls on `sleep N; tail`. The tools wait by themselves; give long ones a 600 s
  timeout.
- Scratch: `~/.hermes/cache/scratch/omarchy/r3/`. Screenshots land in
  `~/.hermes/cache/scratch/omarchy/shots/`.

## Tools (all default to the tablet)

| Tool | Use |
| --- | --- |
| `./install.sh` | Confirms which APK is installed (sha256 against the local build). |
| `./probe.sh [-q]` | THE check. X root, cage window, monitor (pixels, scale, logical), layers, clients, ready marker, then `VERDICT: PASS` or `VERDICT: FAIL <which link is wrong>`. About 2 s. |
| `./coldstart.sh [runs] --tag <name>` | Cold start like the launcher icon. Per run: `handover` (seconds until the X display is on top; `auto` = the app did it itself), `desktop` (first probe PASS), `relaunch` (1 = the app process died within 6 s and the script had to start it again: must be 0 now), `pid`, every activity seen on top and whether a foreign one appeared, the `launch:` log lines, the full probe, screenshot numbers. `SUMMARY` line at the end. 60 s per run: more than 2 runs need a 600 s timeout. Do NOT pass `--nudge` (that was for the old app). |
| `./screen.sh set <W>x<H> <dpi>` / `rot <0-3>` / `reset` / `show` | Emulate another screen. Sizes are in the tablet's natural (portrait) orientation: `set 1080x2400 420` + `rot 0` is a portrait phone, `rot 1` its landscape. |
| `./hc.sh j/monitors j/layers j/clients` | Hyprland IPC from outside. Start an app: `./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'`. |
| `./sx.sh '<cmd>' [timeout]` | Run a command INSIDE the live session. Prints output + `rc=`. |
| `./gx.sh '<cmd>'` | Run a command in the guest through a separate proot: reading files and logs (`cat /tmp/fit-x11.log`, `cat /tmp/omarchy-watch.log`, `tail /tmp/omarchy-session.log`). |
| `./shot.sh [name]` | Screenshot + numbers. Read `BANDS` (row ranges of flat colour): `(0,0,0)` = gap or key bar, `(16,16,16)`/`(17,17,17)` = Hyprland's bare fill (bad inside the desktop). |
| `python3 uitap.py --all < dump.xml` | Lists what is on screen as text (`adb -s <tablet-serial> shell uiautomator dump /sdcard/ui.xml` then `adb -s <tablet-serial> exec-out cat /sdcard/ui.xml`). Use it to name a screen you cannot see. |

App pid: `adb -s <tablet-serial> shell pidof com.phoshdroid.app`. Top activity:
`adb -s <tablet-serial> shell dumpsys activity activities | grep -m1 topResumedActivity`.
Launcher intent (same as tapping the icon):
`adb -s <tablet-serial> shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.phoshdroid.app/.LauncherActivity`.
Full launch log of the last start:
`adb -s <tablet-serial> logcat -d -v time -s Phoshdroid:* ProotService:* Phoshdroid.Monitor:*`.

## Items, in this order

### D1. What is installed
`./install.sh` (expect `already installed` + `INSTALL: PASS`), `./screen.sh show`. Record the
sha256 and `lastUpdateTime`
(`adb -s <tablet-serial> shell dumpsys package com.phoshdroid.app | grep -E 'versionName|lastUpdateTime'`).
If `INSTALL: FAIL`, STOP and report.

### D2. Cold start, five times
`./coldstart.sh 5 --tag n1` (600 s timeout). PASS needs ALL of: unaided handover 5/5, desktop
PASS 5/5, `relaunch=0` on every run, no foreign activity on any run. Record per run: handover
seconds, desktop seconds, and from the `launch:` lines the milliseconds of `session-started`, of
the `ready ...` line and of `handover done`, plus which reason the ready line names (`marker`,
`fallback`, `timeout`, `session_died`). `marker` is the only good one. Paste one run's complete
`launch:` sequence into the report.
If the handover is never `auto`, find out where the launch stopped before going on: top
activity, `uitap.py --all` on a dump, the full launch log. Describe it exactly; do not nudge it.

### D3. Geometry of the native screen
From the last run of D2: paste the probe block and the `BANDS` lines of its screenshot, the
`XLORIE geometry:` log line, and `./gx.sh 'cat /tmp/fit-x11.log'`. PASS needs: X root 3200x1971,
cage window and monitor 3200x1970, scale 2, layers 1600x985 and 1600x26, the first screenshot
band is NOT a flat black band (the bar starts at row 0), a black/key-bar band starts at row
1970 or 1971, and exactly one resize line in `fit-x11.log`. Any other numbers: report them as
they are and say which claim they break.

### D4. Tapping the icon again while it runs
Open a window: `./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'`, check `./hc.sh j/clients` shows
it. Note the app pid and the Hyprland process count (`./gx.sh 'pgrep -c Hyprland'`). Press HOME
(`adb -s <tablet-serial> shell input keyevent KEYCODE_HOME`), wait 5 s, send the launcher intent. PASS:
the X display is on top again within 3 s, same pid, `./probe.sh` PASS, `j/clients` still shows
the foot window, and the Hyprland process count is the same as before (a higher count means a
second session was started). Repeat once more.

### D5. Size change while in the background
With the foot window still open and the X display on top: press HOME, `./screen.sh rot 0`
(portrait), send the launcher intent, then poll `./probe.sh -q` every 2 s for up to 20 s and
record after how many seconds it says PASS. PASS: within 15 s, same app pid as in D4, foot
window still listed. Then HOME, `./screen.sh rot 1`, launcher intent, same measurement. This
is the path where the old app restarted the whole session.

### D6. Size changes while in the foreground
X display on top, foot open. In this order, each followed by the same poll (seconds to PASS, app
pid, foot still listed): `./screen.sh rot 0`, `./screen.sh rot 1`, `./screen.sh set 1200x1920 240`,
`./screen.sh reset`. PASS: every step within 15 s, pid never changes. After the last step paste
`./gx.sh 'tail -30 /tmp/omarchy-watch.log'`.

### D7. Screen-size matrix, cold starts
For each row: `./screen.sh set ...` and `./screen.sh rot ...`, then `./coldstart.sh 1 --tag m-<row>`.

| Row | set | rot |
| --- | --- | --- |
| phone-portrait | `1080x2400 420` | 0 |
| phone-landscape | `1080x2400 420` | 1 |
| small-phone | `720x1600 320` | 0 |
| fold-inner | `2256x2504 420` | 0 |
| small-tablet | `1200x1920 240` | 1 |
| odd | `1080x2401 420` | 0 |
| native | `reset` | auto |

Table in the report, one line per row: handover (auto?) and seconds, desktop seconds,
`relaunch`, foreign activity, the `XLORIE geometry` prediction, the real X root, monitor, scale,
logical size, layers, verdict, and whether the first screenshot band is black. PASS needs every
row: handover auto, verdict PASS, relaunch 0, no foreign activity, prediction == X root, and the
monitor equal to the X root rounded down to even. Finish with `./screen.sh reset` + `show`.

### D8. Screen off and on
X display on top, foot open, note the pid. `adb -s <tablet-serial> shell input keyevent KEYCODE_SLEEP`,
wait 15 s, `KEYCODE_WAKEUP`, `adb -s <tablet-serial> shell wm dismiss-keyguard`. PASS: X display on top
(or on top after the launcher intent: say which), same pid, probe PASS, foot listed. If a lock
screen needs a PIN, do not guess it: report "blocked by lock screen" and go on.

### D9. Keyboard and touch still work at the new geometry
Keyboard: write a two-line script (`read -r line; printf '%s' "$line" > /tmp/typed.txt`) to a
local file, copy it to the guest's `/tmp` (guest `/tmp` is `files/usr/tmp` on the device:
`adb -s <tablet-serial> push <file> /data/local/tmp/read-t.sh` then
`adb -s <tablet-serial> shell run-as com.phoshdroid.app cp /data/local/tmp/read-t.sh files/usr/tmp/read-t.sh`),
start it with `./hc.sh '/dispatch hl.dsp.exec_cmd("foot bash /tmp/read-t.sh")'`, then
`adb -s <tablet-serial> shell input text hello` and `input keyevent KEYCODE_ENTER`, then
`./gx.sh 'cat /tmp/typed.txt'`. PASS: `hello`.
Touch: `adb -s <tablet-serial> shell input tap 1600 1000`, then `./sx.sh 'hyprctl -j cursorpos'`.
PASS: the cursor is within 4 logical px of (800, 500), i.e. (x/2, y/2) with no top-gap offset.
Try a second point (400, 300 -> 200, 150).

## Report, final sections

- STATUS TABLE (D1-D9).
- "Failures, exactly": for every FAIL the command, the numbers, the log lines, which claim from
  "What the new build should do" is broken, and whether you think the fault is host or guest and
  why. No fixes on the host side.
- "Changes": tool or guest files you changed, with the reason and the hash proof.
- "Screenshots for the parent to view": path + what each should show.
- "Not verified".
- The `./screen.sh show` output after the final reset.

Final reply, at most 12 lines: one line per item with PASS/FAIL and its decisive number, the
report path, the screen-reset line, and the single most important failure if there is one.
