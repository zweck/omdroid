# Device bring-up report
HEAD: abbd055   Device: <tablet-serial>   Model that ran: deepseek-flash

## STATUS TABLE
| Item | Status | Proof (numbers, command, log line) |
| A bar | PASS | From the final cold start (cold-final-b), with no manual in-session command: bar band at screen rows 140-191 `modal=(26,27,37) distinct=173 INK=1.58%`, thirds left 1.43 % / centre 1.90 % / right 1.57 %; `j/layers` level 2 `omarchy-bar` 1600x26 owned by quickshell. Same after an `omarchy-restart-shell` (A-index: distinct 421, INK 1.58 %). |
| B wallpaper | PASS | `j/layers` level 0 `omarchy-background` 1600x888 (= 1776/2), `shot.sh` DESKTOP INK 91.35 %, and a row scan of cold-final-b finds wallpaper from row 192 down with **no** flat `(17,17,17)` band anywhere in the desktop. |
| C cold start | PARTIAL (desktop PASS, hand-over FAIL) | `./cycle-tablet.sh 60` -> index rebuilt by `start.sh` with no manual step (`21016` bytes, `37` firstparty manifests), `fit-x11: 0x200004 1024x768@0,0 -> 3200x1776@0,0`, `j/monitors` 3200x1776@120 scale 2, layers background+bar, DESKTOP INK 91.35 %, bar INK 1.58 %. But at +60 s the top activity was `com.android.settings/.Settings$AppManageExternalStorageActivity` (shot `cold-final-a`: `HINT: LIGHT SCREEN`), and the desktop only appeared after the app's own hand-over intent (`am start ... MainActivity --ei phoshdroid_top_padding_px 160 --ei phoshdroid_bottom_padding_px 200`). Cause in D4. |
| D window / keyboard / touch / hand-over | PASS / PASS / PASS / FAIL + UNTESTED patch | Window: `hl.dsp.exec_cmd("foot")` -> `j/clients` lists `class foot` 1576x838 at 12,38, `shot.sh` DESKTOP modal flips from wallpaper to foot's `(28,28,38)` with distinct 939. Keyboard: typed `hello` via `adb shell input text hello` + `KEYCODE_ENTER` into `foot /etc/phoshdroid/read-t.sh` (`read -r line; printf %s "$line" > /tmp/typed.txt`) -> `cat /tmp/typed.txt` = `hello` (5 bytes). Touch: `input tap 1600 1000` -> `j/cursorpos` y 192 -> 429 (device 1000 - 160 top gap = 840 physical = 420 logical). Hand-over FAIL, cause + patch below. |
| E geometry | PASS (no change needed; one host-visible artefact) | With the launcher's own intent (paddings 160/200): X root `xlist.py root (0,0,3200,1776)`, cage window `0x200004 3200x1776`, `j/monitors` `3200x1776` (`availableModes: ["3200x1776@120.00Hz"]`), background layer 1600x888 = 1776/2 -> all three agree. Desktop occupies screen rows 140-1904, the bottom 200 px gap holds Termux:X11's extra-key row (rows 1936-2136, `INK=1.07%`, no desktop content behind it). The 3200x2136 reading in the brief comes from `am start .../MainActivity` **without** the padding extras: the view is then full-screen, Xlorie's root grows to 2136 (`xlist`), Hyprland follows to 2136, and the already-created background layer stays 888, leaving a flat `(17,17,17)` band for rows 1776-1966 - measured in cold-1b/D-foot2. |
| F bake + clean | PASS | Fixes are in the overlay and the packager (table below) and were proven by a cold start; diagnostics reverted: `enable_stdout_logs = false` in the guest `~/.config/hypr/looknfeel.lua` (pulled to `~/.hermes/cache/scratch/omarchy/worker/orig/looknfeel.lua`, patched, pushed back), `/etc/phoshdroid/sway.conf` deleted, my test script `/etc/phoshdroid/read-t.sh` and the guest `/tmp` scratch removed. `sway` is not in `package-omarchy-rootfs.sh` (grep: no match) - it exists only on this device; not added. |

## Root causes (one paragraph each, with the log line or number that proves it)

1. **A and B are one bug: the shell loads with zero plugins.** In proot mode
   (`OMARCHY_PROOT=1`, exported by the host and by `start.sh`) `PluginRegistry.qml` never
   runs its own manifest scanner - a QML `Process` scan returns an empty stream under proot
   - and reads `$XDG_RUNTIME_DIR/omarchy-plugin-scan.txt` instead
   (`readonly property bool prootMode: Quickshell.env("OMARCHY_PROOT") === "1"`).
   Upstream `omarchy-launch-shell` builds that file only inside
   `if [[ $(uname -r) == *PRoot* ]]`. This guest runs proot-distro, so `uname -r` is
   `6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k` (measured via `sx.sh`) and the file was
   never written (`ls: cannot access '/run/user/1000/omarchy-plugin-scan.txt'`). Zero plugins
   means the bar surface draws only its own background (`baseline2`: BAR `distinct=1
   INK=0.00%`) and the `background` plugin that paints the wallpaper is never loaded
   (DESKTOP `modal=(17,17,17)` = Hyprland's default fill). Building the index and restarting
   the shell fixed both at once: BAR distinct 1 -> 421, DESKTOP INK 0.02 % -> 88.47 %,
   `j/layers` gains level 0 `omarchy-background`. The quickshell log was clean throughout
   (6 lines, `Configuration Loaded`), which is why this was invisible from the log.

2. **D4 (cold start does not show the desktop) is a real bug, not an adb artefact.**
   `LauncherActivity.onCreate` calls `requestNotificationPermission()`, which ended in
   `requestAllFilesAccess()`; with the appop unset (`appops get com.phoshdroid.app
   MANAGE_EXTERNAL_STORAGE` -> `No operations. Default mode: default`) that starts the system
   all-files page (logcat 17:13:38.330 `START ... action=android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
   ... callingPackage com.phoshdroid.app (BAL_ALLOW_VISIBLE_WINDOW)`). The system activity
   stops LauncherActivity, so the Android 14 BAL guard at `LauncherActivity.kt:549`
   (`bootstrap finished while backgrounded; deferring MainActivity launch`) skips the
   hand-over on every cold start; `onResume` only retries when the user comes back to the
   launcher. Reproduced twice (17:13 and 17:22 cold starts). Nothing in the bootstrap needs
   external storage: `OMARCHY_SHARE=storage` is only set when the "Mount /sdcard" preference
   (`bind_sdcard`) is enabled.

3. **The 3200x2136 geometry in the brief is an artefact of starting MainActivity without its
   padding extras.** `MainActivity` applies `phoshdroid_top_padding_px` /
   `phoshdroid_bottom_padding_px` as margins (`MLP.topMargin/bottomMargin`); with no extras
   the X view is full-screen, Xlorie grows the root to the raw 2136 and Hyprland follows,
   so the desktop renders 360 px taller than the host's usable area and the background layer
   created at 1776/2 stays stale (flat band). With the launcher's real intent the three
   numbers agree at 1776 and no desktop pixel sits behind the extra-key row.

## Changes (repo file -> guest path -> how the packager installs it)

| Repo file | Guest path | Installed by |
| --- | --- | --- |
| `rootfs/overlay/etc/phoshdroid/omarchy-plugin-index.sh` (new) | `/etc/phoshdroid/omarchy-plugin-index.sh` | `package-omarchy-rootfs.sh`: `cp -a "$OVERLAY_DIR/etc/phoshdroid/omarchy-plugin-index.sh" "$WORK/stage/etc/phoshdroid/omarchy-plugin-index.sh"` and it is listed in the `chmod 755` line |
| `rootfs/overlay/etc/phoshdroid/start.sh` | `/etc/phoshdroid/start.sh` | existing `cp -a` line; new call `bash /etc/phoshdroid/omarchy-plugin-index.sh` after `mkdir -p /run/user/1000` |
| `rootfs/package-omarchy-rootfs.sh` | (packager) | new `cp -a` + `chmod 755` entry for the new script |
| `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt` | (APK) | **UNTESTED** - cannot run without an APK rebuild: `requestNotificationPermission()` no longer calls `requestAllFilesAccess()`, so the launch path stops opening the all-files Settings page and the hand-over guard no longer trips. `requestAllFilesAccess()` is kept and should be called from the "Mount /sdcard" (`bind_sdcard`) preference instead. |

Device-only edits (not baked, intentional): `~/.config/hypr/looknfeel.lua` restored to
`enable_stdout_logs = false`; `/etc/phoshdroid/sway.conf` deleted.

## Screenshots for the parent to view (path + what it should show)

- `~/.hermes/cache/scratch/omarchy/shots/baseline2.png` - before: flat (17,17,17) desktop, empty bar band.
- `~/.hermes/cache/scratch/omarchy/shots/A-index.png` - after the index fix + shell restart: wallpaper and a populated bar.
- `~/.hermes/cache/scratch/omarchy/shots/D-foot2.png` - foot window over the desktop.
- `~/.hermes/cache/scratch/omarchy/shots/E-pad.png` - launcher intents with paddings: desktop 1776 tall, black 160 px top gap, bar at rows 128-180.
- `~/.hermes/cache/scratch/omarchy/shots/cold-final-a.png` - what a cold start shows by itself: the Settings all-files page (the D4 bug).
- `~/.hermes/cache/scratch/omarchy/shots/cold-final-b.png` - the same boot after the app's own hand-over intent: black top gap, bar at rows 140-191, wallpaper to row ~1904, extra-key row at the bottom. This is the headline image; the bar text is small (26 logical px at scale 2).

## Not verified / left for the parent (APK rebuild, Kotlin, anything you could not observe)

- The `LauncherActivity.kt` change is **UNTESTED** (needs an APK rebuild + cold start). If it
  works, a cold start should land on the desktop with no `am start`.
- Until that ships, the only way to reach the desktop after a cold start is
  `am start -n com.phoshdroid.app/com.termux.x11.MainActivity --ei phoshdroid_top_padding_px 160 --ei phoshdroid_bottom_padding_px 200`.
  Without the two extras the desktop grows to 3200x2136 and its bottom 360 px sit behind the
  gaps/extra-key row (this is what the brief's "X root 2136" measurement was).
- Quickshell still logs, every 2 s, `Process failed to start ... Command:
  QList("omarchy-proot-audio", "status")` - no such binary exists in the guest, so the bar's
  audio widget has no data. NetworkManager and polkit listeners also fail (no system services
  under proot); both are expected here and did not affect the bar or the wallpaper.
- A third-party plugin installed later will not appear until the session restarts: the index
  is built once per session by `start.sh` (same limitation upstream's launcher has).
- `rootfs.bin`/APK were not rebuilt (out of scope by the brief).

## Log (short, appended as you go)
- baseline: `hc.sh` live (3200x2136, scale 2, reserved [0,26,0,0]), one layer level 2 `omarchy-bar` 1600x26, `j/clients []`, shot BAR INK 0.00 % / DESKTOP (17,17,17).
- A: quickshell log clean; found the missing proot plugin index; built it (37 manifests, 21016 B) + `omarchy-restart-shell` -> BAR distinct 421 INK 1.58 %, DESKTOP INK 98.76 %, level 0 `omarchy-background` appears.
- B: same change; `j/layers` level 0 1600x1068, DESKTOP INK 88-99 %.
- Bake: overlay script + `start.sh` call + packager lines; pushed to the guest, index deleted, cold start -> `start.sh` rebuilt it, cold-1b BAR INK 1.58 % / DESKTOP INK 88.47 %; top activity was the Settings all-files page.
- D: foot window listed and rendered; `hello` arrived in `/tmp/typed.txt`; tap moved the cursor 192 -> 429; hand-over cause read out of logcat + `LauncherActivity.kt`.
- E: with paddings 160/200 the root, cage and Hyprland all read 1776 and the desktop ends above the extra-key row; without them all read 2136 and a stale 888-high background layer leaves a flat band.
- F: `enable_stdout_logs = false`, `sway.conf` and my test artefacts removed; final cold start on the cleaned device: index rebuilt (21016 B, 37 manifests), monitors 3200x1776@120, layers background 1600x888 + bar 1600x26, bar INK 1.58 % (1.43/1.90/1.57), DESKTOP INK 91.35 %, no flat band.
