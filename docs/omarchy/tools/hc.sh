#!/bin/bash
# hc.sh <request> [<request> ...]  -- hyprctl against the LIVE session, from a second proot.
#
# Requests use Hyprland's socket syntax, one per argument:
#   ./hc.sh j/monitors j/layers j/clients
#   ./hc.sh '/dispatch hl.dsp.exec_cmd("foot")'   (starts a long-lived app inside the session)
#
# This Hyprland (0.56.1) runs a LUA config: `dispatch X` is evaluated as `hl.dispatch(X)`, so the
# classic `dispatch exec foot` is a syntax error. Start plain (non-JSON) requests with "/": the
# letters before the first "/" are parsed as flags.
#
# Stock `hyprctl` from gx.sh or adb CANNOT work: proot remaps the over-long IPC socket
# path to $PREFIX/tmp/proot-<pid>-XXXXXX per proot instance. See hyprsock.py.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
REQ=$(mktemp)
if [ $# -eq 0 ]; then
    echo "j/monitors" > "$REQ"
else
    for a in "$@"; do printf '%s\n' "$a" >> "$REQ"; done
fi
chmod 644 "$REQ"
adb -s "$T" push "$HERE/hyprsock.py" /data/local/tmp/hyprsock.py >/dev/null 2>&1
adb -s "$T" push "$REQ" /data/local/tmp/hyprsock.req >/dev/null 2>&1
adb -s "$T" shell run-as com.phoshdroid.app cp /data/local/tmp/hyprsock.py files/usr/tmp/hyprsock.py
adb -s "$T" shell run-as com.phoshdroid.app cp /data/local/tmp/hyprsock.req files/usr/tmp/hyprsock.req
"$HERE/gx.sh" "python3 /tmp/hyprsock.py -f /tmp/hyprsock.req" 2>&1 | grep -v ' pushed'
rm -f "$REQ"
