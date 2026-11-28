#!/bin/bash
# Omdroid completeness hook (R11 S4): Omarchy features that mean nothing, or fail
# silently, under proot and Android. Run by start.sh once per session, after
# session.env, as a plain bash script (not sourced). Returns in well under a second.
#
# Parts, each with its own switch in ~/.config/omdroid/session.env:
#   OMDROID_COMPLETE=0         turns every part off (restores what this hook changed)
#   OMDROID_COMPLETE_POWER=0   Logout, Reboot, Shutdown, Suspend and Lock keep Omarchy's own
#                              scripts (which fail silently here: no systemd, no uwsm)
#   OMDROID_COMPLETE_IDLE=guest  keep Omarchy's idle screensaver and idle lock on (default
#                              android: Android owns the screen timeout and the lock)
#   OMDROID_COMPLETE_MENU=0    leave the System menu as Omarchy ships it
#   OMDROID_COMPLETE_TERMINAL=0  no xdg-terminal-exec shim (the image has none, so every
#                              script that opens a terminal through it fails)
#   OMDROID_COMPLETE_EDITOR=0  keep Omarchy default editor (nvim, which the image lacks)
#   OMDROID_COMPLETE_BANNERS=0 keep Hyprland start-up warning banners
#   OMDROID_COMPLETE_CLIPBOARD=0  no clipboard bridge between the guest Wayland
#                              clipboard and the X display Android talks to
#   OMDROID_COMPLETE_BROWSER=0 keep the image's brave launcher (which aborts here: the
#                              sandbox helper cannot be root-owned 4755 inside proot)
# Every change is key-level and reversible; the state lives in ~/.local/state/omdroid/complete.
D=${OMDROID_COMPLETE_DIR:-/etc/phoshdroid/complete}
BIN=${OMDROID_COMPLETE_BIN:-/usr/local/bin}
USRBIN=${OMDROID_COMPLETE_USRBIN:-/usr/bin}
OMARCHY_BIN=${OMDROID_COMPLETE_OMARCHY_BIN:-/usr/share/omarchy/bin}
STATE=$HOME/.local/state/omdroid/complete
LOG=${OMDROID_COMPLETE_LOG:-/tmp/omdroid-complete.log}
: > "$LOG" 2> /dev/null || LOG=/dev/null
say() { echo "complete: $*" | tee -a "$LOG"; }

chmod 755 "$D"/bin/* "$D"/*.py "$D"/*.sh 2> /dev/null

all=${OMDROID_COMPLETE:-1}
power=${OMDROID_COMPLETE_POWER:-1}
idle=${OMDROID_COMPLETE_IDLE:-android}
menu=${OMDROID_COMPLETE_MENU:-1}
terminal=${OMDROID_COMPLETE_TERMINAL:-1}
editor=${OMDROID_COMPLETE_EDITOR:-1}
banners=${OMDROID_COMPLETE_BANNERS:-1}
clipboard=${OMDROID_COMPLETE_CLIPBOARD:-1}
browser=${OMDROID_COMPLETE_BROWSER:-1}
[ "$all" = 0 ] && { power=0; idle=guest; menu=0; terminal=0; editor=0; banners=0; clipboard=0; browser=0; }

# --- power and session entries -------------------------------------------------
# The omarchy-* names are links in /usr/local/bin to /usr/share/omarchy/bin (set at image
# time), so a link here wins over them in PATH and over any keybinding that calls them.
# systemctl keeps the real binary for every verb that is not a power verb.
link_ours() { # $1 name, $2 target
    ln -sfn "$2" "$BIN/$1"
}
if [ "$power" != 0 ]; then
    for n in lock logout reboot shutdown; do
        link_ours "omarchy-system-$n" "$D/bin/omarchy-system-$n"
    done
    link_ours omdroid-session "$D/bin/omdroid-session"
    link_ours systemctl "$D/bin/systemctl"
    say "power: session wrappers linked"
else
    for n in lock logout reboot shutdown; do
        if [ "$(readlink "$BIN/omarchy-system-$n" 2> /dev/null)" = "$D/bin/omarchy-system-$n" ]; then
            ln -sfn "$OMARCHY_BIN/omarchy-system-$n" "$BIN/omarchy-system-$n"
        fi
    done
    [ "$(readlink "$BIN/systemctl" 2> /dev/null)" = "$D/bin/systemctl" ] && rm -f "$BIN/systemctl"
    say "power: Omarchy scripts kept (switch off)"
fi

# --- idle lock and screensaver ---------------------------------------------------
# Omarchy idle service: after 150 s the screensaver (needs a terminal chooser and ttfx,
# neither is in the guest) and after 300 s the lock (needs /etc/pam.d/omarchy-lock-password,
# absent, so the shell answers missing-pam and nothing locks). Android owns both, and the
# guest sees no input whenever another Android app is on top, so the idle cycle would fire
# all the time. Omarchy own switch is the stay-awake indicator file; the record says who
# created it, so a later choice of the user (toggle, or the switch above) is respected.
flag=$HOME/.local/state/omarchy/indicators/stay-awake
ours=$STATE/stay-awake.ours
mkdir -p "$STATE" 2> /dev/null
if [ "$idle" != guest ]; then
    if [ ! -e "$ours" ]; then
        if [ -e "$flag" ]; then
            echo preexisting > "$ours"
        else
            mkdir -p "$(dirname "$flag")" && : > "$flag" && echo created > "$ours"
        fi
        say "idle: Android owns the lock and screen timeout; stay-awake flag $(cat "$ours")"
    fi
else
    if [ "$(cat "$ours" 2> /dev/null)" = created ]; then
        rm -f "$flag" "$ours"
        say "idle: guest idle lock and screensaver back on (switch)"
    fi
fi

# --- the System menu -------------------------------------------------------------
if [ "$menu" != 0 ]; then
    python3 "$D/omdroid-menu-ext.py" apply 2>&1 | tee -a "$LOG"
else
    python3 "$D/omdroid-menu-ext.py" remove 2>&1 | tee -a "$LOG"
fi

# --- terminal shim, default editor, start-up banners --------------------------------
# xdg-terminal-exec: only while no real one is installed.
real_xte=${OMDROID_COMPLETE_REAL_XTE:-/usr/bin/xdg-terminal-exec}
if [ "$terminal" != 0 ] && [ ! -x "$real_xte" ]; then
    link_ours xdg-terminal-exec "$D/bin/xdg-terminal-exec"
    say "terminal: xdg-terminal-exec runs foot"
else
    [ "$(readlink "$BIN/xdg-terminal-exec" 2> /dev/null)" = "$D/bin/xdg-terminal-exec" ] && rm -f "$BIN/xdg-terminal-exec"
fi

# Default editor: Omarchy launches nvim unless ~/.local/state/omarchy/defaults/editor names
# another; the image has nano and no nvim. Written only when that file is absent, recorded,
# and taken away again when it still says nano and nvim has appeared.
edfile=$HOME/.local/state/omarchy/defaults/editor
edours=$STATE/editor.ours
nvim_cmd=${OMDROID_COMPLETE_NVIM:-nvim}
nano_cmd=${OMDROID_COMPLETE_NANO:-nano}
if [ "$editor" != 0 ] && ! command -v "$nvim_cmd" > /dev/null 2>&1 && command -v "$nano_cmd" > /dev/null 2>&1; then
    if [ ! -e "$edfile" ] && [ ! -e "$edours" ]; then
        mkdir -p "$(dirname "$edfile")" && echo nano > "$edfile" && echo created > "$edours"
        say "editor: default editor nano (nvim is not installed)"
    fi
else
    if [ "$(cat "$edours" 2> /dev/null)" = created ] && [ "$(cat "$edfile" 2> /dev/null)" = nano ]; then
        rm -f "$edfile" "$edours"
        say "editor: default editor file removed"
    fi
fi

# Hyprland start-up banners: a marked block in the user's looknfeel.lua, before Hyprland starts.
if [ "$banners" != 0 ]; then
    python3 "$D/omdroid-hypr-block.py" apply 2>&1 | tee -a "$LOG"
else
    python3 "$D/omdroid-hypr-block.py" remove 2>&1 | tee -a "$LOG"
fi
# --- clipboard bridge ---------------------------------------------------------------
# Under cage the Wayland clipboard and the X display Termux:X11 serves are separate
# worlds: wl-copy then wl-paste works inside Hyprland, while an X client on :0 reads
# CLIPBOARD owner=0x0. Android only ever talks to the X side (Xlorie forwards the X
# selection owner's text to Android, and takes the selection itself when Android
# copies), so without this nothing reaches Android in either direction.
# omdroid-clipboard.py owns CLIPBOARD on :0 for text copied in the guest and copies
# back whatever someone else puts there. Text only, 1 MiB cap, no images.
cb_pid=$STATE/clipboard.pid
cb_cmd=${OMDROID_COMPLETE_CLIPBOARD_CMD:-$D/omdroid-clipboard.py}
stop_bridge() {
    if [ -f "$cb_pid" ]; then
        old=$(cat "$cb_pid" 2> /dev/null)
        # The pid could have been reused since the file was written, so the command
        # line must still name this bridge before anything is signalled.
        if [ -n "$old" ] && tr '\0' ' ' < "/proc/$old/cmdline" 2> /dev/null | grep -qF "$cb_cmd"; then
            kill "$old" 2> /dev/null
        fi
        rm -f "$cb_pid"
    fi
    # Backstop for an instance left without a pid file. Only a python interpreter
    # whose second argument is this very script counts: a looser match also hits
    # any wrapper (proot, bash -c) whose command line merely carries the text, and
    # that kills the caller.
    for p in /proc/[0-9]*; do
        pid=${p#/proc/}
        [ "$pid" = "$$" ] && continue
        set -- $(tr '\0' '\n' < "$p/cmdline" 2> /dev/null)
        case "${1##*/}" in python3*) ;; *) continue ;; esac
        case "${2:-}" in */omdroid-clipboard.py) kill "$pid" 2> /dev/null ;; esac
    done
}
if [ "$clipboard" != 0 ]; then
    stop_bridge
    nohup "$cb_cmd" >> "$LOG" 2>&1 < /dev/null &
    echo $! > "$cb_pid"
    say "clipboard: bridge started (pid $(cat "$cb_pid"))"
else
    stop_bridge
    say "clipboard: bridge off (switch)"
fi

# --- browsers ------------------------------------------------------------------------
# No member of the Chromium family can sandbox itself inside proot: the helper must be
# root-owned mode 4755 and the zygote needs user namespaces, neither of which exists
# here. A bare brave therefore aborts before a window appears, and the next launch says
# it quit unexpectedly. The image's Chromium only works because Omarchy's own launcher
# passes the flags (omarchy-launch-browser's proot branch). One launcher covers the
# family: it takes the name it was called by, and only names the image actually has are
# linked, so /usr/local/bin/brave and /usr/local/bin/chromium both get the flags from
# a desktop entry, the menu, omarchy-launch-browser or a terminal.
#
# A desktop entry that names its binary by full path walks past that link: Arch's
# chromium.desktop says Exec=/usr/bin/chromium %U. For such an entry a copy goes into
# /usr/local/share/applications, which XDG_DATA_DIRS lists before /usr/share, with the
# path dropped from every Exec line so PATH finds the launcher. The copy is rewritten
# each session (a package update is picked up) and carries a last-line marker, so only
# a file this hook wrote is ever replaced or removed.
browser=${OMDROID_COMPLETE_BROWSER:-1}
browsers="brave chromium google-chrome-stable microsoft-edge-stable vivaldi-stable opera"
APPS_SRC=${OMDROID_COMPLETE_APPS_SRC:-/usr/share/applications}
APPS=${OMDROID_COMPLETE_APPS:-/usr/local/share/applications}
entry_mark="# omdroid-complete: Exec without /usr/bin, so the launcher in /usr/local/bin runs"
entry_is_ours() { [ "$(tail -n 1 "$APPS/$1.desktop" 2> /dev/null)" = "$entry_mark" ]; }
entry_ours() { # $1 browser name
    if grep -Eqs "^Exec=/usr/bin/$1( |\$)" "$APPS_SRC/$1.desktop" \
            && { [ ! -e "$APPS/$1.desktop" ] || entry_is_ours "$1"; }; then
        mkdir -p "$APPS"
        # Each step gates the next: a failed read leaves the previous copy in place
        # rather than installing a file that holds only the marker.
        sed -E "s#^Exec=/usr/bin/$1( |\$)#Exec=$1\\1#" "$APPS_SRC/$1.desktop" > "$APPS/$1.desktop.new" \
            && echo "$entry_mark" >> "$APPS/$1.desktop.new" \
            && mv -f "$APPS/$1.desktop.new" "$APPS/$1.desktop"
        rm -f "$APPS/$1.desktop.new"
    elif entry_is_ours "$1"; then
        rm -f "$APPS/$1.desktop"
    fi
}
if [ "$browser" != 0 ]; then
    for name in $browsers; do
        [ -x "$USRBIN/$name" ] && { link_ours "$name" "$D/bin/chromium-proot"; entry_ours "$name"; }
    done
    say "browser: chromium-family browsers launch with the sandbox off (proot)"
else
    for name in $browsers; do
        [ "$(readlink "$BIN/$name" 2> /dev/null)" = "$D/bin/chromium-proot" ] && rm -f "$BIN/$name"
        entry_is_ours "$name" && rm -f "$APPS/$name.desktop"
    done
    say "browser: the image launchers kept (switch)"
fi

exit 0
