#!/bin/bash
# probe.sh [-q]  -- do all the sizes in the live session agree?
#
# Prints the X root, the cage window, the Hyprland monitor (pixels, scale, logical size), every
# layer surface, the client count and the ready marker, then one line:
#   VERDICT: PASS            X root == cage window == monitor, logical size integral, layers fit
#   VERDICT: FAIL <reasons>  names the link in the chain that is wrong
# -q prints only the VERDICT line. Exit status 0 = PASS. One adb round trip (about 2 s).
# It does not care which Android activity is on top: the session renders with no surface too.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
adb -s "$T" push "$HERE/probe_guest.py" /data/local/tmp/probe_guest.py >/dev/null 2>&1
adb -s "$T" shell run-as com.phoshdroid.app cp /data/local/tmp/probe_guest.py files/usr/tmp/probe_guest.py
"$HERE/gx.sh" 'DISPLAY=:0 python3 /tmp/probe_guest.py' 2>&1 | python3 "$HERE/probe_report.py" "$@"
