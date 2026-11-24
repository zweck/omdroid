# Device bring-up and debugging (SM-F976B)

Adb target over wireless debugging; adjust the serial as it changes.

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
D=$(adb devices | awk 'NR==2{print $1}')
```

## Install and start

```bash
adb -s "$D" install -g app/build/outputs/apk/debug/app-debug.apk
adb -s "$D" shell am force-stop com.phoshdroid.app
adb -s "$D" shell am start -n com.phoshdroid.app/.LauncherActivity
```

The app is a debug build, so `run-as` works and the whole data dir is readable.

## Where the logs are

| file (app-relative) | what |
| --- | --- |
| `files/usr/tmp/weston-launch.log` | weston stdout/stderr (module load, X connect errors) |
| `files/usr/tmp/weston.log` | weston's own `--log` |
| `files/usr/tmp/omarchy-session.log` | guest `start.sh` trace + Hyprland output |
| `files/usr/tmp/proot-exit.log` | proot exit code and captured stdout |
| `files/usr/tmp/omarchy-runtime/` | Wayland socket + `hypr/<instance>` readiness dir |

```bash
adb -s "$D" shell run-as com.phoshdroid.app cat files/usr/tmp/weston-launch.log
adb -s "$D" shell run-as com.phoshdroid.app cat files/usr/tmp/omarchy-session.log
adb -s "$D" shell run-as com.phoshdroid.app ls -l files/usr/tmp/omarchy-runtime
adb -s "$D" logcat -d -s Phoshdroid ProotService Weston
```

## Extraction progress

First launch extracts `bootstrap.bin` (14657 members) and `rootfs.bin`
(83159 members, ~4.1 GB) with the Kotlin extractor:

```bash
adb -s "$D" shell run-as com.phoshdroid.app ls \
  files/usr/.extraction_complete \
  files/usr/var/lib/proot-distro/installed-rootfs/omarchy/.extraction_complete
```

## Screen and input

```bash
adb -s "$D" exec-out screencap -p > /tmp/omarchy.png
adb -s "$D" shell input swipe 540 2200 540 900 300   # open Omarchy menu? (depends on bind)
```

## Rerunning the whole chain cleanly

```bash
adb -s "$D" shell am force-stop com.phoshdroid.app          # kills X server, weston, proot
adb -s "$D" shell run-as com.phoshdroid.app rm -rf files/usr/tmp/omarchy-runtime \
  files/usr/tmp/omarchy-session.log files/usr/tmp/weston.log
adb -s "$D" shell am start -n com.phoshdroid.app/.LauncherActivity
```

## Known first-boot risks

- `weston` needs its module map and both library dirs (see CONTRACT.md); a
  missing `kiosk-shell.so` entry fails after the backend loads.
- Hyprland's patched binary must be the `/opt` one; `/usr/bin/Hyprland` is stock
  and has no KGSL path.
- The guest has no systemd: `omarchy-shell` is started by `hyprland.lua`, and
  any `systemctl` call in omarchy scripts fails by design.
