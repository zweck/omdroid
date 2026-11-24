#!/bin/bash
# shot.sh [name]  -- screenshot the tablet and print numbers that judge it without eyes.
# Files land in $OMARCHY_SHOTS (default ~/.hermes/cache/scratch/omarchy/shots):
#   <name>.png       full screenshot (3200x2136)
#   <name>-bar.png   top strip at full resolution, for a vision-capable reviewer
# The X display activity must be on top; shotstat.py says so when it is not.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OMARCHY_SHOTS:-$HOME/.hermes/cache/scratch/omarchy/shots}
mkdir -p "$OUT"
N=${1:-shot-$(date +%H%M%S)}
adb -s "$T" shell input keyevent KEYCODE_WAKEUP
echo "top: $(adb -s "$T" shell dumpsys activity activities | grep topResumedActivity | head -1 | sed 's/.*u0 //; s/ t[0-9]*}.*//')"
adb -s "$T" exec-out screencap -p > "$OUT/$N.png"
python3 "$HERE/shotstat.py" "$OUT/$N.png"
echo "saved: $OUT/$N.png"
