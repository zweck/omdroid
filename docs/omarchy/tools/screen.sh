#!/bin/bash
# screen.sh show | set <W>x<H> <dpi> | rot <0|1|2|3|auto> | reset
#
# Emulate other screens on the tablet with Android's own overrides.
#   set 1080x2400 420   logical display size (in the device's NATURAL orientation, which is
#                       portrait on this tablet) and density. Apps see it at once.
#   rot 0|1|2|3         lock the rotation (0 = natural/portrait, 1 = landscape). `rot auto` unlocks.
#   reset               restore exactly what the tablet had before the first change.
#   show                current overrides and the size apps really get.
#
# The original state is saved once in $STATE and never overwritten, so `reset` is always safe.
# ALWAYS finish with `./screen.sh reset` and check `./screen.sh show`: the tablet is Phil's.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
STATE=${OMARCHY_SCREEN_STATE:-$HOME/.hermes/cache/scratch/omarchy/screen-orig.env}
sh_() { adb -s "$T" shell "$@" | tr -d '\r'; }

save_orig() {
    [ -f "$STATE" ] && return
    local osz ods acc rot
    osz=$(sh_ wm size | sed -n 's/^Override size: //p')
    ods=$(sh_ wm density | sed -n 's/^Override density: //p')
    acc=$(sh_ settings get system accelerometer_rotation)
    rot=$(sh_ settings get system user_rotation)
    [ "$rot" = null ] && rot=0
    mkdir -p "$(dirname "$STATE")"
    printf 'ORIG_SIZE=%s\nORIG_DENSITY=%s\nORIG_ACCEL=%s\nORIG_ROT=%s\n' "$osz" "$ods" "$acc" "$rot" > "$STATE"
}

show() {
    sh_ wm size
    sh_ wm density
    echo "accelerometer_rotation=$(sh_ settings get system accelerometer_rotation) user_rotation=$(sh_ settings get system user_rotation)"
    sh_ dumpsys window displays | grep -oE 'cur=[0-9]+x[0-9]+ app=[0-9]+x[0-9]+' | head -1
    echo "original (restored by reset): $(tr '\n' ' ' < "$STATE")"
}

save_orig
case "$1" in
    show) show ;;
    set)
        [ -n "$2" ] && [ -n "$3" ] || { echo "usage: screen.sh set <W>x<H> <dpi>"; exit 2; }
        sh_ wm size "$2"
        sh_ wm density "$3"
        sleep 2
        show
        ;;
    rot)
        if [ "$2" = auto ]; then
            sh_ settings put system accelerometer_rotation 1
        else
            sh_ settings put system accelerometer_rotation 0
            sh_ settings put system user_rotation "$2"
        fi
        sleep 2
        show
        ;;
    reset)
        # shellcheck disable=SC1090
        . "$STATE"
        if [ -n "$ORIG_SIZE" ]; then sh_ wm size "$ORIG_SIZE"; else sh_ wm size reset; fi
        if [ -n "$ORIG_DENSITY" ]; then sh_ wm density "$ORIG_DENSITY"; else sh_ wm density reset; fi
        sh_ settings put system user_rotation "${ORIG_ROT:-0}"
        sh_ settings put system accelerometer_rotation "${ORIG_ACCEL:-1}"
        sleep 2
        show
        ;;
    *) echo "usage: screen.sh show | set <W>x<H> <dpi> | rot <0|1|2|3|auto> | reset"; exit 2 ;;
esac
