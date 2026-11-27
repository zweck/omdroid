#!/bin/sh
# SPDX-License-Identifier: MIT
# tile-state.sh: one read-only pass over the backends of the quick-settings
# tiles that have no Quickshell service, printed as key=value lines for
# TilesModel.parseProbe. "none" means the tile has no backend on this device.
# Nothing here changes a setting; each probe is bounded by timeout so a hung
# daemon cannot hold the sheet's refresh. The one file it writes is the state
# cache (~/.cache/omamobile/tile-state), so a shell start can read the last
# state with no process at all.
#
# Every probe runs AT ONCE, each into its own file, and the lines are printed in
# a fixed order afterwards. Serial `timeout 3` calls summed to ~7 s on the tablet
# and the shell paid that at every start: the sheet probes once at load, and the
# bar, dock and back strips did not map until the pass exited (measured, R11 S2:
# tile-state.sh 2.5 s -> 9.6 s, the mobile layers 9.5-9.6 s).
#
#   airplane      on when every Wi-Fi, Bluetooth and WWAN rfkill switch is
#                 soft-blocked (none without such a switch)
#   hotspot       on while NetworkManager's "Hotspot" connection is up
#   powerprofile  power-profiles-daemon's active profile
#   theme_mode    light or dark, as Omarchy resolves the current theme
#   theme_name    the current theme's directory name
#   nightlight    on while hyprsunset runs warmer than identity
#   stayawake     on while Omarchy's stay-awake indicator exists
#   record        on while omamobile-screenrecord (wf-recorder) records
#   data          on while omamobile-sit-data.service is active; none
#                 without omamobile-sitd running
#   data_status   the operator's name (never a bare network code) and the
#                 technology when registered, else "No signal"; with no
#                 modem, the one line omamobile-modem-start left in
#                 /run/omamobile/modem-refused when it refused to start
#                 the modem this boot (a stale marker from a hang, a phone
#                 not yet provisioned), so the tile can say why
#   data_signal   the signal, 0-100, while registered; -1 otherwise
#   data_switch   off when the modem's switch (omamobile-modem-switch, the
#                 Mobile network page) has the modem off for this boot: the
#                 tile stays dimmed but keeps its chevron, so the page that
#                 turns it back on can be reached; absent otherwise
#   dictation     voxtype: on while recording, busy while transcribing, off
#                 when idle; down when its user service isn't running, none
#                 without voxtype (in the root image; Remove > Dictation
#                 takes it out)

cache="${OMAMOBILE_TILE_STATE_CACHE:-$HOME/.cache/omamobile/tile-state}"
dir=$(mktemp -d "${TMPDIR:-/tmp}/omamobile-tile-state.XXXXXX") || dir=
if [ -z "$dir" ]; then
    echo "tile-state=unavailable"
    exit 0
fi
trap 'rm -rf "$dir"' EXIT INT TERM

t() { timeout 3 "$@" 2>/dev/null; }
have() { command -v "$1" >/dev/null 2>&1; }

# airplane
p_airplane() {
    if have rfkill; then
        t rfkill -rn -o TYPE,SOFT | awk '
            $1 == "wlan" || $1 == "bluetooth" || $1 == "wwan" { n++; if ($2 == "blocked") b++ }
            END { print "airplane=" (n == 0 ? "none" : (b == n ? "on" : "off")) }'
    else
        echo "airplane=none"
    fi
}

# hotspot
p_hotspot() {
    if have nmcli; then
        if t nmcli -t -f NAME connection show --active | grep -cx Hotspot >/dev/null; then
            echo "hotspot=on"
        else
            echo "hotspot=off"
        fi
    else
        echo "hotspot=none"
    fi
}

# powerprofile
p_powerprofile() {
    profile=$(have powerprofilesctl && t powerprofilesctl get)
    echo "powerprofile=${profile:-none}"
}

# theme
p_theme() {
    if have omarchy-theme-color; then
        echo "theme_mode=$(t omarchy-theme-color mode)"
    fi
    name_file="$HOME/.local/state/omarchy/current/theme.name"
    [ -r "$name_file" ] && echo "theme_name=$(head -n1 "$name_file")"
}

# nightlight
p_nightlight() {
    if have hyprsunset && have omarchy-toggle-nightlight; then
        case $(t omarchy-toggle-nightlight --status) in
            *'"enabled":true'*) echo "nightlight=on" ;;
            *) echo "nightlight=off" ;;
        esac
    else
        echo "nightlight=none"
    fi
}

# stayawake
p_stayawake() {
    if [ -e "$HOME/.local/state/omarchy/indicators/stay-awake" ]; then
        echo "stayawake=on"
    else
        echo "stayawake=off"
    fi
}

# record
p_record() {
    if have omamobile-screenrecord && have wf-recorder; then
        echo "record=$(t omamobile-screenrecord status || echo off)"
    else
        echo "record=none"
    fi
}

# dictation: the daemon writes idle, recording or transcribing to its state
# file (state_file = "auto" in Omarchy's voxtype config). A stale file
# outlives a stopped daemon, so the user service has to be active too.
p_dictation() {
    if have voxtype; then
        if t systemctl --user is-active --quiet voxtype.service; then
            vstate=
            [ -n "${XDG_RUNTIME_DIR:-}" ] && vstate=$(head -n1 "$XDG_RUNTIME_DIR/voxtype/state" 2>/dev/null)
            case $vstate in
                recording) echo "dictation=on" ;;
                transcribing) echo "dictation=busy" ;;
                *) echo "dictation=off" ;;
            esac
        else
            echo "dictation=down"
        fi
    else
        echo "dictation=none"
    fi
}

# data: the daemon's public state is world-readable; the bearer service's
# state is what "data on|off" switches.
# The status names the operator only by name: sitd's "operator" falls back
# to the numeric network code, which reads as noise, so a bare code is left
# out and the technology stands alone.
p_data() {
    state=${OMAMOBILE_SIT_STATE:-/run/omamobile-sit/state.json}
    if have omamobile-sit && [ -r "$state" ] && have python3; then
        if systemctl is-active --quiet omamobile-sit-data.service 2>/dev/null; then echo "data=on"; else echo "data=off"; fi
        python3 -I -c '
import json, sys
try:
    m = json.load(open(sys.argv[1])).get("modem") or {}
except (OSError, ValueError):
    sys.exit(0)
if not isinstance(m, dict):
    sys.exit(0)
if m.get("state") != "registered":
    print("data_signal=-1")
    print("data_status=No signal")
    sys.exit(0)
try:
    signal = max(0, min(100, int(m.get("signal") or 0)))
except (TypeError, ValueError):
    signal = 0
print("data_signal=%d" % signal)
name = str(m.get("operator_name", m.get("operator")) or "").strip()
if name.replace(" ", "").isdigit():
    name = ""
words = [w for w in (name, str(m.get("tech") or "").strip()) if w]
print("data_status=" + " ".join(words))
' "$state"
    else
        echo "data=none"
        refused=${OMAMOBILE_MODEM_REFUSED:-/run/omamobile/modem-refused}
        why=
        if [ -r "$refused" ]; then
            why=$(head -n1 "$refused" 2>/dev/null | head -c 48)
        fi
        # A modem the owner switched off is not "no modem": its page has the
        # switch that turns it back on, so the tile keeps its chevron.
        switch=${OMAMOBILE_MODEM_SWITCH:-/usr/lib/omamobile-vendor/omamobile-modem-switch}
        if [ -x "$switch" ] && have python3 &&
            "$switch" status 2>/dev/null | python3 -I -c '
import json, sys
try:
    s = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
sys.exit(0 if isinstance(s, dict) and s.get("available") and not s.get("running") else 1)
'; then
            echo "data_switch=off"
            [ -n "$why" ] || why="Modem off"
        fi
        if [ -n "$why" ]; then echo "data_status=$why"; fi
    fi
}

# Android owns these devices; do not spawn Linux hardware/systemd probes.
if [ "${1:-}" = "--android" ]; then
    echo 'airplane=none' > "$dir/10-airplane"
    echo 'hotspot=none' > "$dir/20-hotspot"
    echo 'powerprofile=none' > "$dir/30-powerprofile"
    echo 'dictation=none' > "$dir/80-dictation"
    echo 'data=none' > "$dir/90-data"
else
    p_airplane > "$dir/10-airplane" &
    p_hotspot > "$dir/20-hotspot" &
    p_powerprofile > "$dir/30-powerprofile" &
    p_dictation > "$dir/80-dictation" &
    p_data > "$dir/90-data" &
fi
p_theme > "$dir/40-theme" &
p_nightlight > "$dir/50-nightlight" &
p_stayawake > "$dir/60-stayawake" &
p_record > "$dir/70-record" &
wait

cat "$dir/10-airplane" "$dir/20-hotspot" "$dir/30-powerprofile" "$dir/40-theme" \
    "$dir/50-nightlight" "$dir/60-stayawake" "$dir/70-record" "$dir/80-dictation" \
    "$dir/90-data" > "$dir/all" 2>/dev/null
cat "$dir/all"
case $cache in
    */*)
        mkdir -p "${cache%/*}" 2>/dev/null && cp -f "$dir/all" "$cache" 2>/dev/null
        ;;
esac
exit 0
