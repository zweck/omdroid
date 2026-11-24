#!/bin/bash
# cycle-tablet.sh [wait] -- cold start on the tablet and dump the session state
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
W=${1:-40}
OUT=$HOME/.hermes/cache/scratch/omarchy
adb -s "$T" shell am force-stop com.phoshdroid.app
adb -s "$T" shell input keyevent KEYCODE_WAKEUP
adb -s "$T" logcat -c
adb -s "$T" shell am start -n com.phoshdroid.app/.LauncherActivity >/dev/null
sleep "$W"
echo "== top: $(adb -s "$T" shell dumpsys activity activities | grep topResumedActivity | head -1 | cut -c1-110)"
echo "== procs"
adb -s "$T" shell "ps -A -o PID,ETIME,ARGS | grep -E 'proot|cage|Hyprland|quickshell|fit-x11' | grep -v grep | cut -c1-100"
echo "== fit-x11"
adb -s "$T" shell run-as com.phoshdroid.app cat files/usr/tmp/fit-x11.log 2>&1 | tail -4
echo "== session (tail)"
adb -s "$T" shell run-as com.phoshdroid.app tail -60 files/usr/tmp/omarchy-session.log 2>&1 | grep -aE '^\+|DEBUG ]|WARN ]|ERR|CRIT|MESA|dbus\[|Cage|wlroots|error' | tail -22 | cut -c1-200
echo "== hypr instance dir"
adb -s "$T" shell run-as com.phoshdroid.app ls -a files/usr/tmp/omarchy-runtime/hypr/ 2>&1 | tail -3
adb -s "$T" exec-out screencap -p > "$OUT/t.png" 2>/dev/null
python3 - "$OUT/t.png" <<'EOF'
import sys
from PIL import Image
im=Image.open(sys.argv[1]).convert('RGB'); s=im.resize((48,48))
print("screen", im.size, "distinct colours:", len(set(list(s.getdata()))))
EOF
