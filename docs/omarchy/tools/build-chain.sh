#!/bin/bash
# Repack the guest image with the overlay, then build and unit-test the APK.
# Touches nothing on the tablet. Log: /tmp/omarchy-build-chain.log
# usage: build-chain.sh [--no-repack]
cd "$(dirname "$0")/../../.." || exit 1
LOG=/tmp/omarchy-build-chain.log
A=app/src/main/assets/rootfs.bin
O=rootfs/overlay/etc/phoshdroid
APK=app/build/outputs/apk/debug/app-debug.apk
{
  if [ "$1" != "--no-repack" ]; then
    echo "== $(date +%T) repack"
    ./rootfs/package-omarchy-rootfs.sh > /tmp/omarchy-repack.log 2>&1
    echo "repack rc=$?"
    tail -4 /tmp/omarchy-repack.log
  fi
  echo "== $(date +%T) verify image members"
  tar -tzf "$A" > /tmp/omarchy-members.txt 2>/dev/null
  echo "members=$(wc -l < /tmp/omarchy-members.txt)"
  echo "cage+wlroots members (want 2): $(grep -cE '^\./usr/bin/cage$|^\./usr/lib/libwlroots-0\.20\.so$' /tmp/omarchy-members.txt)"
  bad=0
  for f in start.sh session-child.sh fit-x11.py omarchy-watch.py omarchy-plugin-index.sh; do
    i=$(tar -xzOf "$A" "./etc/phoshdroid/$f" 2>/dev/null | sha256sum | cut -c1-16)
    r=$(sha256sum "$O/$f" | cut -c1-16)
    m=$(tar -tvzf "$A" "./etc/phoshdroid/$f" 2>/dev/null | cut -c1-10)
    if [ "$i" = "$r" ]; then echo "image==repo $f $i $m"; else echo "IMAGE!=REPO $f image=$i repo=$r"; bad=1; fi
  done
  echo "overlay scripts in image: $([ $bad = 0 ] && echo MATCH || echo MISMATCH)"
  sha256sum "$A"
  echo "== $(date +%T) assembleDebug"
  # An incremental package step appends to the old APK and doubles its size,
  # so the old output must be gone before assembling. Delete in place: moving
  # 3 GB into /tmp (a tmpfs with a quota) fails and leaves the old APK behind.
  rm -f "$APK"
  [ -e "$APK" ] && echo "WARNING: old APK still present, the new one will be oversized"
  ./gradlew :app:assembleDebug -Dorg.gradle.jvmargs="-Xmx10g -Dfile.encoding=UTF-8" --console=plain > /tmp/gradle-assemble.log 2>&1
  echo "assemble rc=$?"
  grep -E "^e: |BUILD (SUCCESSFUL|FAILED)" /tmp/gradle-assemble.log | head -12
  ls -la --time-style=+%T "$APK"
  echo "apk bytes=$(stat -c %s "$APK") assets bytes=$(( $(stat -c %s "$A") + $(stat -c %s app/src/main/assets/bootstrap.bin) ))"
  sha256sum "$APK"
  echo "== $(date +%T) unit tests"
  ./gradlew :app:test -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8" --console=plain > /tmp/gradle-test.log 2>&1
  echo "test rc=$?"
  grep -E "BUILD (SUCCESSFUL|FAILED)|FAILED$" /tmp/gradle-test.log | head -8
  python3 "$(dirname "$0")/unit-count.py" 2>/dev/null
  echo "ALLDONE $(date +%Y-%m-%dT%T)"
} > "$LOG" 2>&1
tail -40 "$LOG"
