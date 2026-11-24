#!/bin/bash
# demo-gif.sh <frames-dir> <out.gif> [width]  -- turn demo-capture.sh frames into a looping GIF.
#
# The frame rate is the measured one (frames / seconds from times.txt), so the GIF plays in real
# time. The last frame is held for 2.5 s before the loop restarts. Default width 800 px.
# Needs ffmpeg. Prints the size and duration of the result.
set -eu
DIR=${1:?usage: demo-gif.sh <frames-dir> <out.gif> [width]}
OUT=${2:?usage: demo-gif.sh <frames-dir> <out.gif> [width]}
WIDTH=${3:-800}
FPS=$(awk 'NR==1{a=$1} END{if (NR > 1 && $1 > a) printf "%.3f", (NR - 1) / ($1 - a); else print "3.3"}' "$DIR/times.txt")
mkdir -p "$(dirname "$OUT")"
ffmpeg -v error -y -framerate "$FPS" -pattern_type glob -i "$DIR/f*.jpg" \
    -vf "tpad=stop_mode=clone:stop_duration=2.5,scale=$WIDTH:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    -loop 0 "$OUT"
echo "wrote $OUT: $(stat -c %s "$OUT") bytes, $(ls "$DIR" | grep -c '^f.*jpg$') frames at $FPS fps, $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT") s"
