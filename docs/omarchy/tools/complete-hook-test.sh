#!/bin/bash
# Host test (R11 S4): the completeness hook, omdroid-session and the systemctl wrapper.
# No device: every path the hook touches is redirected into a scratch tree through the
# OMDROID_COMPLETE_* overrides, and a small background loop plays the host app that
# takes the guest request file. usage: complete-hook-test.sh   (exit 0 = all passed)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
D=$(cd "$HERE/../../../rootfs/overlay/etc/phoshdroid/complete" && pwd)
OMARCHY=${OMARCHY_DIR:-/nonexistent}   # an extracted usr/share/omarchy, optional (only the menu template comes from it)
T=$(mktemp -d "${TMPDIR:-/tmp}/complete-hook.XXXXXX")
fail=0
ok() { echo "PASS  $1"; }
bad() { echo "FAIL  $1  -- $2"; fail=1; }
check() { if eval "$2"; then ok "$1"; else bad "$1" "$2"; fi; }

export OMDROID_SESSION_LOG=$T/session.log HOME=$T/home OMDROID_COMPLETE_DIR=$D OMDROID_COMPLETE_BIN=$T/bin OMDROID_COMPLETE_OMARCHY_BIN=$T/omarchy-bin
export OMDROID_COMPLETE_REAL_XTE=$T/no-such-xte OMDROID_COMPLETE_NVIM=no-such-nvim OMDROID_COMPLETE_NANO=bash
# The clipboard bridge is a long-running process with its own start/stop section below;
# off here so its pkill does not shift the counters the session tests assert on.
export OMDROID_COMPLETE_CLIPBOARD=0
# The browser section links each Chromium-family name the image has; a fake /usr/bin keeps
# that independent of what this host happens to install.
# Each fake prints its arguments one per line, so the launcher section can read back
# exactly what the real browser would be started with. The desktop entries live in a
# scratch tree too: Chromium's names its binary by full path, Brave's does not.
mkdir -p "$T/usrbin" "$T/apps-src" "$T/etc"
for b in brave chromium; do printf '#!/bin/sh\nprintf "%%s\\n" "$@"\n' > "$T/usrbin/$b"; chmod 755 "$T/usrbin/$b"; done
printf '[Desktop Entry]\nName=Chromium\nExec=/usr/bin/chromium %%U\nActions=new-window;\n\n[Desktop Action new-window]\nName=New Window\nExec=/usr/bin/chromium\n' > "$T/apps-src/chromium.desktop"
printf '[Desktop Entry]\nName=Brave\nExec=brave %%U\n' > "$T/apps-src/brave.desktop"
export OMDROID_COMPLETE_USRBIN=$T/usrbin OMDROID_COMPLETE_APPS_SRC=$T/apps-src OMDROID_COMPLETE_APPS=$T/apps OMDROID_COMPLETE_ETC=$T/etc
unset XDG_CONFIG_HOME OMDROID_GPU
export OMDROID_COMPLETE_LOG=$T/hook.log OMDROID_HOST_DIR=$T/host OMDROID_SESSION_WAIT_DS=8
mkdir -p "$HOME/.config/hypr" "$HOME/.config/omarchy/extensions"
printf -- '-- user looknfeel\nhl.config({ general = { gaps_in = 3 } })\n' > "$HOME/.config/hypr/looknfeel.lua"
LOOK=$HOME/.config/hypr/looknfeel.lua; cp "$LOOK" "$T/look.orig"
mkdir -p "$HOME/.config/omarchy/extensions" "$T/bin" "$T/omarchy-bin" "$T/host" "$T/stubs"
# The dev box may itself run Omarchy: stub every command omdroid-session calls that could reach a
# real desktop (window close, OSD, notification), and refuse to go on unless the stubs win in PATH.
for c in omarchy-osd omarchy-hyprland-window-close-all; do printf '#!/bin/bash\n: stub\n' > "$T/stubs/$c"; chmod 755 "$T/stubs/$c"; done
printf '#!/bin/bash\nprintf "%%s\\n" "$*" >> "%s/notified"\n' "$T" > "$T/stubs/omarchy-notification-send"; chmod 755 "$T/stubs/omarchy-notification-send"
printf '#!/bin/bash\nprintf "%%s\\n" "$*" >> "%s/pkill.calls"\n' "$T" > "$T/stubs/pkill"; chmod 755 "$T/stubs/pkill"
printf '#!/bin/bash\nprintf "%%s\\n" "$*" >> "%s/hyprctl.calls"\nexit ${HYPRCTL_RC:-0}\n' "$T" > "$T/stubs/hyprctl"; chmod 755 "$T/stubs/hyprctl"
export PATH=$T/stubs:$PATH
for c in omarchy-osd omarchy-hyprland-window-close-all omarchy-notification-send pkill hyprctl; do
    case "$(command -v $c)" in "$T"/stubs/*) ;; *) echo "refusing to run: $c is not stubbed"; exit 2 ;; esac
done
cp "$OMARCHY/config/omarchy/extensions/omarchy-menu.jsonc" "$HOME/.config/omarchy/extensions/" 2> /dev/null || printf '{\n}\n' > "$HOME/.config/omarchy/extensions/omarchy-menu.jsonc"
for n in lock logout reboot shutdown; do
    printf '#!/bin/bash\necho original-%s\n' "$n" > "$T/omarchy-bin/omarchy-system-$n"; chmod 755 "$T/omarchy-bin/omarchy-system-$n"
    ln -s "$T/omarchy-bin/omarchy-system-$n" "$T/bin/omarchy-system-$n"
done
BIN=$T/bin
FLAG=$HOME/.local/state/omarchy/indicators/stay-awake
STATE_DIR=$HOME/.local/state/omdroid/complete
REC=$STATE_DIR/stay-awake.ours
EXTF=$HOME/.config/omarchy/extensions/omarchy-menu.jsonc
cp "$EXTF" "$T/ext.orig"

hook() { env "$@" bash "$D/session-start.sh" > "$T/hook.out" 2>&1; }

# --- default run
hook OMDROID_X=1
for n in lock logout reboot shutdown; do
    check "default: omarchy-system-$n is our wrapper" "[ \"\$(readlink $BIN/omarchy-system-$n)\" = $D/bin/omarchy-system-$n ]"
done
check "default: systemctl wrapper and omdroid-session are linked" "[ -L $BIN/systemctl ] && [ -L $BIN/omdroid-session ]"
check "default: the stay-awake flag is created and recorded as ours" "[ -e $FLAG ] && [ \"\$(cat $REC)\" = created ]"
check "default: the menu block is in the extension" "grep -q 'omdroid:begin' $EXTF"
hook OMDROID_X=1
check "second run changes nothing (idempotent)" "[ \"\$(cat $REC)\" = created ] && [ \$(grep -c 'omdroid:begin' $EXTF) = 1 ]"

# --- terminal shim, editor default, banners
check "default: the xdg-terminal-exec shim is linked while no real one exists" "[ \"\$(readlink $BIN/xdg-terminal-exec)\" = $D/bin/xdg-terminal-exec ]"
check "default: the default editor is nano (nvim missing), recorded" "[ \"\$(cat $HOME/.local/state/omarchy/defaults/editor)\" = nano ] && [ \"\$(cat $STATE_DIR/editor.ours)\" = created ]"
check "default: the banner block is in looknfeel.lua, the user's lines untouched" "grep -q 'disable_hyprland_guiutils_check' $LOOK && grep -q 'gaps_in = 3' $LOOK && [ \$(grep -c 'omdroid complete >>>' $LOOK) = 1 ]"
hook OMDROID_X=1
check "second run: one banner block, one record" "[ \$(grep -c 'omdroid complete >>>' $LOOK) = 1 ]"
hook OMDROID_COMPLETE_BANNERS=0
check "BANNERS=0 removes the block and restores looknfeel.lua byte for byte" "cmp -s $LOOK $T/look.orig"
hook OMDROID_COMPLETE_EDITOR=0
hook OMDROID_COMPLETE_TERMINAL=0
check "TERMINAL=0 removes the shim link" "[ ! -e $BIN/xdg-terminal-exec ]"
hook OMDROID_COMPLETE_NVIM=bash
check "nvim installed later: the editor file we created is taken away" "[ ! -e $HOME/.local/state/omarchy/defaults/editor ]"
printf '#!/bin/bash\n: real\n' > "$T/real-xte"; chmod 755 "$T/real-xte"
hook OMDROID_COMPLETE_REAL_XTE=$T/real-xte
check "a real xdg-terminal-exec in the image wins: no shim link" "[ ! -e $BIN/xdg-terminal-exec ]"
printf '#!/bin/bash\nprintf "%s|" "$@" > %s/foot-args\n' '%s' "$T" > "$T/stubs/foot"; chmod 755 "$T/stubs/foot"
bash "$D/bin/xdg-terminal-exec" --app-id=org.omarchy.terminal --title=Omarchy --dir= -e bash -c 'echo hi'
check "the shim maps app-id, title and the command onto foot (empty --dir ignored)" "[ \"\$(cat $T/foot-args)\" = '--app-id|org.omarchy.terminal|--title|Omarchy|bash|-c|echo hi|' ]"
bash "$D/bin/xdg-terminal-exec" --dir="$T" btop
check "the shim passes --dir and a bare command" "[ \"\$(cat $T/foot-args)\" = '--working-directory|$T|btop|' ]"
hook OMDROID_X=1


# --- switches
hook OMDROID_COMPLETE_POWER=0
check "POWER=0: the original links are back and systemctl is gone" "[ \"\$(readlink $BIN/omarchy-system-lock)\" = $T/omarchy-bin/omarchy-system-lock ] && [ ! -e $BIN/systemctl ]"
hook OMDROID_COMPLETE_IDLE=guest
check "IDLE=guest: the flag we created is removed with its record" "[ ! -e $FLAG ] && [ ! -e $REC ]"
hook OMDROID_COMPLETE_MENU=0
check "MENU=0: the block is removed and the file is as it was" "! grep -q 'omdroid:begin' $EXTF && cmp -s $EXTF $T/ext.orig"
hook OMDROID_X=1
hook OMDROID_COMPLETE=0
check "COMPLETE=0: everything is undone" "[ ! -e $FLAG ] && [ ! -e $BIN/systemctl ] && ! grep -q 'omdroid:begin' $EXTF && [ \"\$(readlink $BIN/omarchy-system-reboot)\" = $T/omarchy-bin/omarchy-system-reboot ]"

# a flag the user already had is theirs: the guest switch must leave it
mkdir -p "$(dirname $FLAG)"; : > "$FLAG"
hook OMDROID_X=1
check "a stay-awake flag that was already there is recorded as preexisting" "[ \"\$(cat $REC)\" = preexisting ]"
hook OMDROID_COMPLETE_IDLE=guest
check "IDLE=guest does not remove a preexisting flag" "[ -e $FLAG ]"
hook OMDROID_COMPLETE=0

# --- the guest request: a fake host takes it
host_loop() { # records what it took into $T/seen
    : > "$T/seen"
    while [ ! -e "$T/stop-host" ]; do
        if [ -e "$OMDROID_HOST_DIR/request" ]; then cat "$OMDROID_HOST_DIR/request" >> "$T/seen"; rm -f "$OMDROID_HOST_DIR/request"; fi
        sleep 0.03
    done
}
host_loop &
HOST=$!
bash "$D/bin/omdroid-session" home; rc=$?
check "home: a host that takes the request gets 'home <epoch>' and the script exits 0" "[ $rc = 0 ] && grep -qE '^home [0-9]+\$' $T/seen"
bash "$D/bin/omdroid-session" lock
check "lock is mapped to home" "[ \$(grep -c '^home ' $T/seen) = 2 ]"
bash "$D/bin/omdroid-session" stop; bash "$D/bin/omdroid-session" restart; bash "$D/bin/omdroid-session" shutdown
check "stop, restart and shutdown are written as such" "grep -q '^stop ' $T/seen && grep -q '^restart ' $T/seen && grep -q '^shutdown ' $T/seen"
check "stop, restart and shutdown end the session through Hyprland's exit dispatcher (3 calls)" "[ \$(grep -c -F 'dispatch hl.dsp.exit()' $T/hyprctl.calls) = 3 ]"
check "... and tell the root broker to end each time, never signal Hyprland while the dispatcher works" "[ \$(grep -c -F '[o]mdroid-rootd' $T/pkill.calls) = 3 ] && ! grep -q -- '-x Hyprland' $T/pkill.calls"
check "home and lock never touch Hyprland or the broker" "[ \$(grep -c . $T/hyprctl.calls) = 3 ] && [ \$(grep -c . $T/pkill.calls) = 3 ]"
HYPRCTL_RC=1 bash "$D/bin/omdroid-session" stop
check "when the exit dispatcher fails the guest falls back to SIGTERM on Hyprland" "grep -q -- '-TERM -x Hyprland' $T/pkill.calls"
OMDROID_SESSION_END=term bash "$D/bin/omdroid-session" stop
check "OMDROID_SESSION_END=term skips the dispatcher" "[ \$(grep -c -- 'dispatch' $T/hyprctl.calls) = 4 ]"
bash "$D/bin/omdroid-session" nonsense 2> /dev/null; rc=$?
check "an unknown action is refused with status 2" "[ $rc = 2 ]"
: > "$T/seen"
bash "$D/bin/systemctl" suspend; bash "$D/bin/systemctl" --no-wall poweroff; bash "$D/bin/systemctl" reboot
check "systemctl suspend, poweroff and reboot become home, shutdown and restart" "[ \"\$(cut -d' ' -f1 $T/seen | tr '\\n' ' ')\" = 'home shutdown restart ' ]"
printf '#!/bin/bash\necho REAL "$@"\n' > "$T/real-systemctl"; chmod 755 "$T/real-systemctl"
out=$(OMDROID_REAL_SYSTEMCTL=$T/real-systemctl bash "$D/bin/systemctl" --user show-environment)
check "any other verb goes to the real systemctl unchanged" "[ \"$out\" = 'REAL --user show-environment' ]"
touch "$T/stop-host"; wait "$HOST" 2> /dev/null

# --- no host answers: the script must say so, not stay quiet
rm -f "$OMDROID_HOST_DIR/request"
pk_before=$(grep -c . "$T/pkill.calls"); hc_before=$(grep -c . "$T/hyprctl.calls")
bash "$D/bin/omdroid-session" stop > /dev/null 2>&1; rc=$?
check "no host: exit 1, a normal-urgency notification that names the cause, and the request file is cleaned up" "[ $rc = 1 ] && grep -q -- '-u normal.*does not answer' $T/notified && [ ! -e $OMDROID_HOST_DIR/request ]"
check "no host: the session is not ended (no extra pkill, no dispatcher call)" "[ \$(grep -c . $T/pkill.calls) = $pk_before ] && [ \$(grep -c . $T/hyprctl.calls) = $hc_before ]"

# --- clipboard bridge: started with the switch on, stopped with it off
printf '#!/bin/bash\nsleep 60\n' > "$T/bridge-stub"; chmod 755 "$T/bridge-stub"
OMDROID_COMPLETE_CLIPBOARD=1 OMDROID_COMPLETE_CLIPBOARD_CMD=$T/bridge-stub \
    bash "$D/session-start.sh" > /dev/null 2>&1
cbpid=$(cat "$HOME/.local/state/omdroid/complete/clipboard.pid" 2> /dev/null)
check "clipboard: the switch on starts the bridge" "grep -q 'clipboard: bridge started' $T/hook.log && [ -n '$cbpid' ]"
check "clipboard: the started process is alive" "kill -0 $cbpid 2> /dev/null"
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_CLIPBOARD_CMD=$T/bridge-stub \
    bash "$D/session-start.sh" > /dev/null 2>&1
check "clipboard: the switch off stops it" "grep -q 'clipboard: bridge off' $T/hook.log && [ ! -e $HOME/.local/state/omdroid/complete/clipboard.pid ]"
tries=0; while kill -0 "$cbpid" 2> /dev/null && [ $tries -lt 20 ]; do sleep 0.1; tries=$((tries+1)); done
check "clipboard: the process is gone after the switch off" "! kill -0 $cbpid 2> /dev/null"

# --- browsers: one launcher, linked for each family member the image has
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=1 OMDROID_COMPLETE_USRBIN=$T/usrbin bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: the switch on links an installed browser to the launcher" "[ \"\$(readlink $T/bin/brave)\" = \"$D/bin/chromium-proot\" ] && [ -x $D/bin/chromium-proot ]"
check "browser: the second family member shares that launcher" "[ \"\$(readlink $T/bin/chromium)\" = \"$D/bin/chromium-proot\" ]"
check "browser: a browser the image lacks is not linked" "[ ! -e $T/bin/vivaldi-stable ]"
check "browser: an entry that names its binary by full path gets a copy that does not" "grep -qx 'Exec=chromium %U' $T/apps/chromium.desktop && grep -qx 'Exec=chromium' $T/apps/chromium.desktop && ! grep -q '^Exec=/usr/bin/' $T/apps/chromium.desktop"
check "browser: the copy keeps every other line of the entry" "[ \"\$(grep -v '^Exec=' $T/apps/chromium.desktop | sed '\$d')\" = \"\$(grep -v '^Exec=' $T/apps-src/chromium.desktop)\" ]"
check "browser: an entry that already names it bare gets no copy" "[ ! -e $T/apps/brave.desktop ]"
# A read of the source entry that fails keeps the good copy: a sed that fails on that one
# file stands in for the entry vanishing between the check and the read.
mkdir -p "$T/failsed"
printf '#!/bin/bash\nfor a; do case "$a" in */apps-src/chromium.desktop) exit 2 ;; esac; done\nexec %s "$@"\n' "$(command -v sed)" > "$T/failsed/sed"
chmod 755 "$T/failsed/sed"
PATH=$T/failsed:$PATH OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=1 bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: a failed read of the source entry keeps the previous copy" "grep -qx 'Exec=chromium %U' $T/apps/chromium.desktop && grep -qx '\[Desktop Entry\]' $T/apps/chromium.desktop && [ ! -e $T/apps/chromium.desktop.new ]"

# The launcher itself, run through the link the hook made. Chromium's flags file is the
# image's shape: ANGLE on Vulkan with the Vulkan features on. Brave has no flags file.
printf '%s\n' '# image defaults' '--ozone-platform=x11' '--enable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan' \
    '--use-gl=angle' '--use-angle=vulkan' > "$HOME/.config/chromium-flags.conf"
SANDBOX_OFF="--no-sandbox --disable-dev-shm-usage --password-store=basic"
SOFTWARE_GL="--use-angle=gles --disable-gpu-compositing"
run() { "$@" | tr '\n' ' ' | sed 's/ $//'; }   # the arguments the fake browser received, on one line
check "launcher: kgsl mode passes only the sandbox flags" "[ \"\$(OMDROID_GPU=kgsl run $T/bin/chromium https://a.test)\" = \"$SANDBOX_OFF https://a.test\" ]"
check "launcher: no mode at all passes only the sandbox flags" "[ \"\$(run $T/bin/chromium https://a.test)\" = \"$SANDBOX_OFF https://a.test\" ]"
check "launcher: software mode answers a Vulkan flags file with ANGLE on GLES and software compositing" "[ \"\$(OMDROID_GPU=software run $T/bin/chromium https://a.test)\" = \"$SANDBOX_OFF $SOFTWARE_GL https://a.test\" ]"
check "launcher: the caller's own choice comes last, so it still wins" "[ \"\$(OMDROID_GPU=software run $T/bin/chromium --use-angle=swiftshader --app=https://a.test)\" = \"$SANDBOX_OFF $SOFTWARE_GL --use-angle=swiftshader --app=https://a.test\" ]"
check "launcher: software mode leaves a browser without a Vulkan flags file alone" "[ \"\$(OMDROID_GPU=software run $T/bin/brave https://a.test)\" = \"$SANDBOX_OFF https://a.test\" ]"
printf '%s\n' '--use-angle=vulkan' > "$T/etc/brave-flags.conf"; printf '%s\n' '--use-angle=gl' > "$HOME/.config/brave-flags.conf"
check "launcher: the last --use-angle of the flags files decides, as it does for the browser" "[ \"\$(OMDROID_GPU=software run $T/bin/brave)\" = \"$SANDBOX_OFF\" ]"

# A desktop entry somebody else put in the override directory is neither replaced nor removed.
printf '[Desktop Entry]\nName=Mine\nExec=/opt/mine\n' > "$T/apps/chromium.desktop"
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=1 bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: an entry this hook did not write is not replaced" "grep -qx 'Exec=/opt/mine' $T/apps/chromium.desktop"
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=0 bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: nor removed by the switch off" "grep -qx 'Exec=/opt/mine' $T/apps/chromium.desktop"
rm -f "$T/apps/chromium.desktop"
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=1 bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: the copy comes back once that entry is gone" "grep -qx 'Exec=chromium %U' $T/apps/chromium.desktop"
OMDROID_COMPLETE_CLIPBOARD=0 OMDROID_COMPLETE_BROWSER=0 OMDROID_COMPLETE_USRBIN=$T/usrbin bash "$D/session-start.sh" > /dev/null 2>&1
check "browser: the switch off removes the links" "[ ! -e $T/bin/brave ] && [ ! -e $T/bin/chromium ]"
check "browser: the switch off removes the entry copy" "[ ! -e $T/apps/chromium.desktop ]"

rm -rf "$T"
[ $fail = 0 ] && echo "all passed" || echo FAILED
exit $fail
