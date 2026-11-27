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

**The live scale (R11)** is a separate host-owned file; see section 12.

## 5. Session start, in order

1. Host: wipe `<prefix>/tmp/omarchy-runtime/` (it is `XDG_RUNTIME_DIR`: nothing in it is meant to
   survive a session) and stale `<prefix>/tmp/proot-*` entries; keep the logs in `<prefix>/tmp`.
2. Host: start the X server, then the proot guest with `/etc/phoshdroid/start.sh` (its environment
   carries the GPU mode, section 9), and alongside it the fake-root broker proot (section 7). No host-side Wayland compositor is started (weston
   is gone; cage inside the guest is the parent).
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
  since process start. Lines starting `gpu:` carry the GPU decision (section 9).

## 7. Fake root (`sudo`)

The desktop stays as it is: uid 1000 (`-i 1000:1000`), so files the session writes belong to
`omarchy` and git/ssh/ownership checks keep working. The image has no `sudo` package (setuid does
not work under proot) and `su`/`run0`/`pkexec` cannot help, but 70 of Omarchy's scripts in
`/usr/share/omarchy/bin` call `sudo` — `omarchy-pkg-add`, `omarchy-pkg-remove`,
`omarchy-refresh-pacman`, timezone, and the `sudo pacman|tee|sed|rm|cp|mkdir|env` uses inside them.

- **Host**: `ProotService` starts a SECOND proot as part of the session: the login command with
  exactly three differences — `-0` (fake root) instead of `-i 1000:1000`, `-w /root`, and
  `/usr/bin/python3 /etc/phoshdroid/omdroid-rootd.py` as the program. Same rootfs, same binds,
  same `--kill-on-exit`, so both proots see `<prefix>/tmp` as `/tmp`. It stops with the session.
  Logs: `session: root-broker started` and `session: root-broker exited <code>` under logcat tag
  `ProotService`; the broker's own output is mirrored to `<prefix>/tmp/root-broker.log`.
  A broker that is missing or dies changes nothing else: the desktop and the session lifecycle
  never depend on it.
- **Socket**: `/tmp/omdroid-root/sock` in the guest (directory 0700, socket 0600), one request per
  connection, concurrent connections allowed. One line per request in
  `/tmp/omdroid-root/rootd.log`: time, `argv[0]`, exit code.
- **Guest**: `/etc/phoshdroid/sudo` is linked to `/usr/local/bin/sudo` at image time, and again by
  `start.sh` every session so an updated install picks it up. It sends argv, cwd, a whitelisted
  environment and either the caller's terminal (a pty, so Ctrl+C/Z and window size behave) or
  plain pipes with stdin streamed. It never asks for a password.
- **Two more clients of the same broker** (same socket, same request format, so nothing above
  changes): `/etc/phoshdroid/fakeroot`, linked as `/usr/local/bin/fakeroot`, runs its command as
  the target user with the FULL environment (plus `FAKEROOTKEY`, which makepkg's `-F` re-entry
  checks) because that is what makepkg calls for its package step; and `/etc/phoshdroid/yay`,
  linked as `/usr/local/bin/yay`, which is not a broker client itself but installs `yay-bin` on
  first use through `makepkg` and `sudo`. Both links are made by the packager and remade by
  `start.sh`, and `/usr/local/bin` comes first in the session `PATH` so the fakeroot shim wins
  over the real package.
- **What it is**: fake root only. Everything still runs as the Android app's own uid inside its
  private storage, so `sudo` grants no privilege it did not already have. `systemctl` still does
  not work (there is no systemd in this guest).

## 8. Docking (monitor, keyboard, mouse)

A monitor appearing is a dock; it going away is an undock. Neither is a reason to restart the
session (section 3 keeps a live size change alive too), and neither is a reason to lose an open
window.

**Who decides.** The host: `app/src/main/kotlin/com/phoshdroid/app/launch/DockState.kt` is the
pure rule — an external, non-default display docks the device, presentation displays first, public
before private. Android's `UI_MODE_TYPE_DESK` is reported in the `dock:` log line but never docks
on its own (a charging stand sets it, and flipping the phone to the desktop layout with no monitor
attached would be a regression). Input devices are reported, never a trigger.

**The UI mode file.** The host owns `~/.config/omdroid/ui` in the guest
(`<rootfs>/home/omarchy/.config/omdroid/ui`) and writes `desktop` while docked, `mobile` when not,
at two moments: before a session starts (before `start.sh` runs `omdroid-ui.sh`) and on a dock
change while one runs. It writes nothing when the file already holds that mode.

**The pin.** `~/.config/omdroid/ui-pin`, in the same directory, holds the user's choice: `auto`
(the default when the file is missing or unreadable), `mobile` or `desktop`. While it names a mode
the host never writes `ui` at all, so a hand-chosen mode survives every dock and undock:

```bash
echo desktop > ~/.config/omdroid/ui-pin   # keep the stock desktop UI, even on the phone
echo mobile  > ~/.config/omdroid/ui-pin   # keep the phone UI, even on the monitor
echo auto    > ~/.config/omdroid/ui-pin   # follow the dock again
```

The guest applies the pin itself: `omdroid-ui.sh` and `omarchy-watch.py` use `ui-pin` while it
names a mode and `ui` otherwise. (Until the r7 device round the guest read only `ui`, so a desktop
pin written while `ui` said `mobile` changed nothing.)

**The live switch.** `omarchy-watch.py` polls `ui-pin` and `ui` (every 0.25 s). On a change of
the mode they give it runs `omdroid-ui.sh`, rebuilds the plugin index the shell reads at start, puts
the Hyprland monitor back on the even-rounded X root if a config reload moved it, restarts only the
shell (the same rate-limited path a size change uses; a restart the limit refuses is queued, never
dropped) and runs `hyprctl reload config-only` for the `looknfeel.lua` half. A full reload is never
used: `monitors.lua` builds its mode from the session-start size, so any full reload - including
the one Hyprland does by itself when `omdroid-ui.sh` edits `looknfeel.lua` - puts that size back.
After `ready` the watcher therefore keeps the monitor equal to the X root with a runtime rule
(`hyprctl eval 'hl.monitor({ output = "", mode = "<W>x<H>@<Hz>", position = "auto", scale = <current>,
transform = 0 })'`) whenever the two differ for 1.5 s. Windows survive; the session, the X server
and their PIDs are untouched. An older guest image applies a mode change at the next session start
instead — same file, one start later.

**Where the desktop is drawn.** Docked, the host starts `com.termux.x11.MainActivity` with
`ActivityOptions.setLaunchDisplayId(target)`. A private target display, a device without
`FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS`, or any refusal falls back to the plain launch, and the
user then opens Omdroid from the monitor itself — the activity runs on the display it is opened on,
which is research route 1 and always works. If a monitor appears while a session is already
running, the host re-launches the same `singleTask` activity with that display id; no second X
activity can be created, and the X root follows the surface the view lands on.

**Keyboard, mouse, bar.** Docked: `showAdditionalKbd=false` (the extra-key bar is a phone
affordance, and its height comes straight out of the X root), and the X activity asks for Android
16.1's keyboard capture — `<uses-permission android:name="android.permission.CAPTURE_KEYBOARD"/>`
plus `LayoutParams.setKeyboardCaptureEnabled(true)` by reflection, because neither is in the
compileSdk 35 SDK — so Meta reaches Hyprland instead of opening the system's own shortcut. On a
platform without it the reflection logs once and Termux:X11's accessibility `KeyInterceptor` stays
the key path. Undocked: the bar and its gap come back. The X view's margins (the launcher's
`phoshdroid_top_padding_px` / `phoshdroid_bottom_padding_px`, i.e. the extra-key bar's gap) apply
only while the X activity is on the built-in display; on any other display the view fills the
window, so the X root equals the monitor. A launch made while docked also passes
`phoshdroid_builtin_bottom_padding_px`, the built-in display's gap, for the way back.

**Input while docked.** Android keeps keyboard focus and its mouse pointer on the built-in display
(no desktop mode on HyperOS: the pointer cannot reach the monitor and the monitor never gets focus),
so the keyboard capture above never applies there. The host's accessibility `KeyInterceptor` therefore
owns the dock's input while the X activity is alive, started, on a non-default display, connected to
the X server, the keyguard is down and Samsung DeX is off (`DockedInput.captureWanted` in the
`termux-x11` submodule; DeX is excluded because its pointer and focus reach the monitor by
themselves). Then every key of a physical alphabetic keyboard (the tablet's keyboard cover too) and
of a mouse's side buttons goes to X through `MainActivity.handleKey`, whatever Android has focused,
with a release always following its press; power, sleep, volume, media, brightness, camera and call
keys stay Android's. Mice and touchpads are taken with
`AccessibilityServiceInfo.setMotionEventSources(SOURCE_MOUSE | SOURCE_TOUCHPAD)` (API 34; Android
then delivers those events only to the service) and reach X as relative motion, buttons 1-3, wheel
clicks and two-finger scroll (`DockedPointer`). The touchscreen, the stylus and the on-screen
keyboard stay Android's. Android's own arrow keeps moving on the built-in screen but clicks nothing.
Any change of the rule (undock, the activity stopping or dying, the keyguard) drops the pointer
sources at once; a crash or force-stop takes the service with the process, so Android has its input
back immediately. The guest needs nothing for this: the keys and pointer arrive as ordinary X input.

**Surviving the unplug.** The `termux-x11` submodule manifest sets
`com.samsung.android.multidisplay.keep_process_alive=true`: the in-process X server and the proot
session live in our process, so a Samsung teardown on unplug would take the whole desktop with it.
Unplugged, Android migrates the window to the built-in display, the guest switches back to
`mobile`, and the same session is there when Omdroid is next opened. Not yet proven on a device.

**Logs.** `adb logcat -s Phoshdroid:* | grep dock:` — one line per change:
`dock: docked display=2 1920x1080@213 kbd=1 mouse=1` (a private target adds ` private`; `kbd` and
`mouse` are 1 when Android sees a physical keyboard/mouse), and `dock: undocked`. A dock change
that writes the mode file also logs `dock: ui desktop written (docked)`, and a launch that goes to
a monitor logs `launch: handover done display=2 on-target=true`. A display move logs
`x-view margins top=0 bottom=0 (display 2)` (and `... (built-in display)` on the way back). Input
capture logs under the `KeyInterceptor` tag, one line per change:
`input: docked capture on display=2 kbd=2 pointer=3` (`kbd`/`pointer` count the physical keyboards
and pointing devices Android lists, the keyboard cover's included) and `input: capture off`. Guest
side, `/tmp/omarchy-watch.log` gets `ui mode mobile -> desktop` and
`ui switch: omdroid-ui rc=0; plugin-index rc=0; …`, plus `monitor fit <W>x<H> rc=0` when a config
reload had moved the monitor.

## 9. GPU path (KGSL or software)

The session has two GPU paths and the host chooses between them. Both run the same patched Mesa
(`/opt/omarchy-android/mesa`) and the same nested Wayland buffer path; they differ only in which
Mesa driver Hyprland's EGL context uses.

| Mode | Driver | Needs | Speed |
|---|---|---|---|
| `kgsl` | the patched Mesa's `kgsl` Gallium driver (freedreno on the Adreno through `/dev/kgsl-3d0`) | a Qualcomm Adreno phone | GPU |
| `software` | the same Mesa's `llvmpipe` (`swrast`) on the CPU | nothing; any phone | slow, measured per device |

**Who decides.** The host: `launch/GpuMode.kt`, `GpuModeRule.resolve(preference, kgslUsable)`. The
preference is Settings -> Graphics (`gpu_mode` in the `phoshdroid_prefs` store): `auto` (default and
the value of anything unknown), `kgsl` or `software`. `auto` picks `kgsl` when `/dev/kgsl-3d0`
exists and this app can read and write it, else `software`. An explicit `kgsl` or `software` is
honoured even when that probe disagrees. The decision is made once per session start by
`ProotService` and passed to the guest, and to the root broker, as environment:

| Variable | Values | Meaning |
|---|---|---|
| `OMDROID_GPU` | `kgsl`, `software` | the resolved mode |
| `OMDROID_GPU_SOURCE` | `auto`, `setting` | `auto`: the host chose from the device, so the guest may fall back once (below). `setting`: the user chose, nothing overrides it |

**The per-mode variables** (everything else in the guest environment is identical in both):

| Variable | `kgsl` | `software` |
|---|---|---|
| `OMARCHY_GPU_MODE` | `kgsl` | `software` (the only reader, `omarchy-launch-shell`, treats every non-`kgsl` value alike) |
| `MESA_LOADER_DRIVER_OVERRIDE` | `kgsl` | `swrast` |
| `LIBGL_ALWAYS_SOFTWARE` | unset | `1` |
| `GALLIUM_DRIVER` | unset | `llvmpipe` (the patched Mesa has no softpipe) |
| `VK_ICD_FILENAMES` | `/opt/omarchy-android/mesa/freedreno_icd.json` | unset (the image has no lavapipe) |
| `FD_KGSL_ENABLE_DMABUF` | `1` | unset |
| `HYPRLAND_ANDROID_DMABUF` | `1` | unset (no dma-buf producer; clients use `wl_shm`) |

`ProotCommandBuilderTest` pins the `kgsl` environment to the literal list 0.0.17 passed, and
`GuestGpuScriptsTest` runs the guest's `start.sh` block in bash and compares it with what the host
passes, so the two sides cannot drift.

**The guest.** The GPU block of `start.sh` honours `OMDROID_GPU`. When it is missing or not one of the
two values (a manual launch from a shell, `bash /etc/phoshdroid/start.sh`), the block probes
`/dev/kgsl-3d0` itself the same way, sets the source to `auto`, and exports the matching variables;
it also `unset`s the other mode's variables, so a stale shell cannot leak them. `OMDROID_GPU=software
bash /etc/phoshdroid/start.sh` forces the software mode by hand. One line goes to
`/tmp/omarchy-session.log`: `gpu: mode=<mode> source=<source> from=<host|probe|marker>`.

**The fallback.** When the mode is `kgsl` and the source is `auto`, `session-child.sh` runs Hyprland as
a child instead of `exec`ing it. If Hyprland exits non-zero within 15 s of starting
(`OMDROID_GPU_FALLBACK_WINDOW`) and nobody asked it to stop, the script logs
`gpu: Hyprland exited <rc> after <n>s in kgsl mode; ...`, removes the failed start's `hypr/` instance
dir and shell launch lock, switches to the software block and starts Hyprland once more (`exec`).
It also leaves `$XDG_RUNTIME_DIR/omdroid-gpu-fallback`: the host wipes the runtime dir before every
launch, so the marker lives for this launch only, and a host retry of the whole session (next
paragraph) finds it in `start.sh` and starts in software mode at once (`from=marker`). The fallback
is off when the user chose a mode (`source=setting`), and `OMDROID_GPU_FALLBACK=0` (for example in
`~/.config/omdroid/session.env`) turns it off for an auto session. Every other start still `exec`s
Hyprland exactly as before.

**With the host's retry.** `SessionExit.shouldRetry` restarts a guest that exits non-zero inside its
first 30 s, once. The two retries do not stack on the same failure: the guest's own fallback runs
inside the first attempt (up to 15 s lost), and if the software start fails too, the session exits with
that second Hyprland's code, so the host's one retry (if the 30 s have not passed) starts straight in
software through the marker rather than trying KGSL a third time. A software failure that also happens
in the retry shows the session-ended screen as before.

**Logs.** Host, logcat tag `Phoshdroid`: `gpu: preference=auto kgsl-usable=false -> software
source=auto`. Guest: the `gpu:` lines above in `/tmp/omarchy-session.log`.

**Not shipped.** virgl (the image has no `virpipe` driver and there is no host-side virgl server) and
ANGLE on the host are not options, and neither is a Mali, Xclipse or PowerVR hardware path: those
drivers need a DRM render node the app sandbox does not expose.

## 10. Shared storage (host <-> guest)

Two directions, one switch.

**Android -> Omarchy (`~/Phone`).** With sharing on, the host binds `/sdcard` at
`/home/omarchy/Phone` and at `/sdcard` and exports `OMARCHY_SHARE=storage`; with it off, neither bind
is added and the variable is unset. Guest scripts key on `OMARCHY_SHARE` alone, never on whether
`~/Phone` happens to exist: with it set, `/etc/phoshdroid/share/session-start.sh` adds one Nautilus
bookmark (`file:///home/omarchy/Phone`, label `Omdroid Phone`, in `~/.config/gtk-3.0/bookmarks` - the
file GTK3 and GTK4 both read) and records it under `~/.local/state/omdroid/share/`; without it, the
same hook removes that line only while it still equals the recorded text. A line the user edited is
left alone and logged.

**The default is `auto`** (preference `bind_sdcard_mode`): sharing is on exactly when the app holds
`MANAGE_EXTERNAL_STORAGE`. A fresh install starts off - `~/Phone` does not exist - and the one grant
that makes sharing useful also turns it on, with no second setting to find. `on` and `off` are the
explicit overrides; the old boolean `bind_sdcard` is folded into the tri-state on first read. Host log
line: `share: mode=<mode> all-files=<bool> -> bind=<bool>` (logcat tag `Phoshdroid`).

Measured on the tablet (R10 integration APK, both arms in the same minute): with the bind on,
`/home/omarchy/Phone` is the phone's storage, 35 top-level entries; a file written from the guest
lands as uid 10298 (the app's own uid), group 1023 (`media_rw`), mode 0660, directories 2770; a cold
start costs nothing measurable (10.3 s off, 10.6-11.2 s on). With the bind off `~/Phone` does not
exist at all. A picture written from the guest is NOT in Android's media store until something scans
it (`MEDIA_SCANNER_SCAN_FILE`), so gallery apps do not see it by themselves.

**Omarchy -> Android (a DocumentsProvider).** `com.phoshdroid.app.storage.OmdroidDocumentsProvider`,
authority `com.phoshdroid.app.documents`: one root, "Omdroid", whose documents are the files under
the guest's `/home/omarchy`. It is independent of `OMARCHY_SHARE` - it serves the guest home, not
`/sdcard`. Path rules, all in `GuestTree`:
- document ids are `root` and `doc:<path relative to the home>`; anything else is refused;
- `..` is refused anywhere, in a request and in a link target; an absolute id is refused;
- a symlink is resolved in GUEST space: an absolute target is rebased on the guest rootfs, a
  relative one on the link's own directory, at most 16 hops;
- a path that resolves outside `/home/omarchy` is refused;
- nothing is served while `<rootfs>/.extraction_complete` is absent.

Read, write, create, delete and rename are supported; MIME types come from the file extension. The
manifest declaration follows the platform's custom-provider page
(`developer.android.com/guide/topics/providers/create-document-provider`): `exported="true"`,
`grantUriPermissions="true"`, `android:permission="android.permission.MANAGE_DOCUMENTS"` and the
`android.content.action.DOCUMENTS_PROVIDER` intent filter.

## 11. Session requests (guest -> host)

The guest cannot end, restart or leave its own session in a way the host understands, and it cannot
reach Android at all, so Omarchy's Logout, Reboot, Shutdown, Suspend and Lock (all of which need
systemd or `uwsm`, absent here) are mapped onto one request channel.

- **Request.** `omdroid-session <stop|restart|shutdown|home>` (guest `/etc/phoshdroid/complete/bin`,
  linked as `/usr/local/bin/omdroid-session`) writes one line, `<action> <unix-seconds>`, to
  `/tmp/omdroid-host/request` (host `<prefix>/tmp/omdroid-host/request`) by temp name plus rename. For
  `stop`, `restart` and `shutdown` it first closes the windows (`omarchy-hyprland-window-close-all`, as
  Omarchy does) and shows an OSD.
- **Host.** `ProotService` watches the directory (`FileObserver`, no polling), reads the file, deletes it
  (the guest waits up to 4 s for that: the acknowledgement; when it stays, the guest posts a critical
  notification and exits 1, so nothing fails silently) and acts. `SessionRequest.parse` drops an unknown
  action and any stamp more than 30 s from the host clock, so a leftover file cannot end a new session.
  A request is ignored when no session is running.
- **Actions.** `stop` (Logout): end the guest, show the session-ended screen (exit 0). `restart`
  (Reboot): end the guest, then the launcher starts a fresh session (`session_restart` extra, the START
  path of `LaunchDecision`). `shutdown`: end the guest and remove the app's tasks and process (the X
  server lives in it). `home` (Suspend, Lock): the Android home screen comes up, the session keeps
  running; Android owns locking and sleeping. None of them retries (`SessionExit.shouldRetry` is not
  consulted for a requested end). The guest ends itself once the host has acknowledged: `omdroid-session` calls Hyprland's exit dispatcher (`hyprctl dispatch 'hl.dsp.exit()'`; the orderly path; SIGTERM only when that fails. Hyprland also segfaults at exit now and then on either path, which the launcher reports as exit 139 on an exit from inside, see `r11-completeness.md`) and tells the fake-root broker's python program to end, and proot exits by itself. The host waits 10 s for the guest process to die, and only then signals it (SIGTERM, then SIGKILL, 3 s each): on the device a signal to the session proot did not end the guest, and the broker proot outlived SIGKILL. A request is finished exactly once, by the monitor thread or by the request thread, whichever sees the exit first. `OMDROID_SESSION_END=term` (session.env) skips the dispatcher. Log lines (tag `Phoshdroid`): `session: request <ACTION>`,
  `session: request <ACTION>: guest process ended by itself|after SIGTERM|after SIGKILL`, `session: root-broker stopped (<outcome>)` or `session: root-broker still alive after SIGKILL`, `launch: session ended by request
  <ACTION>`, `launch: restart requested by the guest`; guest `/tmp/omdroid-session.log`: time, action,
  acknowledgement delay, taken yes/no.
- **Wrappers.** `/usr/local/bin/omarchy-system-{lock,logout,reboot,shutdown}` and `systemctl`
  (`suspend|hibernate` -> home, `poweroff|halt` -> shutdown, `reboot` -> restart; every other verb goes to
  `/usr/bin/systemctl`) are linked by `complete/session-start.sh` every session start.
- **Menu.** The hook writes a marked block into `~/.config/omarchy/extensions/omarchy-menu.jsonc`: System
  shows Back to Android, Logout, Restart, Shutdown; Lock, Screensaver and Hibernate are hidden (the menu
  skips `when:` guards under proot, so a row can only be removed by giving it no action).
- **Idle.** Android owns locking and the screen timeout: the hook creates Omarchy's own stay-awake flag
  once (`~/.local/state/omarchy/indicators/stay-awake`, recorded in `~/.local/state/omdroid/complete`).
- **Switches** (`~/.config/omdroid/session.env`): `OMDROID_COMPLETE=0` (everything off), `_POWER=0`,
  `_IDLE=guest`, `_MENU=0`, `_TERMINAL=0`, `_EDITOR=0`, `_BANNERS=0`.

## 12. The live UI scale (host -> guest)

`OMARCHY_SCALE` is the scale at session start only. The scale for the
display the desktop is on now is the host-owned file `~/.config/omdroid/ui-scale` in the guest
(`<rootfs>/home/omarchy/.config/omdroid/ui-scale`): one line, `1` or `2`, written atomically next to
the `ui` mode file. The host resolves it the same way as the start-time value, for the TARGET display
(`UiScale.forDisplay`: the user's `ui_scale` setting wins, else `auto` from that display's own density
and shorter edge) and writes it before every session start and on every dock change, before the mode
file. `omarchy-watch.py` reads it every poll; while the monitor's scale differs it applies the file's
value with the runtime `hl.monitor` rule it already uses for the size (plus `GDK_SCALE`), and the
layer remedy restarts only the shell. Every size fit carries the wanted scale: a config reload puts
the start-time scale back (`monitors.lua`), and a fit that kept the reverted scale would leave the
docked desktop at scale 2 until the next poll. A missing file, or any value other than `1` or `2`,
changes nothing (an older host keeps the start-time scale). `OMDROID_SCALE_FOLLOW=0` in
`~/.config/omdroid/session.env` turns the guest side off. The restart itself is the stock `omarchy-restart-shell`, called from
`act()` with a rate limit (6 per rolling minute, 2 s apart); S1 measured that calling it twice from
a live session can leave a black screen on both builds, so the watcher does not amplify it: after a
restart the layers that are missing altogether, or no Quickshell process, mean the restart LOST the
shell (`act: shell restart FAILED (...): not retrying`), which writes
`~/.local/state/omdroid/shell-restart-failed` and ends the retry budget for that monitor size
(one attempt; a later size change is a new attempt). Only layers that exist and do not fit yet keep
the retry budget (3 actions per size, then `giving up`). Log lines: guest `ui-scale <a> -> <b>`,
`monitor scale <s> != ui-scale <w>: monitor fit ... scale <w>`; host `dock: ui-scale <n> written
display=<id> <w>x<h>@<dpi>` and `launch: ui-scale <n> written|kept`.

## 13. Android settings bridge

Android owns Wi-Fi, Bluetooth, the display and battery. While a guest session runs,
`ProotService` owns `AndroidControls`. Broadcast receivers (battery, Wi-Fi, power,
airplane mode), a Wi-Fi `NetworkCallback`, settings `ContentObserver`s and the
embedded display preference listener trigger snapshots. Resuming the embedded
activity refreshes its preference listener and state after initial handover or
a dock change. Callbacks coalesce for
50 ms; the host writes only when field values change. There is no polling loop.

The guest reads `/tmp/omdroid-android/state.json`, bound from the host prefix tmp.
Schema 2 has these fields; radio/power fields are nullable when the OS denies or
cannot supply a reading. No network names, addresses or paired-device lists appear.

| Field | Type | Meaning / units |
| --- | --- | --- |
| `schema` | integer | `2` |
| `sessionId` | string | Random UUID for this service/guest lifetime |
| `timestamp` | number | Unix milliseconds of the last changed snapshot; not a heartbeat |
| `wifiEnabled` | boolean or null | Android Wi-Fi radio enabled |
| `wifiConnected` | boolean or null | At least one Wi-Fi network reported by the callback |
| `bluetoothEnabled` | boolean or null | Android Bluetooth radio state |
| `airplaneMode` | boolean or null | Android airplane mode |
| `rotationLocked` | boolean or null | Omdroid activity orientation locked |
| `powerSave` | boolean or null | Android battery saver |
| `batteryPercent` | integer or null | Battery charge, 0–100 percent |
| `charging` | boolean or null | Android reports an external power connection |
| `full` | boolean or null | Android battery status is full |

**Liveness:** the host passes `OMDROID_ANDROID_SESSION` to the guest. A snapshot
is usable only when its validated session ID matches that environment value.
The file is watched, never periodically reloaded. An unchanged reading stays
valid in the same session; stopping unregisters listeners and removes the file.
The X server is in the Android host process, so a host crash also removes the
live display. A new service/guest gets a new ID and rejects any previous file.
This deliberately replaces the old ten-second timestamp lease: it is session
liveness, not a watchdog for a hung Android main thread. Old schema-1 snapshots
are rejected. APK overlay and host updates must ship together.

Taps atomically publish `requests/*.request`. A `FileObserver` wakes a streaming
consumer: at most 64 directory entries per batch, at least 100 ms between batches
under load, at most one accepted action per 500 ms. Surplus requests are discarded,
not queued for later launches. Each read is bounded to 33 bytes; accepted content
is at most 32 bytes and at most 30 seconds old, and predates neither this session
nor the current wall clock. Empty request directories are removed; nonempty
invalid directories are renamed out of the request namespace without traversing
guest-controlled trees. Incomplete `.new` writes are ignored; the writer removes
its own temporary file on failure.

State and request operations use `SecureDirectoryStream` handles opened with
no-follow semantics, including the bridge and requests directories. The state
temporary is exclusively created then atomically renamed. Renaming a shared
directory or planting a symlink cannot redirect file access into host-private
files. Unsupported secure-directory access fails closed. Shutdown does not join
the worker on the main thread; repeated publish failures log only once until a
successful write.

Accepted actions: `wifi`, `bluetooth`, `display`, `rotation-lock`, `rotation-auto`,
`volume`, `airplane`, `battery`, `settings`. No arbitrary intent, URL, arguments
or commands are accepted. Settings use `DisplayLaunch` on the embedded activity's
current display (the launcher's session display is the fallback), with its normal
platform-refusal fallback. Radio subpages dismiss the sheet before opening.
Rotation changes Omdroid's persisted activity orientation inside the sheet, with
no change to global Android rotation. Unknown tiles use a neutral glyph,
`available: false`, and IPC `enabled: null`. Outside proot, Linux backends remain.
The Android probe path skips Linux radio, power-profile, modem and systemd probes;
software theme, idle-indicator, night-light and recorder checks remain available.

Only normal `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` permissions are needed;
no secure-settings, location, nearby-device or accessibility grant is added.
The extra-key preferences activity is enabled and non-exported, with separate
Omdroid/Termux preference resource names to avoid Android navigation crashes.

## 14. Guest time zone and dialog utilities

Before starting the broker or desktop, `ProotService` synchronizes the guest's
`/etc/localtime` with Android's named time zone through `GuestTimezone`. The
relative symlink points into the guest's installed tzdata, so the clock, calendar
and applications share local time and daylight-saving rules. Missing or invalid
zone data preserves the previous configuration. A host time-zone change is
applied when the next guest session starts; the guest does not run a time daemon.

`/etc/phoshdroid/guiutils/bin` is on the session PATH. These actual Hyprland GUI
utilities use the upstream Qt implementation, built against the pinned image's
libraries with software rendering. The newer hyprtoolkit implementation requires
dmabuf v4 and DRM render nodes that this guest cannot provide. This avoids a partial
system upgrade. Both the rootfs packager and APK overlay carry them, so updated
installations receive the same runtime dependency as fresh installations.
See `rootfs/build-guiutils.sh` and the accompanying provenance and license.

The overlay also carries the standard `xdg-terminal-exec` script, used only if
the guest has no existing terminal execution helper. `omdroid-app-defaults.py`
selects installed Nano when the image lacks Neovim and no editor choice exists.
It migrates only the exact stock Chromium flags file to native Wayland with
software rendering, saving an exclusive `.before-omdroid-wayland` backup first.
Customized flags, browser profiles and existing editor choices are preserved.
Software browser rendering trades acceleration for compatibility with this
nested compositor; it does not change the guest compositor's GPU configuration.
