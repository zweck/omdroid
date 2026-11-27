#!/bin/bash
# Omdroid UI mode for the guest session: mobile (the Omamobile phone pieces
# vendored in /etc/phoshdroid/omamobile) or desktop (stock Omarchy).
#
# start.sh calls this once, before omarchy-plugin-index.sh runs, because the
# index is built once per session and the shell loads plugins from it.
#
# Switch: $HOME/.config/omdroid/ui holding `mobile` or `desktop`.
# Missing or unreadable means mobile. Nothing here creates or rewrites it.
#
# No-clobber rule: every write is key-level and reversible, and a key is only
# replaced or removed while it still holds exactly what this script wrote. A
# file the user edited keeps the user's value; the script logs the skip. The
# values it found before its first write live in
# $HOME/.local/state/omdroid/ui/prior.*, which is what desktop restores.
#
# Dry run: OMDROID_ROOT points the whole script at a scratch copy of the guest
# tree (root/home/omarchy, root/usr/share/omarchy, root/etc/phoshdroid,
# root/usr/local/bin) instead of /.

set -u

root="${OMDROID_ROOT:-}"
if [ -n "$root" ]; then
  home="$root/home/omarchy"
  omarchy_path="$root/usr/share/omarchy"
  etc="$root/etc/phoshdroid"
  local_bin="$root/usr/local/bin"
else
  home="${HOME:-/home/omarchy}"
  omarchy_path="${OMARCHY_PATH:-/usr/share/omarchy}"
  etc="/etc/phoshdroid"
  local_bin="/usr/local/bin"
fi

config="$home/.config"
state="$home/.local/state/omdroid/ui"
mode_file="$config/omdroid/ui"
theme_file="$config/omarchy/shell.toml"
shell_json="$config/omarchy/shell.json"
looknfeel="$config/hypr/looknfeel.lua"
plugin_dir="$config/omarchy/plugins"
vendor="$etc/omamobile"

plugin_ids="omamobile.bar omamobile.quicksettings omamobile.launcher omamobile.back omamobile.switcher"
panel_ids="omamobile.quicksettings omamobile.launcher omamobile.back omamobile.switcher"
helper_names="omamobile-launch omamobile-home omamobile-browser-setup omamobile-cell-watch omamobile-call-watch"

# The touch-sized theme pass, as section/key/value: a 44 logical px bar, 16 px
# base type, 44 px control rows. All are shell.toml keys the shell documents
# (docs/theming.md) and ~/.config/omarchy/shell.toml is the machine-level
# override the shell watches live across theme switches.
theme_keys="bar:size-horizontal:44 bar:scale-with-font:false font:base-size:16 spacing:control-height:44"

log() {
  printf 'omdroid-ui: %s\n' "$*"
}

# --------------------------------------------------------------- TOML helpers
# Line-based, like Omarchy's own omarchy-display-text-size: only the named key
# is touched, comments and ordering elsewhere survive.

toml_value() { # section key -> value on stdout, empty when the key is absent
  [ -f "$theme_file" ] || return 0
  awk -v sec="$1" -v key="$2" '
    /^[[:space:]]*\[/ { insec = ($0 ~ "^[[:space:]]*\\[" sec "\\]"); next }
    insec && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
      v = $0
      sub(/^[^=]*=[[:space:]]*/, "", v)
      sub(/[[:space:]]*(#.*)?$/, "", v)
      print v
      exit
    }
  ' "$theme_file"
}

toml_set() { # section key value
  local sec="$1" key="$2" value="$3" tmp
  [ -f "$theme_file" ] || : > "$theme_file"
  tmp="$(mktemp "$state/theme.toml.XXXXXX")" || return 1
  awk -v sec="$sec" -v key="$key" -v value="$value" '
    function flush() { if (insec && !done) { print key " = " value; done = 1 } }
    /^[[:space:]]*\[/ {
      flush()
      insec = ($0 ~ "^[[:space:]]*\\[" sec "\\]")
      print
      next
    }
    insec && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
      if (!done) { print key " = " value; done = 1 }
      next
    }
    { print }
    END {
      if (!done) {
        if (!insec) {
          if (NR > 0) print ""
          print "[" sec "]"
          insec = 1
        }
        print key " = " value
      }
    }
  ' "$theme_file" > "$tmp" && mv -f "$tmp" "$theme_file"
}

toml_unset() { # section key expected — remove the key only while it holds expected
  local sec="$1" key="$2" expected="$3" tmp
  [ -f "$theme_file" ] || return 0
  tmp="$(mktemp "$state/theme.toml.XXXXXX")" || return 1
  awk -v sec="$sec" -v key="$key" -v expected="$expected" '
    /^[[:space:]]*\[/ { insec = ($0 ~ "^[[:space:]]*\\[" sec "\\]"); print; next }
    insec && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
      v = $0
      sub(/^[^=]*=[[:space:]]*/, "", v)
      sub(/[[:space:]]*(#.*)?$/, "", v)
      if (v == expected) next
    }
    { print }
  ' "$theme_file" > "$tmp" && mv -f "$tmp" "$theme_file"
  # Drop section headers this left empty, then an empty file.
  tmp="$(mktemp "$state/theme.toml.XXXXXX")" || return 1
  awk '
    { lines[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) {
        if (lines[i] ~ /^[[:space:]]*\[/) {
          j = i + 1
          while (j <= NR && lines[j] ~ /^[[:space:]]*$/) j++
          if (j > NR || lines[j] ~ /^[[:space:]]*\[/) continue
        }
        if (lines[i] ~ /^[[:space:]]*$/ && !started) continue
        if (lines[i] ~ /^[[:space:]]*$/) { if (blank) continue; blank = 1 } else { blank = 0 }
        started = 1
        print lines[i]
      }
    }
  ' "$theme_file" > "$tmp" && mv -f "$tmp" "$theme_file"
  if [ -f "$theme_file" ] && [ -z "$(tr -d '[:space:]' < "$theme_file")" ]; then
    rm -f "$theme_file"
    log "removed empty $theme_file"
  fi
}

theme_apply() { # mobile|desktop
  local mode="$1" key section name value current
  if [ "$mode" = "mobile" ]; then
    : > "$state/theme.keys.new"
    for key in $theme_keys; do
      section="${key%%:*}"
      name="$(printf '%s' "$key" | cut -d: -f2)"
      value="${key##*:}"
      current="$(toml_value "$section" "$name")"
      if [ -n "$current" ] && [ "$current" != "$value" ]; then
        # Not ours: the user set this one (or it came from the theme). Left as
        # it is, in this mode and in desktop.
        log "shell.toml: [${section}] ${name} = ${current} is not ours; leaving it"
        continue
      fi
      if [ -z "$current" ]; then
        printf '%s.%s\n' "$section" "$name" >> "$state/theme.keys.new"
      else
        grep -qx "$section.$name" "$state/theme.keys" 2>/dev/null && printf '%s.%s\n' "$section" "$name" >> "$state/theme.keys.new"
      fi
      toml_set "$section" "$name" "$value"
    done
    mv -f "$state/theme.keys.new" "$state/theme.keys"
    log "shell.toml: mobile sizes applied"
  else
    if [ ! -f "$state/theme.keys" ]; then
      log "shell.toml: nothing to restore"
      return 0
    fi
    while read -r key; do
      [ -n "$key" ] || continue
      section="${key%%.*}"
      name="${key#*.}"
      value=""
      for k in $theme_keys; do
        if [ "${k%%:*}" = "$section" ] && [ "$(printf '%s' "$k" | cut -d: -f2)" = "$name" ]; then
          value="${k##*:}"
        fi
      done
      [ -n "$value" ] || continue
      toml_unset "$section" "$name" "$value"
    done < "$state/theme.keys"
    rm -f "$state/theme.keys"
    log "shell.toml: mobile sizes removed"
  fi
}

# ------------------------------------------------------------- shell.json ----
# Surgical jq edits, the same way Omarchy's own omarchy-bar edits this file:
# only bar.id, bar.quickSettings and our four plugin entries are touched.

shell_json_apply() { # mobile|desktop
  local mode="$1"
  [ -f "$shell_json" ] || return 0
  command -v jq >/dev/null 2>&1 || { log "no jq; shell.json untouched"; return 1; }
  if [ "$mode" = "mobile" ]; then
    if [ ! -f "$state/prior.shell-json" ]; then
      jq -c '{id: (.bar.id // null), quickSettings: (.bar.quickSettings // null)}' \
        "$shell_json" > "$state/prior.shell-json" || return 1
    fi
    local tmp
    tmp="$(mktemp "$state/shell.json.XXXXXX")" || return 1
    jq -S --arg bar "omamobile.bar" --arg qs "omamobile.quicksettings" \
      --argjson panels '["omamobile.quicksettings","omamobile.launcher","omamobile.back","omamobile.switcher"]' '
      .version = (.version // 1)
      | .bar = ((.bar // {}) + {id: $bar, quickSettings: $qs})
      | .plugins = (((.plugins // []) + ($panels | map({id: .}))) | unique_by(.id))
    ' "$shell_json" > "$tmp" && mv -f "$tmp" "$shell_json" || return 1
    log "shell.json: bar.id=omamobile.bar, four panels enabled"
  else
    local prior
    if [ -f "$state/prior.shell-json" ]; then
      prior="$(cat "$state/prior.shell-json")"
    else
      prior='{"id":null,"quickSettings":null}'
    fi
    local tmp
    tmp="$(mktemp "$state/shell.json.XXXXXX")" || return 1
    jq -S --argjson prior "$prior" \
      --argjson panels '["omamobile.quicksettings","omamobile.launcher","omamobile.back","omamobile.switcher"]' '
      .bar = (.bar // {})
      | (if .bar.id == "omamobile.bar" then
           (if $prior.id == null then del(.bar.id) else .bar.id = $prior.id end)
         else . end)
      | (if .bar.quickSettings == "omamobile.quicksettings" then
           (if $prior.quickSettings == null then del(.bar.quickSettings)
            else .bar.quickSettings = $prior.quickSettings end)
         else . end)
      | (if .bar == {} then del(.bar) else . end)
      | .plugins = ((.plugins // []) | map(select((.id as $i | $panels | index($i)) | not)))
    ' "$shell_json" > "$tmp" && mv -f "$tmp" "$shell_json" || return 1
    rm -f "$state/prior.shell-json"
    log "shell.json: stock bar and plugin list restored"
  fi
}

# ----------------------------------------------------------- Hyprland rules --
# One marked block appended to the user's looknfeel.lua (additive, never a
# rewrite of existing lines) and removed whole on desktop.

look_begin='-- >>> omdroid mobile ui >>>'
look_end='-- <<< omdroid mobile ui <<<'

looknfeel_apply() { # mobile|desktop
  local mode="$1" tmp
  if [ "$mode" = "mobile" ]; then
    if [ ! -f "$looknfeel" ]; then
      log "no $looknfeel; Hyprland gaps left alone"
      return 0
    fi
    tmp="$(mktemp "$state/looknfeel.lua.XXXXXX")" || return 1
    awk -v begin="$look_begin" -v end="$look_end" '
      $0 == begin { skip = 1; next }
      $0 == end { skip = 0; next }
      !skip { print }
    ' "$looknfeel" > "$tmp" || return 1
    {
      printf '%s\n' "$look_begin"
      cat <<'LUA'
-- Touch-sized, edge to edge: no gaps, no borders, fully opaque windows, and
-- dialogs centred and capped to the screen. Written by /etc/phoshdroid/omdroid-ui.sh
-- while ~/.config/omdroid/ui says mobile (or is absent).
hl.config({ general = { gaps_in = 0, gaps_out = 0, border_size = 0 } })
-- Real touch (wl_touch) moves the pointer to each finger; hide it on a touch,
-- as a phone does. A mouse brings it back. The stock config sets this false.
hl.config({ cursor = { hide_on_touch = true } })
hl.window_rule({ match = { class = ".*" }, tag = "-default-opacity", opacity = "1 1" })
hl.window_rule({
  match = { modal = true },
  maximize = false,
  float = true,
  center = true,
  max_size = { "(monitor_w)", "(monitor_h)" },
})
LUA
      if [ "${OMDROID_ANIMATIONS:-0}" != "1" ]; then
        cat <<'LUA'
-- No animations in the mobile UI: each transition is one frame instead of a
-- dozen full-damage frames on a CPU-rendered shell. Put OMDROID_ANIMATIONS=1
-- in ~/.config/omdroid/session.env to keep the Omarchy animations.
hl.config({ animations = { enabled = false } })
LUA
      fi
      printf '%s\n' "$look_end"
    } >> "$tmp"
    mv -f "$tmp" "$looknfeel"
    log "looknfeel.lua: mobile rules in place"
    sha256sum "$looknfeel" | awk '{print $1}' > "$state/looknfeel.sha256" 2>/dev/null
  else
    [ -f "$looknfeel" ] || return 0
    tmp="$(mktemp "$state/looknfeel.lua.XXXXXX")" || return 1
    awk -v begin="$look_begin" -v end="$look_end" '
      $0 == begin { skip = 1; next }
      $0 == end { skip = 0; next }
      !skip { print }
    ' "$looknfeel" > "$tmp" || return 1
    mv -f "$tmp" "$looknfeel"
    rm -f "$state/looknfeel.sha256"
    log "looknfeel.lua: mobile rules removed"
  fi
}

# ------------------------------------------------------------- plugin links --

link_plugins() {
  local id src dst
  mkdir -p "$plugin_dir"
  # Shared QML has no manifest and is not itself a plugin. Keep its relative
  # import beside the five user-directory links (QML resolves their URLs).
  src="$vendor/plugins/omdroid.android"
  dst="$plugin_dir/omdroid.android"
  if [ -d "$src" ] && { [ ! -e "$dst" ] || [ -L "$dst" ]; }; then
    ln -sfn "$src" "$dst"
  fi
  for id in $plugin_ids; do
    src="$vendor/plugins/$id"
    dst="$plugin_dir/$id"
    if [ ! -f "$src/manifest.json" ]; then
      log "missing vendored plugin $src"
      continue
    fi
    if [ -L "$dst" ]; then
      [ "$(readlink "$dst")" = "$src" ] && continue
      ln -sfn "$src" "$dst" && log "relinked $id"
    elif [ -e "$dst" ]; then
      log "keeping $dst: a real directory, not our link"
    else
      ln -s "$src" "$dst" && log "linked $id"
    fi
  done
}

unlink_plugins() {
  local id dst
  dst="$plugin_dir/omdroid.android"
  if [ -L "$dst" ] && [ "$(readlink "$dst")" = "$vendor/plugins/omdroid.android" ]; then
    rm "$dst"
  fi
  for id in $plugin_ids; do
    dst="$plugin_dir/$id"
    if [ -L "$dst" ]; then
      case "$(readlink "$dst")" in
        "$vendor"/*) rm -f "$dst" && log "unlinked $id" ;;
        *) log "keeping $dst: not our link" ;;
      esac
    fi
  done
}

link_helpers() {
  local name src dst
  if ! mkdir -p "$local_bin" 2>/dev/null; then
    log "cannot create $local_bin"
    return 0
  fi
  for name in $helper_names; do
    src="$vendor/bin/$name"
    dst="$local_bin/$name"
    [ -f "$src" ] || continue
    chmod 755 "$src" 2>/dev/null
    if [ -L "$dst" ]; then
      [ "$(readlink "$dst")" = "$src" ] && continue
      ln -sfn "$src" "$dst" && log "relinked $dst"
    elif [ -e "$dst" ]; then
      log "keeping $dst: not our link"
    else
      ln -s "$src" "$dst" 2>/dev/null || log "cannot link $dst"
    fi
  done
}

# ---------------------------------------------------------------- app icons --

# Web-app entries in ~/.local/share/applications name a bare icon (Icon=hey)
# that no icon theme has: their PNGs sit beside them as icons/<entry>.png, a
# folder neither the shell icon index (*/apps/* under the XDG icon dirs, see
# AppLibrary.qml) nor the Qt theme lookup reads, so the drawer drew them
# blank (14 of 19 entries on the tablet, 13 of them with a PNG there). Mobile
# links each such PNG into
# ~/.local/share/icons/hicolor/512x512/apps/<Icon>.png, never over a theme or
# pixmap icon of that name; desktop removes exactly the links that point into
# applications/icons.
apps_dir="$home/.local/share/applications"
icon_links="$home/.local/share/icons/hicolor/512x512/apps"

link_app_icons() {
  local entry stem icon png dst made=0
  [ -d "$apps_dir/icons" ] || return 0
  for entry in "$apps_dir"/*.desktop; do
    [ -f "$entry" ] || continue
    stem="$(basename "$entry" .desktop)"
    png="$apps_dir/icons/$stem.png"
    [ -f "$png" ] || continue
    icon="$(sed -n 's/^Icon=//p' "$entry" | head -n 1)"
    case "$icon" in "" | */* | *" "*) continue ;; esac
    if compgen -G "$root/usr/share/icons/hicolor/*/apps/$icon.*" > /dev/null ||
      compgen -G "$root/usr/share/pixmaps/$icon.*" > /dev/null; then
      continue
    fi
    dst="$icon_links/$icon.png"
    if [ -L "$dst" ]; then
      [ "$(readlink "$dst")" = "$png" ] && continue
      case "$(readlink "$dst")" in
        "$apps_dir/icons/"*) ln -sfn "$png" "$dst" ;;
        *) log "keeping $dst: not our link"; continue ;;
      esac
    elif [ -e "$dst" ]; then
      log "keeping $dst: not our link"
      continue
    else
      mkdir -p "$icon_links" && ln -s "$png" "$dst" || continue
    fi
    made=$((made + 1))
  done
  if [ "$made" -gt 0 ]; then log "app icons: linked $made web-app icons"; fi
}

unlink_app_icons() {
  local dst gone=0
  [ -d "$icon_links" ] || return 0
  for dst in "$icon_links"/*.png; do
    [ -L "$dst" ] || continue
    case "$(readlink "$dst")" in
      "$apps_dir/icons/"*) rm -f "$dst" && gone=$((gone + 1)) ;;
    esac
  done
  if [ "$gone" -gt 0 ]; then log "app icons: removed $gone links"; fi
  # Only the folders this created, and only while empty.
  rmdir "$icon_links" "${icon_links%/apps}" "$home/.local/share/icons/hicolor" "$home/.local/share/icons" 2>/dev/null
  return 0
}

# ------------------------------------------------------- idle screensaver --

# Omarchy's idle service runs omarchy-launch-screensaver after idle.screensaver
# seconds. That needs xdg-terminal-exec to name the terminal and ttfx to draw,
# and this guest ships neither (nor the branding text it animates), so every
# idle period ended in a "Screensaver only runs in Alacritty, Foot, Ghostty,
# or Kitty" notification and no screensaver. Android already owns the screen
# timeout, so in both modes the screensaver is turned off with Omarchy's own
# flag (what `omarchy-toggle screensaver-off` writes), once: the record in
# $state says we set it, so a user who turns it back on keeps it on.
toggles="$home/.local/state/omarchy/toggles"

screensaver_off() {
  local flag="$toggles/screensaver-off" mark="$state/screensaver-off.ours"
  [ -e "$flag" ] && return 0
  if [ -e "$mark" ]; then
    log "screensaver: turned back on by the user; leaving it on"
    return 0
  fi
  if mkdir -p "$toggles" && : > "$flag"; then
    : > "$mark"
    log "screensaver: off (no xdg-terminal-exec or ttfx in this guest)"
  fi
}

# --------------------------------------------------------------------- main --

mkdir -p "$state" "$config/omdroid" 2>/dev/null

# The session environment block in start.sh sits just before the watcher, after
# this script has run, so the animation switch read here (OMDROID_ANIMATIONS)
# comes from the same optional user file, read the same way. The
# marker stops a second read when the caller already sourced it: the watcher
# restarts the shell by running this file, and it inherited the values.
session_env="$home/.config/omdroid/session.env"
if [ -z "${OMDROID_SESSION_ENV_READ:-}" ] && [ -f "$session_env" ]; then
  set -a
  . "$session_env"
  set +a
  export OMDROID_SESSION_ENV_READ=1
  log "session.env: read $session_env"
fi

mode="mobile"
if [ -f "$mode_file" ]; then
  mode="$(tr -d '[:space:]' < "$mode_file" 2>/dev/null)"
  case "$mode" in
    mobile | desktop) ;;
    "")
      mode="mobile"
      ;;
    *)
      log "unknown mode '${mode}' in ${mode_file}; using mobile"
      mode="mobile"
      ;;
  esac
fi

# The pin in ui-pin is the user choice and wins over the host-written mode
# while it names one (contract section 8); auto, missing or anything else
# defers to the ui file.
pin_file="$config/omdroid/ui-pin"
pin="auto"
if [ -f "$pin_file" ]; then
  pin="$(tr -d '[:space:]' < "$pin_file" 2>/dev/null)"
  case "$pin" in
    mobile | desktop) mode="$pin" ;;
    *) pin="auto" ;;
  esac
fi

log "mode ${mode} (${mode_file}, pin ${pin})"

case "$mode" in
  mobile)
    theme_apply mobile
    shell_json_apply mobile
    looknfeel_apply mobile
    link_plugins
    link_helpers
    link_app_icons
    screensaver_off
    ;;
  desktop)
    theme_apply desktop
    shell_json_apply desktop
    looknfeel_apply desktop
    unlink_plugins
    unlink_app_icons
    screensaver_off
    ;;
esac

exit 0
