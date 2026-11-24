#!/bin/bash
# cage's only client. Starts Hyprland with no fixed sleep: fit-x11.py --wait
# resizes cage's X11 window to the X root (each side rounded down to even) and
# returns as soon as that geometry is confirmed, capped at FIT_WAIT seconds (on
# the cap it logs one line and Hyprland starts anyway).
#
# The confirmed size is then handed to Hyprland as OMARCHY_OUTPUT_WIDTH/HEIGHT,
# which is what monitors.lua turns into its mode: the mode is exactly the size
# cage has (never the odd host prediction, which cannot match the even-rounded
# window) and the refresh hint start.sh exported is kept.
#
# Runs inside dbus-run-session, with the guest environment already exported.
X=${PHOSHDROID_XDISPLAY:-:0}
rm -f /tmp/omarchy-fit
python3 /etc/phoshdroid/fit-x11.py --wait "${FIT_WAIT:-10}" "$X"
if read -r fw fh < /tmp/omarchy-fit; then
    export OMARCHY_OUTPUT_WIDTH=$fw OMARCHY_OUTPUT_HEIGHT=$fh
fi
exec /opt/omarchy-android/hyprland/bin/Hyprland --config /home/omarchy/.config/hypr/hyprland.lua
