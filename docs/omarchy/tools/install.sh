#!/bin/bash
# install.sh [apk]  -- put a build on the tablet and prove it is the one that is installed.
#
# HyperOS refuses `adb install` (INSTALL_FAILED_USER_RESTRICTED), so the APK is pushed to
# /sdcard/Download and installed with Android's own package installer, driven by taps. The tap
# targets are found by text in a uiautomator dump, not by fixed coordinates.
# App data (the extracted guest) survives: this is an update, not a reinstall.
#
# Prints, at the end:
#   INSTALLED sha256=<..> lastUpdateTime=<..>   and  INSTALL: PASS | FAIL <reason>
# PASS means: the installed base.apk has the same sha256 as the local APK.
# Takes 2-4 minutes (1.7 GB push). Call it with a 600 s timeout.
set -u
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
APK=${1:-$HERE/../../../app/build/outputs/apk/debug/app-debug.apk}
PKG=com.phoshdroid.app
REMOTE=/sdcard/Download/omdroid-debug.apk
[ -f "$APK" ] || { echo "INSTALL: FAIL no apk at $APK"; exit 1; }
LOCAL_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
echo "apk $(stat -c %s "$APK") bytes sha256=$LOCAL_SHA"

sh_() { adb -s "$T" shell "$@" 2>/dev/null | tr -d '\r'; }
installed_sha() {
    local p
    p=$(sh_ pm path $PKG | head -1 | sed 's/^package://')
    [ -n "$p" ] && sh_ sha256sum "$p" | cut -d' ' -f1
}
# tap_text <regex>: tap the centre of the first node whose text matches. Returns 1 if absent.
tap_text() {
    sh_ uiautomator dump /sdcard/ui.xml >/dev/null
    adb -s "$T" exec-out cat /sdcard/ui.xml 2>/dev/null | python3 "$HERE/uitap.py" "$1" > /tmp/omdroid-tap.txt
    local xy
    xy=$(cat /tmp/omdroid-tap.txt)
    [ -n "$xy" ] || return 1
    # shellcheck disable=SC2086
    sh_ input tap $xy
    echo "tapped '$1' at $xy"
}

if [ "$(installed_sha)" = "$LOCAL_SHA" ]; then
    echo "INSTALLED sha256=$LOCAL_SHA (already installed)"
    echo "INSTALL: PASS"
    exit 0
fi

sh_ input keyevent KEYCODE_WAKEUP
sh_ wm dismiss-keyguard
sh_ am force-stop $PKG
echo "== push"
adb -s "$T" push "$APK" "$REMOTE" 2>&1 | tail -1
sh_ appops set com.google.android.documentsui REQUEST_INSTALL_PACKAGES allow
sh_ appops set com.android.shell REQUEST_INSTALL_PACKAGES allow
echo "== open the installer"
# Opening the APK document directly is refused for the shell, and a file:// VIEW intent lands on
# other handlers. So: open the Downloads folder in DocumentsUI, filter it to our file with the
# search box (the folder is long and sorted by name), tap the row, then the installer's button.
sh_ am start -a android.intent.action.VIEW \
    -d "content://com.android.externalstorage.documents/document/primary%3ADownload" >/dev/null
sleep 3
tap_text '^Search$' || echo "no Search button (top: $(sh_ dumpsys activity activities | grep -m1 topResumedActivity))"
sleep 1
sh_ input text omdroid-debug
ok=0
for i in $(seq 1 40); do
    if [ "$(installed_sha)" = "$LOCAL_SHA" ]; then ok=1; break; fi
    # In order of appearance: the filtered file row, the installer's question, nothing (copying).
    if ! tap_text '^omdroid-debug\.apk$'; then
        if ! tap_text '^(Update|Install|Continue|Reinstall|Install anyway|Continue installing)$'; then
            # The filter is applied only after Enter, and the first Enter can be swallowed.
            case "$(sh_ dumpsys activity activities | grep -m1 topResumedActivity)" in
                *documentsui*) sh_ input keyevent KEYCODE_ENTER ;;
            esac
        fi
    fi
    sleep 3
done
tap_text '^Done$' || true
sh_ input keyevent KEYCODE_HOME
if [ "$ok" = 1 ]; then
    sh_ pm grant $PKG android.permission.POST_NOTIFICATIONS
    sh_ rm -f "$REMOTE"   # 1.7 GB of the user's storage; the next install pushes again
    echo "INSTALLED sha256=$LOCAL_SHA $(sh_ dumpsys package $PKG | grep -m1 lastUpdateTime | sed 's/^ *//')"
    echo "INSTALL: PASS"
else
    echo "installed sha256=$(installed_sha) wanted=$LOCAL_SHA"
    echo "top: $(sh_ dumpsys activity activities | grep -m1 topResumedActivity)"
    echo "INSTALL: FAIL the installer did not finish in 120 s (take a screenshot and look at what it shows)"
    exit 1
fi
