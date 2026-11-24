#!/bin/bash
# gpush.sh <local-file> <guest-abs-path>  -- copy a file into the tablet's extracted guest
set -e
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
LOCAL="$1"; GUEST="$2"
ROOT=files/usr/var/lib/proot-distro/installed-rootfs/omarchy
adb -s "$T" push "$LOCAL" /data/local/tmp/upload.bin >/dev/null
adb -s "$T" shell run-as com.phoshdroid.app cp /data/local/tmp/upload.bin "$ROOT$GUEST"
adb -s "$T" shell run-as com.phoshdroid.app ls -l "$ROOT$GUEST"
