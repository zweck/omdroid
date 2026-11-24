#!/bin/bash
# Build the plugin index that Quickshell's PluginRegistry reads under proot.
#
# In proot mode (OMARCHY_PROOT=1) the registry does not run its own manifest
# scanner: a QML Process-based scan returns an empty stream under proot, so it
# reads $XDG_RUNTIME_DIR/omarchy-plugin-scan.txt instead. Upstream
# omarchy-launch-shell builds that file, but only inside
# `if [[ $(uname -r) == *PRoot* ]]`. This guest runs proot-distro, whose kernel
# release is the Android one (6.6.x-android15), so the test never matches: the
# index is never written, the registry loads zero plugins, the bar draws its
# background with no widgets, and the background plugin never draws the
# wallpaper. Building the index here, before Hyprland and the shell start, makes
# the shell come up complete and keeps the index valid for shell restarts.
#
# Format expected by PluginRegistry.parseScanOutput (same as upstream):
#   ===firstparty::<source dir>===
#   <raw manifest.json>
#   === EOM ===
# Bash built-ins plus cat/read only, no subprocess scanning.
set -u
runtime="${XDG_RUNTIME_DIR:-/run/user/$UID}"
omarchy_path="${OMARCHY_PATH:-/usr/share/omarchy}"
home="${HOME:-/home/omarchy}"
output="$runtime/omarchy-plugin-scan.txt"
temporary="${output}.new"

mkdir -p "$runtime"
: > "$temporary"

emit_manifest() {
  local kind="$1" manifest="$2" sub line
  case ${manifest##*/} in
    manifest.json) sub=${manifest%/manifest.json} ;;
    *) sub=${manifest%/*} ;;
  esac
  printf '===%s::%s===\n' "$kind" "$sub" >> "$temporary"
  while IFS= read -r line || [[ -n $line ]]; do
    printf '%s\n' "$line" >> "$temporary"
  done < "$manifest"
  printf '\n=== EOM ===\n' >> "$temporary"
}

shopt -s nullglob
plugin_root="$omarchy_path/shell/plugins"
manifests=(
  "$plugin_root"/*/manifest.json
  "$plugin_root"/*/*/manifest.json
  "$plugin_root"/*/*/*.manifest.json
)
for manifest in "${manifests[@]}"; do
  emit_manifest firstparty "$manifest"
done

for sub in "$home/.config/omarchy/plugins"/*/; do
  [[ -f $sub/manifest.json ]] || continue
  emit_manifest thirdparty "${sub%/}/manifest.json"
done

mv -f -- "$temporary" "$output"
