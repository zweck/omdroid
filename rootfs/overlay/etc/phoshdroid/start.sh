#!/bin/bash
# Omarchy guest session. Runs as uid 1000 inside proot: no root prep here.
# Order: cage (nested Wayland parent over Xlorie) -> Hyprland -> Omarchy shell
# (started by the Hyprland lua config when OMARCHY_PROOT is set).
#
# cage/wlroots is the parent because its X11 backend presents through the X
# Present extension (fd-passing SHM); the weston pixman x11 backend hard-requires
# SysV SHM, which the Android app sandbox blocks (shmget -> ENOSYS), so it dies
# with "x11shm: failed to allocate SHM segment".
exec > /tmp/omarchy-session.log 2>&1
set -x

export HOME=/home/omarchy
export USER=omarchy
export LOGNAME=omarchy
export XDG_RUNTIME_DIR=/run/user/1000
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=Hyprland
export XDG_SESSION_DESKTOP=Hyprland
export WAYLAND_DISPLAY=wayland-omarchy
export OMARCHY_PATH=/usr/share/omarchy
export OMARCHY_PROOT=1
export LANG=C.UTF-8 LC_ALL=C.UTF-8

# Geometry arrives from the host as OMARCHY_OUTPUT_*; the Xlorie variables are
# the fallback for a manual launch from a shell.
: "${OMARCHY_OUTPUT_WIDTH:=${XLORIE_WIDTH:-1080}}"
: "${OMARCHY_OUTPUT_HEIGHT:=${XLORIE_HEIGHT:-2004}}"
: "${OMARCHY_REFRESH_MHZ:=120000}"
: "${OMARCHY_SCALE:=2}"
export OMARCHY_OUTPUT_WIDTH OMARCHY_OUTPUT_HEIGHT OMARCHY_REFRESH_MHZ OMARCHY_SCALE

# The /opt builds are the Android-patched ones (KGSL buffer path); the /usr
# copies are stock Arch builds and must never come first.
export PATH=/opt/omarchy-android/hyprland/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export XDG_DATA_DIRS=/opt/omarchy-android/hyprland/share:/usr/local/share:/usr/share
export LD_LIBRARY_PATH=/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib

# GPU path: the patched Mesa/Hyprland/aquamarine builds talk KGSL directly.
# ProotCommandBuilder already passes this block from the host; it is repeated
# here so the script is self-contained for a manual launch from a shell, where
# Mesa would otherwise pick its X11 DRI3/Zink path (no DRM node inside proot).
export OMARCHY_GPU_MODE=kgsl
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export LIBGL_DRIVERS_PATH=/opt/omarchy-android/mesa/root/usr/lib/dri
export VK_ICD_FILENAMES=/opt/omarchy-android/mesa/freedreno_icd.json
export FD_KGSL_ENABLE_DMABUF=1
export AQ_NO_KMS_REQUIREMENT=1 AQ_NO_MODIFIERS=1
export AQ_WAYLAND_REFRESH_MHZ="$OMARCHY_REFRESH_MHZ"
export HYPRLAND_ANDROID_DMABUF=1 HYPRLAND_SHM_DAMAGE=1
export HYPRLAND_EGL_NO_MODIFIERS=1 HYPRLAND_EGL_SURFACELESS=1
export HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_SD_VARS=1 HYPRLAND_NO_RT=1
export QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland SDL_VIDEODRIVER=wayland
export XWAYLAND_NO_GLAMOR=1 NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0 GTK_A11Y=none

mkdir -p /run/user/1000
rm -f /run/user/1000/wayland-omarchy /run/user/1000/wayland-omarchy.lock

# Build the shell plugin index for this session. In proot mode Quickshell reads
# this file instead of scanning, and upstream writes it only when uname -r
# reports PRoot, which proot-distro does not. Without it the shell loads with
# zero plugins: an empty bar and no wallpaper.
bash /etc/phoshdroid/omarchy-plugin-index.sh

# cage: one fullscreen kiosk surface on the Xlorie root; pixman renderer because
# an Android app has no DRM/GBM node, x11 backend because that is what the host
# display server speaks.
export DISPLAY=:0
export WLR_BACKENDS=x11
export WLR_RENDERER=pixman

# Debug switch: /etc/phoshdroid/trace turns on Hyprland/aquamarine tracing.
if [ -e /etc/phoshdroid/trace ]; then
    export HYPRLAND_TRACE=1 AQ_TRACE=1
fi

# Xlorie has no window manager, so the cage x11 window would stay at the wlroots
# default of 1024x768 in the corner of the screen. This poller resizes it to
# the X root (each side rounded down to even) and follows later root size
# changes; wlroots turns the resize into the new output mode. 0.25 s so a live
# size change reaches cage quickly.
python3 /etc/phoshdroid/fit-x11.py "$DISPLAY" 0.25 > /tmp/fit-x11.log 2>&1 &

# A marker left behind by the previous session would hand the host over early.
rm -f "$XDG_RUNTIME_DIR/omarchy-ready"

# Ready marker + layer-fit watcher: writes the marker once the desktop is really
# drawn, then keeps the shell layers in step with the monitor after a live root
# size change. Logs to /tmp/omarchy-watch.log.
python3 /etc/phoshdroid/omarchy-watch.py > /tmp/omarchy-watch.log 2>&1 &

# monitors.lua forces a mode when these hints are set, but they are predictions
# from before the surface existed; the X root is the truth and Hyprland follows
# its parent (cage) on its own. Worse, a forced mode is re-applied by a config
# reload long after the root changed, which leaves the monitor larger than cage.
# So the hints do not reach Hyprland (nothing else in the guest reads them).
unset OMARCHY_OUTPUT_WIDTH OMARCHY_OUTPUT_HEIGHT

# session-child.sh needs the real X display name; pass it in a variable of our
# own so a stray DISPLAY in the child environment cannot point the one-shot
# resize at a display that does not exist (that cost a 60 s boot once).
export PHOSHDROID_XDISPLAY="$DISPLAY"

# No systemd under proot: Hyprland is executed directly, and its own config
# starts the shell once (flock-guarded) when OMARCHY_PROOT is set. The cage client
# is a tiny wrapper that waits for the resize above before starting Hyprland.
exec dbus-run-session \
    --config-file=/home/omarchy/.config/omarchy/proot-session-bus.conf \
    bash --noprofile --norc -lc \
    'exec cage -- /etc/phoshdroid/session-child.sh'
