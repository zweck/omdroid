# Brief: Omarchy on the tablet — finish the device bring-up

You are the WORKER for this brief. Do the work yourself in this session: never create
agents, never call `delegate_task`. You cannot view images; judge screenshots with the
numbers `shot.sh` prints and leave the pictures for the parent (it can see them).

Repo: `/home/phil/Development/personal/proot-post`, branch `omarchy-embed` (read `AGENTS.md`).
Tools: `docs/omarchy/tools/` (run them from that directory).
Report (you create it in your first calls and update it after EVERY step):
`docs/omarchy/device-bringup-report.md`.

## Goal

A cold start of the app `com.phoshdroid.app` on the tablet ends in a usable Omarchy desktop:
wallpaper, a bar with its widgets, a window you can open and type into. Then the fix is in
the repo overlay so a fresh install reproduces it.

## Where it stands (all measured on the tablet today)

The desktop stack IS running. Do not re-diagnose it.

- Chain: Xlorie X server (in the app) -> `cage` (wlroots, `WLR_BACKENDS=x11 WLR_RENDERER=pixman`)
  -> Hyprland (patched build in `/opt/omarchy-android`) -> `quickshell -n -p /usr/share/omarchy/shell`.
- `./hc.sh j/monitors` answers: `WAYLAND-1`, 3200x2136@120, scale 2.00, `reserved: [0, 26, 0, 0]`.
- `./hc.sh j/layers` answers: ONE layer, level 2, namespace `omarchy-bar`, 1600x26, owned by quickshell.
  Level 0 (background) is EMPTY. `j/clients` is `[]`.
- Screenshot: whole screen is `(17,17,17)` (Hyprland's default fill), a 52 px band `(26,27,37)` at
  the top (the bar's background) with only 4 distinct colours in it, a cursor, and Termux:X11's
  extra-key row at the bottom. So: **the bar is drawn but empty, and there is no wallpaper.**
- Fonts are present: `fc-list | grep -icE "jetbrains|nerd"` = 96, `fc-match monospace` = JetBrainsMono Nerd Font.
- `~/.config/omarchy/current/background` -> `.../theme/backgrounds/0-winding-road.webp` (existence of the target not checked).
- `~/.config/hypr/autostart.lua` only starts `omarchy-voxtype-daemon`. What starts the shell and the
  wallpaper has not been traced.
- Quickshell writes logs to `/run/user/1000/quickshell/by-id/<id>/log.log` (newest by mtime = live one). Nobody has read them yet.
- Host logcat on cold start: `XLORIE geometry: 3200x1776 (raw 3200x2136)` and
  `bootstrap finished while backgrounded; deferring MainActivity launch`. After a cold start the
  launcher stayed on top; the X display only appeared after
  `adb -s <tablet-serial> shell am start -n com.phoshdroid.app/com.termux.x11.MainActivity`.
- The host (`app/.../proot/ProotCommandBuilder.kt`) passes the whole environment (KGSL/Mesa/AQ_/HYPRLAND_
  variables, `OMARCHY_OUTPUT_WIDTH/HEIGHT`, `OMARCHY_SCALE`, `PROOT_TMP_DIR`). `start.sh` repeats it.

### Earlier conclusions that were WRONG — do not build on them

1. "Hyprland never finishes init, the IPC socket is never created." False. proot rewrites the
   over-long `AF_UNIX` path of `hypr/<sig>/.socket.sock` (about 140 bytes on the host, `sun_path`
   holds 108) to `$PREFIX/tmp/proot-<pid>-XXXXXX` and keeps that mapping inside ONE proot instance.
   From adb `run-as` or from `gx.sh` (a second proot) the socket file is not at its nominal path
   and stock `hyprctl` says `Couldn't connect ... (4)`. The socket is alive: `hc.sh` talks to it.
2. "No GL context (EGL probe)". Invalid: the probe ran through `gx.sh`, which has a stripped
   environment, and the probe had a ctypes bug. Hyprland renders (fill, bar band, cursor).
3. "Hyprland's log stops at `Creating the AsyncResourceGatherer!`, so it hangs there." False: its own
   log goes quiet once the config is applied. `scheduleFrame ... canSchedule false` lines under
   `HYPRLAND_TRACE` are normal chatter, not a stall.

## Hard rules

- Device: ONLY the tablet, serial `<tablet-serial>`. Every adb call carries `-s <tablet-serial>`. A phone may be
  attached too; never touch any other serial.
- NEVER `adb uninstall`, `pm clear`, or delete anything under `files/usr` wholesale: the 4 GB guest
  would be gone and reinstalling is blocked on this ROM. Do not run `adb install`.
- Do not rebuild `rootfs.bin` or the APK (`package-omarchy-rootfs.sh`, gradle). The parent does that
  after your report. Iterate by pushing files into the extracted guest.
- No `git commit`, `git push`, `git stash`, `git checkout` of files. Leave your changes in the tree.
- Never edit files in the guest with an in-guest `sed -i`. Edit a file on this machine, push it with
  `gpush.sh`. For a guest file that comes from the payload (not from `rootfs/overlay/`), first pull
  the original (`./gx.sh 'cat <path>' > <scratch>/orig/<name>`), keep it, edit a copy, push the copy.
- Never `pkill -f` / `pgrep -f ... | kill` on this machine. Kill by exact PID.
- One change per boot or per shell restart, and say which change produced which numbers.
- One Wayland parent at a time (never start a second cage/sway). Do not set `WAYLAND_DEBUG`.
- If a terminal command is BLOCKED waiting for approval, do not retry or rephrase it. Put the guest
  command in a file with `write_file` and run `./sx.sh "$(cat file)"` / `./gx.sh "$(cat file)"`.
- Files you may edit: `rootfs/overlay/etc/phoshdroid/*`, `rootfs/package-omarchy-rootfs.sh`,
  `docs/omarchy/tools/*`, your report. Kotlin under `app/` only for item D, and say in the report
  that it is UNTESTED (it cannot run without an APK rebuild). Never touch `termux-x11/`, `termux-app/`.
- Scratch for originals, logs, experiments: `~/.hermes/cache/scratch/omarchy/worker/`.
- `start.sh` in the overlay is quoting-fragile: no apostrophes in its comments.

## Tools (in `docs/omarchy/tools/`, all default to the tablet)

| Tool | Use |
| --- | --- |
| `./hc.sh j/monitors j/layers j/clients` | Hyprland IPC of the live session. One request per argument. `./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'` starts a long-lived app. |
| `./sx.sh '<cmd>' [timeout]` | Run a shell command INSIDE the live session (started by Hyprland, so it has the session env, D-Bus, `WAYLAND_DISPLAY`, working stock `hyprctl`). Prints output + `rc=`. |
| `./gx.sh '<cmd>'` | Run a command in the guest through a SEPARATE proot with a minimal env. For reading/listing guest files. Works with no live session. Never judge GL, Wayland or IPC from it. |
| `./gpush.sh <local> <guest-abs-path>` | Copy a file into the guest rootfs. Re-`chmod` scripts afterwards with `./gx.sh 'chmod 755 <path>'`. |
| `./cycle-tablet.sh [wait_s]` | Cold start (force-stop + launch), then print processes and log tails. Use 60. |
| `./shot.sh [name]` | Screenshot + numbers (`WHOLE`, `BAR`, `DESKTOP`, `INK`, `HINT:` lines). PNGs in `~/.hermes/cache/scratch/omarchy/shots/`. |
| `xlist.py` | In the guest: X root size and top-level windows (`./gpush.sh xlist.py /tmp/...` is wrong: guest `/tmp` is `files/usr/tmp`; use `adb -s <tablet-serial> push` + `run-as com.phoshdroid.app cp ... files/usr/tmp/`, then `./gx.sh 'DISPLAY=:0 python3 /tmp/xlist.py'`). |

After a cold start, bring the X display forward if `shot.sh` prints `LIGHT SCREEN`:
`adb -s <tablet-serial> shell am start -n com.phoshdroid.app/com.termux.x11.MainActivity`.
A session is live right now (started 16:35); you can begin without a reboot.

This Hyprland is 0.56.1 with a LUA config (`~/.config/hypr/*.lua`, `hl.*` API). Over IPC,
`dispatch X` is evaluated as `hl.dispatch(X)`: the classic `dispatch exec foot` is a syntax error,
the working form is `/dispatch hl.dsp.exec_cmd("foot")` (verified). Start every non-JSON request
with `/`. Config changes are Lua too; read the existing files for the idiom before writing one.
Verified inside the session through `sx.sh`: `WAYLAND_DISPLAY=wayland-1`,
`MESA_LOADER_DRIVER_OVERRIDE=kgsl`, stock `hyprctl -j monitors` answers.

A sibling agent is reading the upstream project read-only and writes
`~/.hermes/cache/scratch/omarchy/upstream/findings.md` (what starts the shell and wallpaper, what
the bar needs, known issues). Look for that file when you start item A and again at item B. Use it
if it is there. Never wait for it.

## First calls

1. Create the report with the STATUS TABLE below, every row `PENDING`.
2. `cd docs/omarchy/tools && ./hc.sh j/monitors j/layers j/clients && ./shot.sh baseline` — confirm the
   state above. If `hc.sh` prints `NO-SESSION` or `NO-IPC`, run `./cycle-tablet.sh 60`, bring the X
   display forward, and repeat once. If it still fails, STOP and report the exact output.
3. Then the items, in this order.

## Items

### A. The bar is empty
1. Read the live quickshell log:
   `./gx.sh 'L=$(ls -t /run/user/1000/quickshell/by-id/*/log.log | head -1); echo "$L"; grep -aiE "error|warn|fail|undefined|cannot|unavailable|not " "$L" | head -80; echo ----; tail -40 "$L"'`
2. Find what starts the shell and how to restart it alone:
   `./gx.sh 'grep -rnE "quickshell|omarchy-launch-shell|omarchy-restart" /home/omarchy/.config/hypr /usr/share/omarchy/bin /usr/share/omarchy/default 2>/dev/null | head -40'`
3. From the log, name the cause. Hypotheses, each with its single test:
   - QML load errors because a service is missing under proot (UPower, NetworkManager, PipeWire,
     bluetooth, logind/systemd). Test: the log names the failing import/property. Fix in the shell's
     QML or by giving it what it needs; keep the original file.
   - Text and icons do not render through Qt Quick's GPU path on this driver while rectangles do.
     Test: restart the shell once with `QT_QUICK_BACKEND=software` in its environment (via `sx.sh`,
     using the exact launch command from step 2) and compare `shot.sh` `BAR INK`.
   - The shell is waiting on something (it polls `hyprctl -j monitors` before building the bar).
     Test: `./sx.sh 'hyprctl -j monitors | head -5; echo; hyprctl version | head -3'`.
4. PASS when `shot.sh` prints `HINT: BAR has content` and at least two of the three bar thirds
   have `INK` above 0.5 %, from a shell started the normal way (no manual env).

### B. No wallpaper
Find what should draw it (`grep -rnE "swaybg|hyprpaper|wallpaper|background" /home/omarchy/.config/hypr /usr/share/omarchy/bin | head -30`),
check the image exists (`ls -lL ~/.config/omarchy/current/background`), start it in the session with
`./hc.sh '/dispatch hl.dsp.exec_cmd("...")'`, then make the session start it by itself. PASS when `hc.sh j/layers` shows a
level-0 layer and `shot.sh` prints `HINT: DESKTOP shows a wallpaper or window`.

### C. Cold-start proof (run it after A and again after B)
`./cycle-tablet.sh 60`, bring the X display forward if needed, `./hc.sh j/monitors j/layers`,
`./shot.sh cold-<n>`. No manual in-session command between boot and screenshot. Record the numbers.
This row is the headline: A and B only count if they hold here.

### D. Window and input
1. `./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'` (or the terminal Omarchy binds; find it), then `./hc.sh j/clients`
   must list it and `shot.sh` `DESKTOP INK` must rise.
2. Keyboard: start `foot sh -c "read l; echo \"\$l\" > /tmp/typed.txt"`, then
   `adb -s <tablet-serial> shell input text hello` and `adb -s <tablet-serial> shell input keyevent KEYCODE_ENTER`,
   then `./gx.sh 'cat /tmp/typed.txt'` must print `hello`.
3. Touch: `adb -s <tablet-serial> shell input tap 1600 1000`, then `./hc.sh j/cursorpos` — report whether the
   position follows the tap (logical coordinates are device pixels / 2).
4. Launcher hand-over: read `LauncherActivity.kt` around `deferring MainActivity launch` and
   `waitForHyprlandReady`; say in the report why the X display is not shown by itself after a cold
   start through `am start`, and whether it is an artefact of starting over adb with the screen
   locked/backgrounded or a real bug. Patch only if it is clearly a bug; mark it UNTESTED.

### E. Geometry
The host says the usable area is 3200x1776, the X root is 3200x2136, `fit-x11.py` resizes cage to
the root, and `~/.config/hypr/monitors.lua` forces a mode from `OMARCHY_OUTPUT_*`. Establish with
numbers (X root from `xlist.py`, `j/monitors`, a screenshot with a window at the bottom edge) whether
the bottom of the desktop is hidden behind the extra-key row or system bars. Make the three agree.
Prefer a guest-side fix. Report what you changed and the before/after numbers.

### F. Bake and clean
- Every fix must live in `rootfs/overlay/etc/phoshdroid/` (or be applied by
  `rootfs/package-omarchy-rootfs.sh`, which already copies the overlay and installs packages; read
  it first) so a fresh install gets it. List each guest file you changed, where its repo source is,
  and the exact packager lines that install it.
- Revert the diagnostics on the device: `enable_stdout_logs = true` in the guest's
  `~/.config/hypr/looknfeel.lua` (was `false`), and delete `/etc/phoshdroid/sway.conf` (left over;
  `sway` itself is also only on the device, not in the packager — say so, do not add it).
- Run item C one last time on the cleaned device.

## Report format (`docs/omarchy/device-bringup-report.md`)

```
# Device bring-up report
HEAD: <git rev-parse --short HEAD>   Device: <tablet-serial>   Model that ran: deepseek-flash

## STATUS TABLE
| Item | Status | Proof (numbers, command, log line) |
| A bar | PASS/FAIL/PENDING | ... |
| B wallpaper | ... |
| C cold start | ... |
| D window / keyboard / touch / hand-over | ... |
| E geometry | ... |
| F bake + clean | ... |

## Root causes (one paragraph each, with the log line or number that proves it)
## Changes (repo file -> guest path -> how the packager installs it)
## Screenshots for the parent to view (path + what it should show)
## Not verified / left for the parent (APK rebuild, Kotlin, anything you could not observe)
## Log (short, appended as you go)
```

Flip a row the moment its proof exists. A FAIL with a named cause is a good result; a PASS you
did not measure is not. You have roughly 90 tool calls: batch commands, do not poll, and when you
are near the end write the final state of the table before anything else.

## Final reply

At most 12 lines: per item PASS/FAIL/PENDING with its one decisive number, the files you changed,
what the parent must do next, and the report path.
