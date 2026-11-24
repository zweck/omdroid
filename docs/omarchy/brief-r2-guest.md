# Brief R2-GUEST: the guest session starts the same way every time and follows any screen size

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`. You cannot view images; judge with the numbers the tools print and
list the screenshots for the parent (it can see them).

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`, read `AGENTS.md`).
Tools: `docs/omarchy/tools/` (run them from that directory).
Contract with the host side: `docs/omarchy/launch-contract.md` (read it whole first, never edit it).
Background from round 1 (read once): `docs/omarchy/device-bringup-report.md`.
Your report, created in your first calls and updated after EVERY item:
`docs/omarchy/r2-guest-report.md`.

## Goal

Phil asked for two things: the app must launch predictably, and it must work on different screen
sizes. Your half is the guest: the session inside proot must (1) start with no timing guesses,
(2) tell the host when the desktop is really drawn, and (3) end up correct for ANY X root size,
both at start and when the size changes while it runs.

## What is true right now (measured today on the tablet)

- The desktop works: wallpaper, bar, windows, keyboard, touch. `./probe.sh` prints
  `VERDICT: PASS` (xroot 3200x1776, cage window 3200x1776, monitor 3200x1776@120 scale 2, logical
  1600x888, layers `omarchy-background` 1600x888 and `omarchy-bar` 1600x26).
- `./coldstart.sh 1 --nudge` at 18:05: desktop PASS at +9.0 s.
- Chain: Xlorie X server (in the Android app) -> `cage` (wlroots x11 backend, pixman) ->
  Hyprland 0.56.1 (patched, LUA config) -> `quickshell` (Omarchy shell, started by Hyprland's config
  through `omarchy-launch-shell`, which supervises and relaunches it).
- `/etc/phoshdroid/start.sh` (repo: `rootfs/overlay/etc/phoshdroid/start.sh`) builds the plugin
  index, starts `fit-x11.py "$DISPLAY" 1` in the background (polls every 1 s, resizes cage's X
  window to the X root) and execs `dbus-run-session ... cage -- /etc/phoshdroid/session-child.sh`.
- `session-child.sh` is `sleep "${CAGE_SETTLE:-6}"` then `exec Hyprland`. **The 6 s sleep was
  added on a theory that turned out to be wrong** (Hyprland was never starved by a resize). Nobody
  has measured whether any wait is needed.
- `~/.config/hypr/monitors.lua` (payload file, do not edit) forces a mode from
  `OMARCHY_OUTPUT_WIDTH/HEIGHT/REFRESH_MHZ` when all three are set, else uses `"preferred"`.
  Measured in round 1: with the forced mode 3200x1776 and the X root later growing to 3200x2136,
  Hyprland DID follow to 3200x2136, but the shell's background layer stayed 1600x888, leaving a
  flat `(17,17,17)` band at the bottom. After `omarchy-restart-shell` the layer was right
  (1600x1068). So: cage follows, Hyprland follows, **the shell's layers do not**. That is the bug
  behind item G4.
- Upstream guest files are extracted read-only on this machine, so you can read scripts and QML
  without the device: `~/.hermes/cache/scratch/omarchy/upstream/usr/share/omarchy/` (`bin/omarchy-launch-shell`,
  `bin/omarchy-restart-shell`, `shell/...`) and `.../upstream/home/omarchy/.config/hypr/`. Facts
  already dug out of them: `~/.hermes/cache/scratch/omarchy/upstream/findings.md`.

### The installed APK is the OLD one — work around it, do not fix it

A sibling agent is rewriting the host side (`app/**`) right now; the new APK is built and installed
by the parent after both of you report. Until then the installed app has these known faults:

1. On every launch it opens Android's "all files access" settings page and never brings the X
   display forward. `./coldstart.sh --nudge` does that for you (handover=NUDGED is expected).
2. It hands the guest `OMARCHY_OUTPUT_HEIGHT` = window height - 360 and shows the X view with gaps
   of 160 px above and 200 px below. The new APK will use: no top gap, bottom gap = extra-key bar
   (165 px on this tablet), so the X root there will be 3200x**1971**. Odd numbers will be normal.
3. After a display-size change, a cold start kills its own process once and hopes an alarm brings
   it back. `coldstart.sh` relaunches it (relaunch=1 is expected after `screen.sh set`).
4. When the app returns from the background after a size change it restarts the whole session.
   For LIVE size tests keep the X display on top the whole time.

## Hard rules

- Device: ONLY the tablet, serial `<tablet-serial>`; every adb call carries `-s <tablet-serial>`. A phone
  (`SM_F976B`) is attached too: never touch it.
- NEVER `adb uninstall`, `adb install`, `pm clear`, or delete under `files/usr` wholesale.
- NEVER run gradle or the packager (`package-omarchy-rootfs.sh`); the parent builds.
- A sibling owns `app/**`. You own `rootfs/overlay/etc/phoshdroid/**`,
  `rootfs/package-omarchy-rootfs.sh`, `docs/omarchy/tools/**` and your report. Touch nothing else:
  not `app/`, not `termux-x11/`, not `termux-app/`, not the contract, not the other report.
- No git command that changes anything (no add, commit, stash, checkout, restore, clean).
- Guest files: edit on this machine, push with `./gpush.sh`, `./gx.sh 'chmod 755 <path>'` for
  scripts. No in-guest `sed -i`. A payload file (not from `rootfs/overlay/`) is changed only as a
  last resort: pull the original to `~/.hermes/cache/scratch/omarchy/r2/orig/` first and say so.
- **Screen overrides are Phil's tablet settings.** After ANY `./screen.sh set` or `rot`, and before
  you finish or stop for any reason, run `./screen.sh reset` and confirm `./screen.sh show` prints
  `Physical size: 2136x3200`, `Override density: 352`, `accelerometer_rotation=1` and no
  `Override size` line. Put that output in the report.
- Never `pkill -f` / `pgrep -f ... | kill` on this machine. One Wayland parent at a time. No
  `WAYLAND_DEBUG`. One change per boot, and say which change produced which numbers.
- If a terminal command is BLOCKED waiting for approval, do not retry or rephrase it: put the
  guest command in a file with `write_file` and run `./sx.sh "$(cat file)"` / `./gx.sh "$(cat file)"`.
- `start.sh` is quoting-fragile: no apostrophes in its comments. Run `bash -n` on every script
  you change before pushing it.
- Scratch: `~/.hermes/cache/scratch/omarchy/r2/`.

## Tools (all default to the tablet)

| Tool | Use |
| --- | --- |
| `./probe.sh [-q]` | THE check. X root, cage window, monitor (pixels, scale, logical), layers, clients, ready marker, then `VERDICT: PASS` or `VERDICT: FAIL <which link is wrong>`. About 2 s. Works whatever Android activity is on top. |
| `./coldstart.sh [runs] --nudge --tag <name>` | Cold start like the launcher icon; per run `handover`, `desktop=+N s` (first probe PASS, 2 s resolution), full probe, screenshot numbers; `SUMMARY` line. 60 s per run: more than 2 runs need a 600 s timeout. |
| `./screen.sh set <W>x<H> <dpi>` / `rot <0-3>` / `reset` / `show` | Emulate another screen. Sizes are in the tablet's natural (portrait) orientation: `set 1080x2400 420` + `rot 0` is a portrait phone, `rot 1` its landscape. |
| `./hc.sh j/monitors j/layers j/clients` | Hyprland IPC from outside. Plain requests start with `/`. LUA config: start an app with `./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'`. |
| `./sx.sh '<cmd>' [timeout]` | Run a command INSIDE the live session (session env, stock `hyprctl` works). Prints output + `rc=`. |
| `./gx.sh '<cmd>'` | Run a command in the guest through a SEPARATE proot, minimal env: reading files, `cat` logs. Works with no session. |
| `./gpush.sh <local> <guest-abs-path>` | Copy a file into the guest rootfs. Guest `/tmp` is NOT in the rootfs: it is `files/usr/tmp` on the device. |
| `./shot.sh [name]` | Screenshot + numbers. Read `BANDS` (row ranges of flat colour): `(0,0,0)` = gap or key bar, `(16,16,16)` = Hyprland's bare fill (bad inside the desktop), `(24,24,40)` = bar background. |

Stock `hyprctl` from `gx.sh` or adb can never work (proot remaps the socket per instance).
Anything you start from `start.sh` runs inside the session's proot, so there stock `hyprctl`
works once `HYPRLAND_INSTANCE_SIGNATURE` is set to the newest directory name in
`$XDG_RUNTIME_DIR/hypr/`.

## Items, in this order

### G1. Baseline
`./probe.sh`, then `./coldstart.sh 3 --nudge --tag base` (600 s timeout). Record the three
desktop times. If probe says `NO-SESSION` twice in a row after a cold start, STOP and report.

### G2. Deterministic start (no fixed sleep)
Replace the `sleep 6` in `session-child.sh` with a bounded wait for a real condition: cage's X
window has the size the guest wants (the X root, each dimension rounded DOWN to even — contract
section 1; make `fit-x11.py` do that rounding everywhere). Suggested shape: a one-shot mode of
`fit-x11.py` that resizes, confirms the geometry and exits 0, with a 10 s cap after which Hyprland
starts anyway and one line says so in the session log. First find out with one boot whether ANY
wait is needed (set the wait to 0): if the desktop is correct without it, the bounded wait stays
only as cheap insurance. Proof: `./coldstart.sh 5 --nudge --tag g2` = 5/5 desktop PASS, and the
min..max desktop time against G1.

### G3. Ready marker (contract section 2)
Write `$XDG_RUNTIME_DIR/omarchy-ready` exactly as the contract says, from a watcher that
`start.sh` starts in the background before the `exec` (new file under
`rootfs/overlay/etc/phoshdroid/`, log to `/tmp/omarchy-watch.log`). `start.sh` removes a stale
marker first. Proof: over 3 cold starts the marker appears, its `<width>x<height>` equals the
monitor in `probe.sh`, and it appears no later than about 3 s after the first probe PASS
(`./gx.sh 'cat /run/user/1000/omarchy-ready; cat /tmp/omarchy-watch.log'`). Also prove the negative
once: with the shell prevented from starting (temporarily rename the plugin index script call, one
boot, then restore), NO marker is written.

### G4. Live size changes (contract section 3) — the main item
1. Reproduce: session up, X display on top, one `foot` window open
   (`./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'`). `./screen.sh rot 0`, wait 15 s, `./probe.sh`.
   Expect FAIL with a stale layer. Record it.
2. Find the cheapest remedy that makes probe PASS, trying in this order and stopping at the
   first that works reliably: (a) nothing needed once `OMARCHY_OUTPUT_WIDTH/HEIGHT` are no longer
   exported to Hyprland (then `monitors.lua` uses `"preferred"` and follows its parent; unset
   them in `start.sh` right before the `exec`); (b) a Hyprland-side nudge from inside the session
   (`hyprctl reload`, or re-applying the monitor rule); (c) restarting only the shell
   (`omarchy-restart-shell`: read it first, it worked in round 1). Whatever (a) shows, decide
   whether the forced mode should go: a forced mode that is re-applied after the root changed
   would be wrong.
3. Put the remedy in the watcher: when the monitor size has been stable for about 1.5 s and the
   layers do not fit it, act once, log it, check again. It must not loop (cap retries, log the
   give-up) and must not act while sizes already agree.
4. Proof, all without any manual in-session command: `rot 0`, `rot 1`, `rot 0`, `rot 1` — after
   each, `./probe.sh` PASS within 15 s, and `clients` still shows the foot window. Then
   `./screen.sh set 1200x1920 240` live -> PASS within 15 s. Report seconds-to-PASS per step and
   what the watcher logged. `./screen.sh reset` afterwards -> PASS again.
5. Speed: if the 1 s poll of `fit-x11.py` is most of the delay, shorten it (0.25 s) or make it
   event-driven; say what you measured.

### G5. Size matrix from a cold start
For each row: `./screen.sh set ...`, `./screen.sh rot ...`, `./coldstart.sh 1 --nudge --tag m-<name>`,
then record xroot, monitor, scale, logical size, verdict, desktop time and the BANDS lines.

| name | set | rot | apps see |
| --- | --- | --- | --- |
| phone-portrait | `1080x2400 420` | 0 | 1080x2400 |
| phone-landscape | `1080x2400 420` | 1 | 2400x1080 |
| small-phone | `720x1600 320` | 0 | 720x1600 |
| fold-inner | `2256x2504 420` | 0 | 2256x2504 |
| small-tablet | `1200x1920 240` | 1 | 1920x1200 |
| odd | `1080x2401 420` | 0 | 1080x2401 (X height 2041: must be handled by the even rounding) |
| native | `./screen.sh reset` | auto | 3200x2136 |

A row passes when probe says PASS with no in-session help. A row that fails gets one diagnosis
and one fix attempt, then a FAIL row with the cause; do not sink the budget into one size.

### G6. Which scales does this Hyprland accept
At native size, one boot each with `OMARCHY_SCALE` forced to `1` and to `1.5` (push a temporary
copy of `start.sh` with one extra `export`, then push the real one back): record the scale the
monitor reports, the logical size, probe verdict, BAR INK. This only informs a later decision;
change nothing permanent for it.

### G7. Bake, clean, final proof
- Every guest file you added or changed lives under `rootfs/overlay/etc/phoshdroid/` and is copied
  (and `chmod 755` where needed) by `rootfs/package-omarchy-rootfs.sh` (read its overlay block,
  about lines 66-75, and extend it the same way).
- Device: the real `start.sh` and scripts are the ones in the repo (compare sha256 of repo file and
  `./gx.sh 'sha256sum /etc/phoshdroid/*'`), no test leftovers in `/etc/phoshdroid` or guest `/tmp`.
- `./screen.sh reset` + `show` as the hard rule says.
- `./coldstart.sh 5 --nudge --tag final` (600 s timeout): 5/5 desktop PASS, min..max time, marker
  present each run.

## Report format (`docs/omarchy/r2-guest-report.md`)

```
# R2 guest report
HEAD: <git rev-parse --short HEAD>   Device: <tablet-serial>   Model that ran: deepseek-flash

## STATUS TABLE
| Item | Status | Proof (numbers, command, log line) |
| G1 baseline | PASS/FAIL/PENDING | ... |
| G2 deterministic start | ... |
| G3 ready marker | ... |
| G4 live size changes | ... |
| G5 size matrix | ... (one sub-row per size) |
| G6 scales | ... |
| G7 bake + clean + final | ... |

## Contract: anything that is wrong or that you could not meet (with evidence), else "met"
## Root causes (one paragraph each, with the number or log line that proves it)
## Changes (repo file -> guest path -> packager line)
## What the host side must know (anything the new APK has to do or avoid)
## Screenshots for the parent to view (path + what it should show)
## Not verified / left for the parent
## Log (short, appended as you go)
```

Flip a row the moment its proof exists. A FAIL with a named cause is a good result; a PASS you
did not measure is not. You have roughly 90 tool calls per turn: batch commands, never poll with
sleep in a separate call, and when you are near the end write the table's final state (and reset
the screen) before anything else.

## Final reply

At most 12 lines: per item PASS/FAIL/PENDING with its decisive number, files changed, the
`./screen.sh show` line proving the reset, what the parent must do next, the report path.
