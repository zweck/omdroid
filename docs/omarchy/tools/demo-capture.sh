#!/bin/bash
# demo-capture.sh [name]  -- record a short desktop demo from INSIDE the live session.
#
# Frames are taken with grim through Hyprland's own screencopy (about 3 per second at the
# logical size), so this works with the tablet locked or the app in the background and shows
# exactly what the compositor draws. It does not show Android's side (splash, extra-key bar):
# use `adb shell screenrecord` on an unlocked device for that.
#
# The scenario is driven only through Hyprland (no adb input events): close leftover
# terminals, open three (fastfetch, the session processes and themes, system facts), open and
# close the Omarchy menu. btop is not used: it aborts on Android (/sys/devices is unreadable).
# Result, under $OMARCHY_SHOTS/<name>/ (default ~/.hermes/cache/scratch/omarchy/shots/demo):
#   f0001.jpg ...   frames, 1 logical px per px
#   full.png        one full-resolution frame with the three terminals open
#   times.txt       wall-clock second of each frame
# Turn the frames into a GIF with demo-gif.sh.
set -u
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
T=${TABLET_SERIAL:?set TABLET_SERIAL to the adb serial of the test device}
HERE=$(cd "$(dirname "$0")" && pwd)
NAME=${1:-demo}
OUT=${OMARCHY_SHOTS:-$HOME/.hermes/cache/scratch/omarchy/shots}/$NAME
mkdir -p "$OUT"
TMP=$(mktemp -d)

cat > "$TMP/demo-a.sh" <<'EOF'
export PS1='\[\e[1;34m\]omdroid\[\e[0m\] \w \$ '
fastfetch
exec bash --norc
EOF
cat > "$TMP/demo-b.sh" <<'EOF'
export PS1='\[\e[1;34m\]omdroid\[\e[0m\] \w \$ '
echo "\$ ps -o pid,etime,comm -C cage,Hyprland,quickshell"
ps -o pid,etime,comm -C cage,Hyprland,quickshell
echo "\$ ls /usr/share/omarchy/themes"
ls --color=always /usr/share/omarchy/themes
exec bash --norc
EOF
cat > "$TMP/demo-c.sh" <<'EOF'
export PS1='\[\e[1;34m\]omdroid\[\e[0m\] \w \$ '
echo "\$ uname -srm"; uname -srm
echo "\$ head -2 /etc/os-release"; head -2 /etc/os-release
echo "\$ hyprctl version | head -1"; hyprctl version | head -1
echo "\$ echo \$XDG_SESSION_TYPE on \$WAYLAND_DISPLAY"; echo "$XDG_SESSION_TYPE on $WAYLAND_DISPLAY"
exec bash --norc
EOF
cat > "$TMP/demo-run.sh" <<'EOF'
D=/tmp/demo-frames
mkdir -p $D
find $D -maxdepth 1 -type f -name '*.jpg' -delete
: > $D/times.txt
: > $D/grim.err
open() { hyprctl dispatch "hl.dsp.exec_cmd(\"foot bash /tmp/$1\")" >/dev/null 2>&1; }
pkill -x foot
# Park the pointer in the bottom-right corner so it does not sit on a window.
read -r W H <<<"$(hyprctl -j monitors | jq -r '.[0] | "\((.width / .scale) | floor) \((.height / .scale) | floor)"')"
hyprctl eval "hl.dispatch(hl.dsp.cursor.move({ x = $((W - 4)), y = $((H - 4)) }))" >/dev/null 2>&1
sleep 1.5
( i=0
  while [ ! -e $D/stop ]; do
      i=$((i + 1))
      grim -s 1 -t jpeg -q 90 "$(printf '%s/f%04d.jpg' $D $i)" 2>>$D/grim.err
      date +%s.%N >> $D/times.txt
  done ) &
sleep 1.5
open demo-a.sh; sleep 3
open demo-b.sh; sleep 3.5
open demo-c.sh; sleep 3
grim -t png $D/full.png 2>>$D/grim.err
omarchy-menu summon >/dev/null 2>&1; sleep 3
omarchy-menu close >/dev/null 2>&1; sleep 1.5
touch $D/stop
wait
find $D -maxdepth 1 -type f -name stop -delete
echo "frames=$(ls $D | grep -c '^f.*jpg$') grim-errors=$(wc -l < $D/grim.err)"
hyprctl -j clients | grep -c '"class"'
EOF

for f in demo-a.sh demo-b.sh demo-c.sh demo-run.sh; do
    adb -s "$T" push "$TMP/$f" "/data/local/tmp/$f" >/dev/null 2>&1
    adb -s "$T" shell run-as com.phoshdroid.app cp "/data/local/tmp/$f" "files/usr/tmp/$f"
done
"$HERE/sx.sh" 'bash /tmp/demo-run.sh' 60
adb -s "$T" exec-out run-as com.phoshdroid.app tar -cf - -C files/usr/tmp/demo-frames . > "$TMP/frames.tar"
tar -xf "$TMP/frames.tar" -C "$OUT"
echo "saved: $OUT ($(ls "$OUT" | grep -c '^f.*jpg$') frames, full.png $(stat -c %s "$OUT/full.png" 2>/dev/null) bytes)"
