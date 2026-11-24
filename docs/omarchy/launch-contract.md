# Launch and geometry contract (host app <-> guest session)

Two agents work against this file in round 2: one owns the host (`app/**`, Kotlin), one owns the
guest (`rootfs/overlay/etc/phoshdroid/**`). Neither edits this file. If something here is
wrong or cannot be met, say so at the top of your report with the evidence and work to the
nearest thing that can.

## 1. Who knows the screen size

**The X root is the truth.** Xlorie sizes its root to the Android surface it is shown on, so the
only number that is always right is the root size at that moment.

- The host predicts the X size before the surface exists and exports it as `XLORIE_WIDTH` /
  `XLORIE_HEIGHT` (the X server's initial root) and `OMARCHY_OUTPUT_WIDTH` /
  `OMARCHY_OUTPUT_HEIGHT` (guest env). Both are HINTS. The host passes the two `OMARCHY_OUTPUT_*`
  variables only when both are greater than 0.
- The guest never trusts the hint over the root: cage's window is kept equal to the root
  (`fit-x11.py`), Hyprland follows its parent, the shell's layers follow the monitor.
- Parity is the guest's job: an odd root width or height is rounded DOWN to an even number for
  cage's window (odd sizes are normal: 2136 - 165 = 1971 on the tablet), so the logical size is
  integral at scale 2. The spare pixel row stays black. The host does not round anything.
- The host makes its prediction exact for the normal full-screen case, so that no resize happens
  at hand-over: X width = window width minus the left and right display-cutout insets; X height
  = window height minus the top and bottom display-cutout insets minus the bottom gap. The bottom
  gap is the Termux:X11 extra-key bar when it is shown, `round(37.5 * density * rows)` pixels
  (rows = 2 by default), else 0. There is no top gap any more: Termux:X11 runs with
  `hideCutout=false`, so Android itself keeps the window clear of a cutout in every rotation.

## 2. Ready marker

File: `$XDG_RUNTIME_DIR/omarchy-ready` in the guest = `<prefix>/tmp/omarchy-runtime/omarchy-ready`
on the host (`<prefix>` = `/data/data/com.phoshdroid.app/files/usr`).

- Written by the guest, atomically (write a temp name, then `mv`), when ALL of these hold:
  Hyprland's IPC answers, it has one enabled monitor whose size equals the X root, and the layers
  `omarchy-bar` and `omarchy-background` exist and fit the monitor's logical size.
- Content, one line: `ready <unix-seconds> <width>x<height> scale=<scale>` (monitor pixels).
- The guest removes a stale marker at session start, before cage starts. The host removes it
  too, before it starts the session.
- The marker means "the desktop was fully drawn once in this session". The guest does not
  remove it on a later resize.
- Host wait rule: hand over when the marker exists. Fallback for a guest image without the
  marker writer: hand over once a `hypr/<instance>` directory has existed for 25 s. Hard cap
  120 s. Give up at once if the session process has died.

## 3. Live size changes

Rotation, fold/unfold, multi-window and the extra-key bar can change the X root while the session
runs. Required end state, with no user action and no session restart: within 15 s of the root
changing, `docs/omarchy/tools/probe.sh` prints `VERDICT: PASS` and the windows that were open are
still open. The host does not kill or restart the session because the display size changed.

## 4. Scale

`OMARCHY_SCALE` is `1` or `2` (integers). The host picks it from the display density unless the
user chose one. Fractional scales are out of scope until the guest round reports which values
this Hyprland build accepts.

## 5. Session start, in order

1. Host: wipe `<prefix>/tmp/omarchy-runtime/` (it is `XDG_RUNTIME_DIR`: nothing in it is meant to
   survive a session) and stale `<prefix>/tmp/proot-*` entries; keep the logs in `<prefix>/tmp`.
2. Host: start the X server, then the proot guest with `/etc/phoshdroid/start.sh`. No host-side
   Wayland compositor is started (weston is gone; cage inside the guest is the parent).
3. Guest `start.sh`: build the plugin index, remove a stale ready marker, start the resize helper
   and the ready/resize watcher, exec `dbus-run-session ... cage -- session-child.sh`.
4. Guest `session-child.sh`: wait (bounded, no fixed sleep) until cage's window equals the X root,
   then exec Hyprland. Hyprland's own config starts the shell.
5. Guest watcher: write the ready marker; afterwards keep the layers in step with the monitor.
6. Host: on the marker (or the fallback), bring `com.termux.x11.MainActivity` forward with the
   same bottom gap it used for the prediction (extras `phoshdroid_top_padding_px` = 0,
   `phoshdroid_bottom_padding_px` = gap). The X view sits at the top of the window.

## 6. Logs both sides can rely on

- Guest: `/tmp/omarchy-session.log` (session), `/tmp/fit-x11.log`, `/tmp/omarchy-watch.log`
  (watcher: one line per state change, with a timestamp). Guest `/tmp` = `<prefix>/tmp`.
- Host: logcat tag `Phoshdroid`, lines starting `launch:` carry the phase name and milliseconds
  since process start.
