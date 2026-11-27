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
# These utilities are built against the pinned image libraries. Carry them in
# the APK overlay so existing installations gain the dependency as well.
chmod 755 /etc/phoshdroid/guiutils/bin/hyprland-* /etc/phoshdroid/guiutils/libexec/hyprland-*
export PATH="/etc/phoshdroid/guiutils/bin:$PATH"
export XDG_DATA_DIRS=/opt/omarchy-android/hyprland/share:/usr/local/share:/usr/share
# /etc/phoshdroid/lib comes first: it holds the APK-synced aquamarine with touch
# (libaquamarine.so.13, see lib/README.md), which wins over the image copy below.
export LD_LIBRARY_PATH=/etc/phoshdroid/lib:/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib

# The /opt Hyprland is built against the image's hypr* libraries. A full system
# upgrade (a bare yay is yay -Syu) moves them on: hyprgraphics becomes .so.6 and
# needs a newer libjxl than the image has, so the binary cannot start. The yay
# shim and the root broker refuse upgrades for that reason; there is no library
# alias here, because the dependency closure cannot be reproduced by hand.

# GPU path (launch-contract section 9). The host resolves the mode and passes
# OMDROID_GPU=kgsl|software; a manual launch from a shell has no host, so the
# same probe runs here: a readable and writable /dev/kgsl-3d0 means KGSL, anything
# else means software (the patched Mesa llvmpipe, slow but it needs no GPU node).
# OMDROID_GPU_SOURCE says who chose: auto lets session-child.sh retry once in
# software mode when Hyprland dies at once; setting never changes the mode.
gpu_from=host
case "${OMDROID_GPU:-}" in
    kgsl|software) ;;
    *)
        if [ -r /dev/kgsl-3d0 ] && [ -w /dev/kgsl-3d0 ]; then
            OMDROID_GPU=kgsl
        else
            OMDROID_GPU=software
        fi
        OMDROID_GPU_SOURCE=auto
        gpu_from=probe
        ;;
esac
: "${OMDROID_GPU_SOURCE:=setting}"
# An earlier attempt of this same launch already fell back (the host retries a
# session that dies in its first 30 s): do not pay for the failed KGSL start again.
# The marker lives in the runtime dir, which the host wipes before every launch.
if [ "$OMDROID_GPU" = kgsl ] && [ "$OMDROID_GPU_SOURCE" = auto ] \
        && [ -e "$XDG_RUNTIME_DIR/omdroid-gpu-fallback" ]; then
    OMDROID_GPU=software
    gpu_from=marker
fi
export OMDROID_GPU OMDROID_GPU_SOURCE
# Every variable below is the same in both modes: the nested Wayland path does not
# depend on the GPU vendor. The Android patched Mesa wins over the stock one.
export LIBGL_DRIVERS_PATH=/opt/omarchy-android/mesa/root/usr/lib/dri
export AQ_NO_KMS_REQUIREMENT=1 AQ_NO_MODIFIERS=1
export AQ_WAYLAND_REFRESH_MHZ="$OMARCHY_REFRESH_MHZ"
export HYPRLAND_SHM_DAMAGE=1
export HYPRLAND_EGL_NO_MODIFIERS=1 HYPRLAND_EGL_SURFACELESS=1
export HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_SD_VARS=1 HYPRLAND_NO_RT=1
export QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland SDL_VIDEODRIVER=wayland
export XWAYLAND_NO_GLAMOR=1 NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0 GTK_A11Y=none
if [ "$OMDROID_GPU" = software ]; then
    export OMARCHY_GPU_MODE=software
    export MESA_LOADER_DRIVER_OVERRIDE=swrast LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
    unset VK_ICD_FILENAMES FD_KGSL_ENABLE_DMABUF HYPRLAND_ANDROID_DMABUF
else
    export OMARCHY_GPU_MODE=kgsl
    export MESA_LOADER_DRIVER_OVERRIDE=kgsl
    export VK_ICD_FILENAMES=/opt/omarchy-android/mesa/freedreno_icd.json
    export FD_KGSL_ENABLE_DMABUF=1
    export HYPRLAND_ANDROID_DMABUF=1
    unset LIBGL_ALWAYS_SOFTWARE GALLIUM_DRIVER
fi
echo "gpu: mode=$OMDROID_GPU source=$OMDROID_GPU_SOURCE from=$gpu_from"

# Fake root: the sudo client ships in the overlay and the broker is the second
# proot the host app starts. chmod because the APK overlay sync only marks .sh
# and .py files executable, and this one has no suffix.
chmod 755 /etc/phoshdroid/sudo
ln -sfn /etc/phoshdroid/sudo /usr/local/bin/sudo

# build tools: makepkg calls fakeroot for its package step, and real fakeroot
# needs SysV IPC, which Android does not have. The shim sends that step to the
# same root broker instead. yay is the AUR helper the Omarchy scripts expect;
# the shim installs yay-bin on first use. Both are linked here for the same
# reason as sudo: an updated install rewrites /etc/phoshdroid only.
chmod 755 /etc/phoshdroid/fakeroot /etc/phoshdroid/yay
ln -sfn /etc/phoshdroid/fakeroot /usr/local/bin/fakeroot
ln -sfn /etc/phoshdroid/yay /usr/local/bin/yay

# TUI launchers need the standard terminal helper, omitted by the base image.
if ! command -v xdg-terminal-exec >/dev/null 2>&1; then
    chmod 755 /etc/phoshdroid/terminal/xdg-terminal-exec
    ln -sfn /etc/phoshdroid/terminal/xdg-terminal-exec /usr/local/bin/xdg-terminal-exec
    mkdir -p /usr/local/share/xdg-terminal-exec
    ln -sfn /etc/phoshdroid/terminal/xdg-terminals.list /usr/local/share/xdg-terminal-exec/xdg-terminals.list
fi
python3 /etc/phoshdroid/omdroid-app-defaults.py

mkdir -p /run/user/1000
rm -f /run/user/1000/wayland-omarchy /run/user/1000/wayland-omarchy.lock

# UI mode for this session: mobile (the Omamobile phone shell pieces vendored in
# /etc/phoshdroid/omamobile) or the stock desktop. Reads $HOME/.config/omdroid/ui
# and installs or removes the theme sizes, the Hyprland rules and the plugin
# links, so the index built below sees the right plugin set. One line on
# purpose: all of it lives in omdroid-ui.sh.
bash /etc/phoshdroid/omdroid-ui.sh

# Hard-link members an older extractor dropped (gcc needs /usr/bin/ld). Copies
# them from their targets once, then a marker skips the scan on later starts.
bash /etc/phoshdroid/omdroid-repair-links.sh

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

# Session environment for everything started below. Two defaults that bound
# memory and disk on small devices: MALLOC_ARENA_MAX caps the glibc per-thread
# arena count (the guest is multi-threaded: Hyprland, Quickshell, Xwayland), and
# MESA_SHADER_CACHE_MAX_SIZE caps the shader cache, which the default lets grow
# to 1 GB inside the guest home. Do not add the malloc trim or mmap thresholds:
# each changes the glibc syscall pattern and every syscall is costlier under
# proot. The optional user file holds plain key=value lines, is sourced with
# set -a so every assignment is exported, and overrides either default above
# (for example MALLOC_ARENA_MAX= to drop the cap, OMDROID_ANIMATIONS=1,
# OMDROID_WATCH_LEGACY=1, OMDROID_WATCH_HEARTBEAT=1.0).
session_env="$HOME/.config/omdroid/session.env"
export MALLOC_ARENA_MAX=2
export MESA_SHADER_CACHE_MAX_SIZE=128M
# Ring of the nested output: two buffers, one 25 MB frame less in the session.
# AQ_WAYLAND_SWAPCHAIN=3 in session.env restores Hyprland three-buffer ring.
export AQ_WAYLAND_SWAPCHAIN=2
if [ -f "$session_env" ]; then
    set -a
    . "$session_env"
    set +a
    echo "session.env: read $session_env"
fi

# Per-feature hooks, run after session.env so its switches apply: mem (memory and
# software GPU), share (Android storage), complete (Omarchy features under
# Android). Each is a plain bash script that returns within a few seconds and
# starts any long-running helper in the background itself. A failing hook is
# logged and never stops the session.
for hook in /etc/phoshdroid/mem/session-start.sh /etc/phoshdroid/share/session-start.sh /etc/phoshdroid/complete/session-start.sh; do
    if [ -f "$hook" ]; then
        bash "$hook" || echo "hook $hook rc=$?"
    fi
done

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
