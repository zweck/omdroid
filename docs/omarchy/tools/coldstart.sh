#!/bin/bash
# coldstart.sh [runs] [--nudge] [--tag name]  -- cold-start the app the way the launcher icon
# does, and time two things per run:
#   handover  seconds until the X display activity (com.termux.x11.MainActivity) is on top
#   desktop   seconds until probe.sh says VERDICT: PASS (bar + wallpaper up, all sizes agree)
# Resolution is about 2 s (one probe per loop). Each run ends with the full probe and a screenshot
# (<tag>-<n>.png). A summary line closes the output. Budget 60 s per run: for more than 2 runs
# call it with a 600 s timeout.
#
#   --nudge   the APK built before 2026-11-24 18:00 never hands over by itself (it opens Android's
#             all-files settings page instead). With --nudge the script brings the display forward
#             itself after 10 s, with the paddings that APK uses (160/200), and reports
#             handover=NUDGED. Use it for guest-side work; leave it off to judge a new APK.
#
# Each run also lists every activity that was on top during the launch. Anything that is not the
# app's own (the all-files settings page, a permission dialog) is reported as "foreign".
#
# If the app process is gone 6 s after launch (the old APK kills itself after a display-size
# change and counts on an alarm to come back), the script launches it once more: relaunch=1.
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=com.phoshdroid.app
RUNS=1
NUDGE=0
TAG=cold
while [ $# -gt 0 ]; do
    case "$1" in
        --nudge) NUDGE=1 ;;
        --tag) TAG="$2"; shift ;;
        [0-9]*) RUNS="$1" ;;
    esac
    shift
done

now() { date +%s.%N; }
since() { awk -v a="$(now)" -v b="$1" 'BEGIN{printf "%.1f", a-b}'; }
past() { awk -v e="$1" -v l="$2" 'BEGIN{exit !(e>l)}'; }
top() { adb -s "$T" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed -E 's/.* u0 ([^ ]+) .*/\1/' | tr -d '\r'; }
launch() { adb -s "$T" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.LauncherActivity >/dev/null 2>&1; }

ok_ho=0
ok_dk=0
bad_top=0
ho_list=""
dk_list=""
for i in $(seq 1 "$RUNS"); do
    adb -s "$T" shell am force-stop $PKG
    adb -s "$T" shell input keyevent KEYCODE_WAKEUP
    adb -s "$T" shell wm dismiss-keyguard >/dev/null 2>&1
    adb -s "$T" logcat -c
    sleep 1
    t0=$(now)
    launch
    ho=""; homode=NEVER; dk=""; relaunch=0; nudged=0; tops=""; verdict="VERDICT: FAIL never probed"
    while :; do
        el=$(since "$t0")
        past "$el" 120 && break
        cur=$(top)
        case " $tops " in *" $cur "*) ;; *) [ -n "$cur" ] && tops="$tops $cur" ;; esac
        if [ -z "$ho" ]; then
            case "$cur" in
                *com.termux.x11.MainActivity*)
                    ho=$(since "$t0")
                    if [ "$nudged" = 1 ]; then homode=NUDGED; else homode=auto; fi
                    ;;
            esac
        fi
        if [ "$relaunch" = 0 ] && past "$el" 6 && [ -z "$(adb -s "$T" shell pidof $PKG | tr -d '\r')" ]; then
            relaunch=1
            launch
        fi
        if [ "$NUDGE" = 1 ] && [ -z "$ho" ] && [ "$nudged" = 0 ] && past "$el" 10; then
            adb -s "$T" shell am start -n $PKG/com.termux.x11.MainActivity \
                --ei phoshdroid_top_padding_px 160 --ei phoshdroid_bottom_padding_px 200 >/dev/null 2>&1
            nudged=1
        fi
        if [ -z "$dk" ]; then
            verdict=$("$HERE/probe.sh" -q)
            case "$verdict" in "VERDICT: PASS"*) dk=$(since "$t0") ;; esac
        fi
        [ -n "$ho" ] && [ -n "$dk" ] && break
        sleep 0.5
    done
    foreign=none
    for a in $tops; do
        case "$a" in $PKG/*) ;; *) foreign="$a" ;; esac
    done
    [ "$foreign" != none ] && bad_top=$((bad_top + 1))
    echo "RUN $i: handover=${ho:+"+${ho}s "}($homode) desktop=$( [ -n "$dk" ] && echo "+${dk}s" || echo NEVER ) relaunch=$relaunch top=$(top) pid=$(adb -s "$T" shell pidof $PKG | tr -d '\r')"
    echo "  activities seen on top, in order:$tops"
    echo "  foreign activity on top during launch (settings page, permission dialog...): $foreign"
    [ -z "$dk" ] && echo "  last probe: $verdict"
    [ "$homode" = auto ] && ok_ho=$((ok_ho + 1)) && ho_list="$ho_list $ho"
    [ -n "$dk" ] && ok_dk=$((ok_dk + 1)) && dk_list="$dk_list $dk"
    "$HERE/probe.sh" | sed 's/^/  /'
    adb -s "$T" logcat -d -v time -s Phoshdroid:* ProotService:* Phoshdroid.Monitor:* 2>/dev/null \
        | grep -E 'launch:|deferring|display shift|not ready|XLORIE geometry|guest-overlay' | tail -16 | cut -c1-170 | sed 's/^/  log: /'
    "$HERE/shot.sh" "$TAG-$i" | grep -E '^(top:|HINT|BANDS|  rows|saved)' | sed 's/^/  /'
done
range() { echo "$1" | tr ' ' '\n' | awk 'NF{if(min==""||$1<min)min=$1; if($1>max)max=$1} END{if(min=="")print "-"; else printf "%.1f..%.1f s", min, max}'; }
echo "SUMMARY tag=$TAG runs=$RUNS: unaided handover $ok_ho/$RUNS ($(range "$ho_list")), desktop PASS $ok_dk/$RUNS ($(range "$dk_list")), runs with a foreign activity on top: $bad_top"
