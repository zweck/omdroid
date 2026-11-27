# Omamobile plugins vendored into Omdroid

Upstream: https://github.com/derekross/omamobile
Commit ported: `b5baaea7a96733a587b53e8669fde4301769fd5f` (shallow clone, 2026-11-24)
Licence: MIT (`LICENSE` in this directory is upstream's, unchanged).

## What was copied

From `packages/omamobile-shell/`:

- `plugins/omamobile.bar/` — phone status bar (bar kind)
- `plugins/omamobile.quicksettings/` — pull-down quick settings sheet (panel, keepLoaded)
- `plugins/omamobile.launcher/` — dock, home pill and app drawer (panel, keepLoaded)
- `plugins/omamobile.back/` — edge-swipe back gesture (panel, keepLoaded)
- `plugins/omamobile.switcher/` — recent apps as cards (panel, keepLoaded)
- `bin/omamobile-launch` — launches an app, a command or a URL on a new workspace
- `bin/omamobile-home` — the back gesture's "go home"

Not copied: `omamobile.home`, `omamobile.keyboard`, `omamobile.fingerprint`,
`omamobile.call`, `omamobile.mobilenetwork`, `omamobile.voice`, `omamobile.access`,
`omamobile.update`, `omamobile-settings`, `omamobile-owl-widgets`, `omamobile-apps`,
the squeekboard layouts, the OMARCHY patch series and the systemd units. Each one
stands on hardware, systemd or a service proot does not have (see the R5 research,
`docs/omarchy/r5-omamobile-research.md` section 1).

## Local changes

**Upstream files are byte-identical to the commit above, except the ones listed under
"Changed upstream files".** Everything else Omdroid needed was added beside them:

| Added file | Why |
|---|---|
| `bin/omamobile-browser-setup` | stub; upstream fits a phone browser's window, there is no browser profile to fit here. Runs at every bar load, must not fail. |
| `bin/omamobile-cell-watch` | stub; no modem and no ModemManager inside proot. Prints nothing, so the bar shows no carrier icon instead of a restarting process that errors. |
| `bin/omamobile-call-watch` | stub; no `omatel-agent` and no systemd user units. Same reason. |
| `SOURCE.md` | this file |
| `plugins/omdroid.android/` | Shared Android state reader, Qt-translatable labels, and bounded settings request helper. Android owns the hardware hidden by proot. |

### Changed upstream files

One line per upstream file Omdroid edits, with the device evidence that forced it
(`docs/omarchy/r5-mobile-ui-device-report.md`).

| File | Change | Why |
|---|---|---|
| `plugins/omamobile.switcher/Switcher.qml` | A card's `ScreencopyView` sits in a `Loader` and is created only when `OMARCHY_PROOT` is not `1`; under proot the card shows the app's icon and the window's title instead (`liveCopies`, `iconFor`). | Hyprland under proot offers `zwp_linux_dmabuf_v1` at version 3. Creating a `ScreencopyView` (one per open window, even with the switcher closed) makes Quickshell send `get_default_feedback`, a version 4 request: `wl_display` error, "Wayland connection experienced a fatal error", and the whole shell exits the moment any app opens. |
| `plugins/omamobile.launcher/Launcher.qml` | The search pill's and the home pill's `TapHandler.longPressThreshold` is `root.voice ? 0.45 : 3600`: no long press unless the voice plugin is loaded. Without the voice plugin the one-time pill hint says only what works ("Swipe up on the pill to go home · swipe up and hold for recent apps") under the done marker `omdroid-pill-hint` (`pillHintText`, `pillHintMarker`). | `omamobile.voice` and `omamobile.keyboard` are not ported. Under simulated touch every swipe starts with a hold, so the long press fired `voice start` into nothing before each drag (device log, round 5); the stock hint ("Hold the pill to talk · double-tap for the keyboard") named two things that do nothing here. |
| `plugins/omamobile.quicksettings/QuickSettings.qml` | `tileColumns` divides the sheet width by 240 px instead of 180. The page dots are finger-sized targets (`Math.max(44, Style.space(44))` each, a tap goes to that page) with a 10 px dot and the current page as a wide accent pill. The tile-state pass no longer runs from `Component.onCompleted`: the last result is read from `~/.cache/omamobile/tile-state` with a `FileView`, and the pass runs 8 s after load (at once with `OMDROID_QS_PROBE_AT_LOAD=1`). | At 16 px type on the 1600 px tablet, four columns cut most tile names (`Bluet…`, `Do not distu…`); three columns fit them. A wider sheet with four columns was tried and clipped its fourth column. Upstream's 7 px dots could not be hit or seen on the tablet. The pass is serial `timeout 3` probes, ~7 s on the tablet, and the whole shell paid it at every start: the bar, dock and back strips did not map until it exited, so the mobile UI took 10.7 s against the desktop UI's 3.5 s (R11 S2, same build, same hour). Opening the sheet still re-probes through `runOpenRefresh`. |
| `plugins/omamobile.quicksettings/tile-state.sh` | All nine probes run at once, each into its own file, printed in a fixed order afterwards; the combined output is also written to `~/.cache/omamobile/tile-state` (override with `OMAMOBILE_TILE_STATE_CACHE`). | Serial `timeout 3` calls summed to ~7 s (measured 6.6-7.1 s on the tablet). Parallel, the pass costs the slowest probe instead of their sum, and the cache means a shell start needs no process at all. |
| `plugins/omamobile.bar/Bar.qml` | The cell and call watchers run only while the plugin they stream for is vendored (`omamobile.mobilenetwork`, `omamobile.call`), and their 5 s restart is gated the same way. | Neither plugin is ported, so both watchers are Omdroid stubs that exit at once; upstream's restart-on-exit then forked a dying process every 5 s for the whole session (R11 S2 steady-state count). The gate makes them one-shot: no backend, no retry. |
| `plugins/omamobile.launcher/Launcher.qml` | The `omamobile-osk-watch` Process and its 3 s restart run only while `omamobile.keyboard` is vendored. | That binary ships with the keyboard plugin, which is not ported: the Process could not exec at all and its timer relaunched a failing exec every 3 s for the whole session (R11 S2 steady-state count). |
| `plugins/omamobile.launcher/Launcher.qml` | The drawer window unmaps while it is closed: `visible: drawerAlwaysMapped \|\| opened \|\| warm \|\| settle.running`, `open()` maps it and waits one frame (24 ms) before the slide, the dock's tap maps it before its IPC summon, and `OMDROID_DRAWER_MAPPED=1` restores the always-mapped window. | Mapped for the life of the shell it held two 22.97 MiB `wl_shm` buffers — 45.9 MiB, 11.5 MiB of PSS a side — for a drawer most sessions never open (the memory stream measured it on the R10 build). Unmapping it is only safe with the pre-map: mapping on open alone, a new surface's first frames drew before the output's scale applied (the grid at 3x, then a jump), which is why upstream keeps it mapped. |

The bar and quick settings also use `omdroid.android` when `OMARCHY_PROOT=1`.
Wi-Fi and Bluetooth show Android's state and open its connection/pairing pages;
battery, airplane mode, and power saver use Android status. Rotation lock directly
sets the embedded display's persisted orientation and keeps the sheet open;
unlock follows Android's device rotation policy. Display
and volume buttons open Android controls instead of unavailable Linux sliders.
Missing or stale fields remain unknown. This addresses the mobile sheet showing
"NetworkManager not running", "No adapter", "No sensor", and "No backlight" on
an Android tablet with working radios and display controls. See the Android
settings bridge section of `docs/omarchy/launch-contract.md`.

Two upstream frictions were resolved without touching upstream code:

- `omamobile-launch` starts apps through `uwsm-app`. The guest image already ships
  `/usr/local/bin/uwsm-app`, which drops the `--` and execs the rest when
  `OMARCHY_PROOT=1` (it is Omarchy-Android's own shim), so the app path works
  unchanged. No shim was added.
- `omamobile-launch` is `#!/usr/bin/python3`; the image has python3.

## How they reach the guest

`rootfs/overlay/etc/phoshdroid/omamobile/` is copied into the image by
`rootfs/package-omarchy-rootfs.sh` and into an existing install by the APK's
`syncGuestOverlay` task (both list it). At session start `omdroid-ui.sh` symlinks
the five plugin directories into `~/.config/omarchy/plugins/` and symlinks the
`bin/` scripts into `/usr/local/bin/`, then `omarchy-plugin-index.sh` builds the
plugin index the shell reads under proot.
