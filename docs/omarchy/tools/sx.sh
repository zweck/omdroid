#!/bin/bash
# sx.sh '<shell command>' [timeout_s]  -- run a command INSIDE the live session.
#
# The command is started by Hyprland itself (`dispatch exec`), so it runs in the same
# proot instance and environment as the desktop: stock `hyprctl` works, WAYLAND_DISPLAY is
# Hyprland's own socket, HYPRLAND_INSTANCE_SIGNATURE is set, the session D-Bus is reachable.
# Use it for anything that must see the session (hyprctl, quickshell ipc, wl clients, env).
# Output (stdout+stderr, then "rc=<n>") is captured and printed. Default timeout 20 s.
#
# For a long-lived GUI app do NOT use this (it would wait for the timeout); use
#   ./hc.sh '/dispatch hl.dsp.exec_cmd("<app>")'
# gx.sh is still the tool for plain file/guest inspection: it is a separate proot and
# works with no live session.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
W=${2:-20}
TMP=$(mktemp -d)
printf '%s\n' "$1" > "$TMP/sx-cmd.sh"
cat > "$TMP/sx-run.sh" <<'EOF'
#!/bin/bash
bash /tmp/sx-cmd.sh > /tmp/sx-out.txt 2>&1
echo "rc=$?" >> /tmp/sx-out.txt
touch /tmp/sx-done
EOF
chmod 644 "$TMP/sx-cmd.sh" "$TMP/sx-run.sh"
for f in sx-cmd.sh sx-run.sh; do
    adb -s "$T" push "$TMP/$f" "/data/local/tmp/$f" >/dev/null 2>&1
    adb -s "$T" shell run-as com.phoshdroid.app cp "/data/local/tmp/$f" "files/usr/tmp/$f"
done
adb -s "$T" shell run-as com.phoshdroid.app rm -f files/usr/tmp/sx-done files/usr/tmp/sx-out.txt
REPLY=$("$HERE/hc.sh" '/dispatch hl.dsp.exec_cmd("bash /tmp/sx-run.sh")' | tail -n +3)
case "$REPLY" in
    ok*) ;;
    *) echo "sx.sh: Hyprland refused the exec: $REPLY"; rm -rf "$TMP"; exit 1 ;;
esac
DONE=0
for _ in $(seq 1 "$W"); do
    if adb -s "$T" shell run-as com.phoshdroid.app ls files/usr/tmp/sx-done >/dev/null 2>&1; then
        DONE=1
        break
    fi
    sleep 1
done
adb -s "$T" shell run-as com.phoshdroid.app cat files/usr/tmp/sx-out.txt 2>&1
[ "$DONE" = 1 ] || echo "sx.sh: TIMEOUT after ${W}s (command still running, output above is partial)"
rm -rf "$TMP"
