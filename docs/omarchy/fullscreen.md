# Fullscreen on a Samsung tablet

Omdroid's guest fits its Android window. In DeX, an immersive Android window can still
be a floating task: hiding the Android bars does not make that task fill the display.
Filling the Android display does not require fullscreen guest applications or
hiding Omarchy's workspace bar.
On the tested Samsung SM-X800, a fresh launch opened a 1514×1138 floating window on
the 2800×1752 built-in display, despite the DeX fullscreen launch metadata.

Use the DeX fullscreen button in Omdroid's window caption. For an ADB workflow, open
Omdroid, wait for its desktop, and run from the repository root:

```sh
export TABLET_SERIAL='<adb serial of your tablet>'
python3 docs/omarchy/tools/fullscreen.py
```

The helper checks that Omdroid's display activity has focus, identifies its task,
and checks that the device offers `WMShell desktopmode moveTaskOutOfDesk`. It moves
only that freeform task, then waits until the guest monitor matches the display's
maximum bounds, rounded down to even pixels as cage requires. A window already in
fullscreen only needs the geometry check. Unsupported DeX versions and split-screen
layouts get an instruction to use Android's fullscreen control instead.

This is an explicit device-tool action; it does not change how tapping the app icon
launches Omdroid. DeX can open the next session in a floating window again. A
connection script should run the helper after the display activity becomes active
and before starting a client that detects its output size at startup. No new
Android permission is needed.

## Keeping Omarchy's workspace bar

The mobile UI replaces the desktop bar with a phone status bar containing a clock
and connectivity icons. It does not show Omarchy's workspace buttons, even on an
empty desktop. To keep the standard desktop bar on the tablet, run inside the guest:

```sh
mkdir -p ~/.config/omdroid
printf 'desktop\n' > ~/.config/omdroid/ui-pin
```

The session watcher applies the change and restarts only the shell. Open apps
stay running, and the Android window still fills the tablet. The pin survives
rotation, docking and subsequent launches. Set it to `auto` to follow the host's
mobile/desktop choice again. Desktop mode uses Omarchy's workspace bar and app
launcher in place of the phone Home strip and Recent Apps gestures.

Guest applications normally leave the bar visible. Use an application's explicit
fullscreen control only when you want that application to cover the desktop bar
too. Omdroid does not force the YouTube web app into fullscreen.

On the test tablet, the standard 26-logical-pixel bar remained visible above the
running YouTube window at 1752×2800 and 2800×1752. Tapping a workspace selected it,
no bottom strip was reserved, and the open app survived the mode change and rotation.

## Extra keys and guest bars

The extra-key bar starts hidden, and the bottom-right keyboard button toggles it as
an overlay. When either the extra-key bar or Android's letter keyboard is visible,
that button dismisses both, including Android's keyboard toolbar. With both hidden,
it shows the extra-key bar again; the keyboard key in that bar opens Android's
letter keyboard. The extra-key bar should not shrink the guest output. On the test tablet, the guest
stayed 2800×1752 with the key bar both visible and hidden. An older APK reserved
160 pixels and exposed a 2800×1592 guest output; update to a build containing
[#17](https://github.com/zweck/omdroid/pull/17).

The guest shell's top bar and home panel are part of the desktop. Applications use
their own fullscreen control. Check the guest window state with:

```sh
docs/omarchy/tools/hc.sh j/monitors j/clients
```

At scale 2, a fullscreen window on the test tablet reports position `[0,0]`, size
`[1400,876]`, and `fullscreen: 2`. Android fullscreen, guest output size, and the
guest application's window state are three separate checks.

## Leaving a fullscreen guest app by touch in mobile mode

First tap the floating keyboard button to hide any extra-key rows and Android's
letter keyboard. These overlays cover the bottom gesture area, so a swipe starting
on them cannot reach the guest. Android may also reveal its own navigation bar
when a gesture starts at the physical edge.

Swipe a short distance upward from the bottom center and release to return to the
desktop. The app stays open. The gesture starts in a transparent area 180 logical
pixels wide and 18 logical pixels high, centered on the bottom edge. It draws no
pill and reserves no space. A tap there does not go Home or open the keyboard;
the small region is reserved for the swipe rather than the underlying app's taps.
The existing longer swipe or swipe-and-hold opens recent apps. Swipe upward on an
app's card to close its window; swipe sideways to browse the cards.

For a button instead of a gesture, use the optional
[extra-key layout](extra-keys.json). It adds **CLOSE** and **SUPER** to the two
existing rows, keeping the other keys. Open the extra-key bar, tap its gear, and
paste the JSON into the **Extra keys config** preference. Save any custom
layout first. This is an optional saved preference, not a change to the default
Termux:X11 layout. These buttons work in desktop mode too.

Tap **CLOSE** to send Omarchy's **Super+W** window-close shortcut. **Ctrl+W** is an
application shortcut and is not a reliable way to close the active desktop window.
The separate **SUPER** key is the extra-key modifier named `META` internally.
The **HOME** key sends the keyboard Home key; it does not return to the desktop.

The mobile status bar stays below fullscreen windows, including when the shell
remaps its surfaces after rotation. Normal windows still respect its reserved
height and keep their usual navigation strip.

With fullscreen explicitly enabled on the test tablet, the same running YouTube
window filled 1752×2800 in portrait and 2800×1752 in landscape. In both orientations,
a short bottom-center swipe
returned Home, a tap did not navigate away, and no bottom space was reserved.
Rotation and keyboard dismissal preserved the full display bounds. These checks
used the installed local integration with the same QML; they do not cover multiple
monitors or an APK built from the complete PR branch.
The optional CLOSE button and the recent-apps card swipe each closed a disposable
terminal on the portrait tablet while preserving the existing browser windows.
Recent Apps opened from both a normal window and a fullscreen window with the
keyboard overlays hidden.

## OpenDisplay receiver setup

OpenDisplay is an optional, separately installed application. When using a GTK
receiver, keep its default fullscreen behavior: remove `--windowed` from the
receiver launch command. Remove fixed `--width 1920 --height 1080` overrides so it
detects the output after Omdroid has reached fullscreen. A fixed 16:9 stream leaves
side margins on this tablet even when the receiver window is fullscreen.

Preserve any codec or library settings your installed receiver needs. The tested
setup used `--sink gtk --decoder avdec_h264 --scale 2 --refresh 30`; the sender used
scale 2 and a 30 fps limit. Scale is a UI choice, not a substitute for matching the
stream's aspect ratio. Restart an existing receiver and reconnect the sender after
changing the window geometry, so its advertised panel size is detected again.

The local integration was verified at 2800×1752 with nine five-second animation
samples at 29.8–30.0 rendered fps, no decoder errors or stalls, and a tablet tap
reaching the expected host button. A full stop and relaunch through the connection
helper restored fullscreen. These measurements cover that landscape tablet setup;
external DeX monitors and orientation changes during a stream were not tested.

## Startup callback crash

The embedded `MainActivity` formerly created its static callback handler with
`new Handler()`. If a host service first loads that class on a worker looper,
delayed UI callbacks run on that worker too. The reproduced cold-start failure was:

```text
CalledFromWrongThreadException: Expected: main Calling: omdroid-android-controls
  at MainActivity.onWindowFocusChanged
  at MainActivity.onPreferencesChangedCallback
```

The submodule update binds the handler explicitly to `Looper.getMainLooper()`.
The reproducer was the earlier local worker-based Android-controls bridge; current
upstream's bridge runs on the main looper. This fixes the underlying class-loading
dependency without reintroducing that older bridge. The corrected local APK passed
fresh launches without a new Android runtime crash.
