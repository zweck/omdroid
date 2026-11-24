# Launch contract: Omarchy guest on the Phoshdroid host

Both the host-side (Kotlin/Weston) and guest-side (session script) work must
obey this contract. Paths are app-relative; `$PREFIX` = `files/usr`,
`$ROOTFS` = `$PREFIX/var/lib/proot-distro/installed-rootfs/omarchy`.

## Host layer (bionic, runs in the app's Termux prefix)

```bash
DISPLAY=:0 \
XDG_RUNTIME_DIR=$PREFIX/tmp/omarchy-runtime \
LD_LIBRARY_PATH=$PREFIX/lib:$PREFIX/lib/weston \
WESTON_MODULE_MAP=kiosk-shell.so=$PREFIX/lib/weston/kiosk-shell.so;x11-backend.so=$PREFIX/lib/libweston-16/x11-backend.so \
$PREFIX/bin/weston -B x11 --renderer=pixman --shell=kiosk \
  --socket=wayland-omarchy --width=<W> --height=<H> --scale=1 \
  --idle-time=0 --log=$PREFIX/tmp/weston.log
```

- `bin/weston` is a stub needing `libexec_weston.so` from `lib/weston`; its
  RUNPATH is Termux's prefix, so `LD_LIBRARY_PATH` must include both dirs.
- `--shell=kiosk` loads `kiosk-shell.so` from the compiled-in module dir, so it
  must be in `WESTON_MODULE_MAP` too (entries separated by `;`).
- The weston **binary** ships as `jniLibs/arm64-v8a/libweston-bin.so` and runs
  from nativeLibraryDir; Android blocks exec from app data (W^X).

- The Termux prefix ships `weston` 16.0.2 + closure (see
  `rootfs/fetch-termux-packages.py`), so the module is
  `libweston-16/x11-backend.so` — **not** upstream's patched
  `libweston-14/x11-backend.so`, which cannot load into weston 16.
- Use the stock module until the refresh-timing patch is ported to weston 16.
- `XDG_RUNTIME_DIR` is created by the app (mode 0700) and is the only directory
  whose sockets both sides see.

## Guest layer (Arch ARM proot, uid 1000)

proot is launched with `-i 1000:1000` (no fake root, no `su`), binds:

| host | guest |
| --- | --- |
| `$PREFIX/tmp` | `/tmp` |
| `$PREFIX/tmp` | `/dev/shm` |
| `$PREFIX/tmp/omarchy-runtime` | `/run/user/1000` |
| `$ROOTFS/etc/phoshdroid/fake-proc/*` | `/proc/{stat,meminfo,loadavg,uptime,cpuinfo}` |
| `/dev/null` | `/proc/bus/pci/devices` |

Guest environment (all required):

```
HOME=/home/omarchy            USER=omarchy            LOGNAME=omarchy
XDG_RUNTIME_DIR=/run/user/1000
WAYLAND_DISPLAY=wayland-omarchy
XDG_SESSION_TYPE=wayland      XDG_CURRENT_DESKTOP=Hyprland
XDG_SESSION_DESKTOP=Hyprland  LANG=C.UTF-8            LC_ALL=C.UTF-8
OMARCHY_PATH=/usr/share/omarchy   OMARCHY_PROOT=1
OMARCHY_GPU_MODE=kgsl         OMARCHY_SCALE=<1|2>
OMARCHY_OUTPUT_WIDTH=<W>      OMARCHY_OUTPUT_HEIGHT=<H>   OMARCHY_REFRESH_MHZ=<mHz>
PATH=/opt/omarchy-android/hyprland/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
XDG_DATA_DIRS=/opt/omarchy-android/hyprland/share:/usr/local/share:/usr/share
LD_LIBRARY_PATH=/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib
LIBGL_DRIVERS_PATH=/opt/omarchy-android/mesa/root/usr/lib/dri
MESA_LOADER_DRIVER_OVERRIDE=kgsl
VK_ICD_FILENAMES=/opt/omarchy-android/mesa/freedreno_icd.json
FD_KGSL_ENABLE_DMABUF=1
AQ_NO_KMS_REQUIREMENT=1  AQ_NO_MODIFIERS=1  AQ_WAYLAND_REFRESH_MHZ=<mHz>
HYPRLAND_ANDROID_DMABUF=1  HYPRLAND_SHM_DAMAGE=1
HYPRLAND_EGL_NO_MODIFIERS=1  HYPRLAND_EGL_SURFACELESS=1
HYPRLAND_NO_SD_NOTIFY=1  HYPRLAND_NO_SD_VARS=1  HYPRLAND_NO_RT=1
QT_QPA_PLATFORM=wayland  GDK_BACKEND=wayland  SDL_VIDEODRIVER=wayland
XWAYLAND_NO_GLAMOR=1  NO_AT_BRIDGE=1  QT_ACCESSIBILITY=0  GTK_A11Y=none
PULSE_SERVER=tcp:127.0.0.1:4715        (only when the audio bridge is up)
```

Session command (guest `/etc/phoshdroid/start.sh`, no root steps needed):

```bash
exec dbus-run-session \
  --config-file=/home/omarchy/.config/omarchy/proot-session-bus.conf \
  bash --noprofile --norc -lc \
  'exec /opt/omarchy-android/hyprland/bin/Hyprland --config /home/omarchy/.config/hypr/hyprland.lua'
```

The `/opt` Hyprland and aquamarine are the patched KGSL builds; `/usr/bin/Hyprland`
and `/usr/lib/libaquamarine*` are stock and must never come first on PATH or
LD_LIBRARY_PATH. `hyprland.lua` already starts the shell under `OMARCHY_PROOT=1`
(flock-guarded) — the session script must not start `omarchy-shell` itself.

## Order

1. X server up (`:0`, Xlorie, in-process).
2. Weston up; wait for `$RUNTIME/wayland-omarchy` (socket).
3. proot up; wait for `$RUNTIME/hypr/<instance>/` (Hyprland instance dir).
4. Display activity.

Teardown (and every display-shift restart) kills Weston, the virgl server if any,
and proot, then deletes stale `.X0-lock`, `.X11-unix/X0`, `wayland-*`,
`hypr/*` and the readiness log.

## Known gaps

- No `virpipe` driver in the guest payload: GPU modes are kgsl (Adreno) or
  software (swrast/pixman). Do not offer virgl.
- `hyprlock`/`hypridle`/`hyprsunset` are not installed; idle/lock live in
  `~/.config/omarchy/shell.json` (Quickshell).
- `omarchy-proot-audio` is missing from the payload; the session script must
  provide it or audio commands fail.
- Guest has no `/run/user/1000` of its own — it exists only as the bind mount.
