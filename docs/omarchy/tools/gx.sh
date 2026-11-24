#!/bin/bash
# gx.sh '<guest shell command>'  -- run a command inside the live Omarchy guest (uid 1000)
# Same proot args as ProotCommandBuilder, so it sees /run/user/1000 (cage + Hyprland sockets).
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
D=${ADB_SERIAL:-${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}}
CMD="$1"
APK=$(adb -s "$D" shell pm path com.phoshdroid.app | head -1 | sed 's/package://; s#/base.apk##' | tr -d '\r')
NL="$APK/lib/arm64"
P=/data/data/com.phoshdroid.app/files/usr
R=$P/var/lib/proot-distro/installed-rootfs/omarchy
F=$R/etc/phoshdroid/fake-proc
TMPSH=$(mktemp)
cat > "$TMPSH" <<EOF
#!/system/bin/sh
export PROOT_TMP_DIR=$P/tmp PROOT_LOADER=$NL/libproot-loader.so LD_LIBRARY_PATH=$NL TMPDIR=$P/tmp
exec $NL/libproot.so --kill-on-exit --link2symlink -i 1000:1000 -w /home/omarchy -r $R \\
 -b /dev -b /proc -b /sys -b $P/tmp:/tmp -b $P/tmp:/dev/shm -b $P/tmp/omarchy-runtime:/run/user/1000 \\
 -b /dev/null:/proc/bus/pci/devices -b $F/stat:/proc/stat -b $F/meminfo:/proc/meminfo \\
 -b $F/loadavg:/proc/loadavg -b $F/uptime:/proc/uptime -b $F/cpuinfo:/proc/cpuinfo \\
 /usr/bin/env -i HOME=/home/omarchy USER=omarchy XDG_RUNTIME_DIR=/run/user/1000 \\
 PATH=/opt/omarchy-android/hyprland/bin:/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 \\
 LD_LIBRARY_PATH=/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib \\
 WAYLAND_DISPLAY=\${GX_WL:-wayland-0} \\
 /bin/bash -c "\$(cat /data/local/tmp/gx-cmd.txt)"
EOF
printf '%s' "$CMD" > /tmp/gx-cmd.txt 2>/dev/null || printf '%s' "$CMD" > "$TMPSH.cmd"
SRC=/tmp/gx-cmd.txt; [ -f "$TMPSH.cmd" ] && SRC="$TMPSH.cmd"
adb -s "$D" push "$SRC" /data/local/tmp/gx-cmd.txt >/dev/null
adb -s "$D" push "$TMPSH" /data/local/tmp/gx-run.sh >/dev/null
adb -s "$D" shell run-as com.phoshdroid.app sh /data/local/tmp/gx-run.sh 2>&1
rm -f "$TMPSH" "$TMPSH.cmd"
