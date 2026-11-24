# Brief R2-HOST: the Android app launches the desktop the same way every time, on any screen

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`.

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`, read `AGENTS.md`).
Contract with the guest side: `docs/omarchy/launch-contract.md` (read it whole first, never edit it).
Your report, created in your first calls and updated after EVERY item:
`docs/omarchy/r2-host-report.md`.

## Goal

Phil asked for two things: the app must launch predictably, and it must work on different screen
sizes. Your half is the host app (Kotlin under `app/`). You have NO device: you prove your work
with unit tests and a clean compile, and you hand the parent an exact list of what to check on the
tablet. Everything you cannot run is labelled UNTESTED ON DEVICE.

## Hard rules

- NO `adb` at all: a sibling agent is driving the tablet. NO `:app:assembleDebug`, no packager
  script: the parent builds the APK.
- You own `app/**` and your report. Plus exactly ONE hunk in the submodule: the margin code in
  `termux-x11/app/src/main/java/com/termux/x11/MainActivity.java` (item H5). Nothing else under
  `termux-x11/` or `termux-app/` (AGENTS.md: the `conn_fd` split and the touch path must not be
  touched). Never `rootfs/**`, `docs/omarchy/tools/**`, the contract, or the other report.
- No git command that changes anything (no add, commit, stash, checkout, restore, clean), in the
  repo or in the submodules. `git diff` / `git status` are fine.
- Build and test, always with this heap (the box is short of memory, the default asks for 32 GB):
  `./gradlew :app:compileDebugKotlin :app:test -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" --console=plain`
  and once at the end `./gradlew :app:lintDebug` with the same `-D` option. Give gradle calls a
  600 s timeout. Compile after each item before starting the next; never stack two uncompiled
  items.
- Tests: JUnit 5 in `app/src/test/kotlin/com/phoshdroid/app/...`, same package as the code,
  method names are backtick sentences (look at `settings/ProotConfigWriterTest.kt`). Pure logic
  goes into small classes with no Android imports so it can be tested; do not refactor working
  code just to test it.
- Keep `LauncherActivity` `singleTask` and keep `bootstrapCompleted` / `bootstrapInProgress`
  process-scoped (AGENTS.md). Match the existing style. No drive-by rewrites of old Phosh-era
  comments beyond the lines you change.
- The Kotlin tree already holds uncommitted work from earlier today (the whole Omarchy port).
  That is the baseline; do not "restore" anything.

## Facts, verified today (line numbers are for the tree as it is now)

Chain: `LauncherActivity` extracts assets, starts the in-process X server (Xlorie), starts
`ProotService` (proot guest running `/etc/phoshdroid/start.sh`: cage -> Hyprland -> shell), waits,
then starts `com.termux.x11.MainActivity`, which shows the X root.

1. **Readiness is broken.** `LauncherActivity.kt:401-418` clears stale files with `stale.delete()`.
   `tmp/omarchy-runtime/hypr` is a non-empty directory, so it survives, and
   `waitForHyprlandReady` (585-598) sees the PREVIOUS run's instance directory and returns at once.
   Logcat today: `17:20:43.676 Starting proot` -> `17:20:43.677 Launching X11 activity...`, and
   `18:05:04.621` launch -> `18:05:05.127 bootstrap finished while backgrounded`. The desktop itself
   needs about 9 s. `ProotService.prepareRuntimeDir()` does delete recursively, but it runs later,
   in `onStartCommand`.
2. **The launch path opened a Settings page.** `requestNotificationPermission()` used to end in
   `requestAllFilesAccess()`; that system page stopped the launcher, so the guard at line 556
   (`isAtLeast(STARTED)`) skipped the hand-over on every cold start. The call is already removed
   (lines 230-246); `requestAllFilesAccess()` (248-263) is now unused. UNTESTED ON DEVICE.
3. **A cold start after a display-size change kills the app.** `checkForDisplayShift("onCreate")`
   (63, 152-186) compares the window size with the last saved one and calls `restartApp()`
   (205-228: `killProcess` plus an `AlarmManager` activity `PendingIntent` 200 ms later) even though
   no session exists yet in a fresh process. On Android 14+ such a background activity start may be
   blocked, so the app can simply vanish. `DisplayShiftMonitor.kt` does the same whenever the app
   returns to the foreground with a different size: it stops the session and restarts the process.
   That made sense for Phosh (phoc could not resize). The Omarchy guest follows the X root live
   (contract section 3), so a restart now only destroys the user's open apps.
4. **Gaps are hard-coded pixels.** `TOP_GAP_PX = 160`, `BOTTOM_GAP_PX = 200` (637-638), used for
   `XLORIE_HEIGHT` (487) and both `MainActivity` intents (192-193, 566-567). They were sized for
   Phosh's lock-screen swipe on one phone. On the tablet they waste a 140 px black band above the
   bar. The bottom gap exists so the Termux:X11 extra-key bar does not cover the desktop.
5. **Termux:X11 facts** (read-only, `termux-x11/app/src/main/...`):
   - `MainActivity.java:174-191` reads the two extras and sets them as `topMargin` / `bottomMargin`
     of `lorieView`. But `res/layout/main_activity.xml:26` gives `lorieView`
     `android:layout_gravity="center"`, and a centred child of a FrameLayout is placed at
     `(parentH - height)/2 + topMargin - bottomMargin`. Measured: margins 160/200 put the view at
     row 140, not 160. With margins 0/165 it would sit at row -83, cutting the bar off.
   - Extra-key bar height (`MainActivity.java:696-697`):
     `Math.round(37.5f * displayMetrics.density * rows)`, rows = number of rows of the extra-keys
     config, 2 in the default (`utils/TermuxX11ExtraKeys.java:43`). It is shown when the prefs
     `showAdditionalKbd` and `additionalKbdVisible` are true (the launcher writes both, 374-387).
   - Pref `adjustHeightForEK` (default false) must stay false: it changes the layout only after the
     X client connects, which would resize the root at every launch.
   - Pref `hideCutout=true` (what the launcher writes today) makes the window extend UNDER a
     display cutout (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`); `false` selects the default mode,
     where Android keeps a full-screen window clear of the cutout in every rotation.
   - The X root follows the surface: when `lorieView` is shown at a size, Xlorie resizes the root
     to it. `XLORIE_WIDTH` / `XLORIE_HEIGHT` only set the root before a surface exists
     (`cpp/lorie/InitOutput.c:714`).
6. **A dead host process is launched every start.** `ProotService` still starts weston
   (`WestonCompositor.kt`, `ProotService.kt:53-58, 100-108, 179, 184, 197`); it dies in 3 ms
   (`weston.log`: `fatal: failed to create compositor backend`) because the Wayland parent is cage
   inside the guest now. `LauncherActivity.kt:274-276` still requires
   `lib/libweston-16/x11-backend.so` for the prefix to count as ready.
7. **`ProotService` returns `START_STICKY`** (84). If Android restarts it with a null intent the
   process is fresh: there is no X server, width and height are 0, and a guest is started anyway.
   `buildEnvironment` would export `OMARCHY_OUTPUT_WIDTH=0`; in Lua `0` is truthy, so the guest
   would force a `0x0` mode.
8. **Defaults disagree.** `PhoshdroidPreferences.bindSdcard` defaults to `true`,
   `res/xml/preferences.xml` says `false`. `ui_scale` is `"2"` for every screen.
9. `<prefix>/tmp/omarchy-runtime` is the guest's `XDG_RUNTIME_DIR`. Only three names are cleaned
   (`ProotService.kt:217-230`); today it held 15 stale `dbus-*` sockets, and `<prefix>/tmp` held 40
   stale `proot-<pid>-*` sockets and directories from dead sessions.
10. Tablet numbers for your tests: window 3200x2136, `densityDpi` 352 (density 2.2), no cutout,
    2 extra-key rows -> bottom gap `round(37.5 * 2.2 * 2)` = 165, X size 3200x1971.

## Items, in this order

### H1. Never open Settings on the launch path; ask where the feature is
Keep the launch path free of `requestAllFilesAccess()`. Move the request to `SettingsFragment`:
when `bind_sdcard` is switched on and `Environment.isExternalStorageManager()` is false, open the
all-files page then. Make the two `bind_sdcard` defaults agree on `false`.

### H2. Readiness that means "the desktop is drawn" (contract sections 2 and 5)
- One helper (plain `java.io.File` code, unit-tested on a temp directory) that prepares a session:
  wipe `<prefix>/tmp/omarchy-runtime/` completely and recreate it (0700), delete stale
  `<prefix>/tmp/proot-*` entries, delete the X/Wayland leftovers the launcher lists today, keep the
  log files. Call it from the launcher BEFORE the X server and the service start; `ProotService`
  must not wipe again after the guest may have started writing.
- Replace `waitForHyprlandReady` with the contract's wait rule: ready marker
  `tmp/omarchy-runtime/omarchy-ready`; fallback when a `hypr/<instance>` directory has existed for
  25 s with no marker (an older guest image); hard cap 120 s; return at once if
  `ProotService.isRunning()` turns false. Put the decision in a small pure function (inputs: marker
  present, instance-dir age, elapsed, session alive) so the four outcomes are unit-tested.
- Log one line per phase with tag `Phoshdroid`, text starting `launch:` and the milliseconds since
  the launcher was created: prefix-extracted, rootfs-extracted, x11-socket, session-started,
  ready (with the reason: marker / fallback / timeout / session-died), handover (done / deferred).

### H3. Hand-over that cannot be skipped silently
Gate the hand-over on `RESUMED` instead of `STARTED` (a permission dialog pauses the launcher;
starting the display under it buries the dialog). When it is deferred, `onResume` must complete it;
check that every path that sets `bootstrapCompleted` leads to a hand-over once the launcher is
resumed, and that a launch-path failure (`showError`) is never followed by a hand-over.

### H4. No self-kill on a cold start, no session restart on a size change
- In a fresh process (no bootstrap done or running, session not running) a different saved size is
  just recorded: never `restartApp()`.
- With a live session, a size change no longer stops the session or restarts the process: record
  the new size and carry on (the guest follows the X root). Keep the old restart code reachable
  behind ONE `const val RESTART_SESSION_ON_DISPLAY_SHIFT = false` used by both `LauncherActivity`
  and `DisplayShiftMonitor`, so the device round can flip it if the guest side fails its test.

### H5. Geometry from the device, not from constants (contract section 1)
- New pure class (no Android imports), unit-tested: input window width/height, the four
  display-cutout insets, density, extra-key rows, extra-keys shown; output X width, X height,
  bottom gap. Rule: X width = width - left - right insets; bottom gap = shown ?
  `round(37.5f * density * rows)` : 0; X height = height - top - bottom insets - bottom gap; if
  the result is not positive, fall back to the window size with no gap. No rounding to even (the
  guest does that). Tests must include fact 10 (3200x2136, 2.2, 2 rows -> 165, 3200x1971), a phone
  with a top cutout, extra keys hidden, and the non-positive fallback.
- `LauncherActivity`: delete `TOP_GAP_PX` / `BOTTOM_GAP_PX`; compute the geometry once per
  hand-over from `windowManager.currentWindowMetrics` (bounds, and
  `windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())`),
  `resources.displayMetrics.density`, and the extra-key rows
  (`com.termux.x11.utils.TermuxX11ExtraKeys.getExtraKeysInfo()?.matrix?.size`, falling back to 2
  when it is null — check the real accessor names in that class). Use it for `XLORIE_WIDTH/HEIGHT`,
  for `ProotService.start(...)`, and for both `MainActivity` intents: top extra 0, bottom extra =
  bottom gap. Keep the existing three-step fallback for a 0x0 window.
- Termux:X11 prefs the launcher writes: `hideCutout` false (was true), `adjustHeightForEK` false,
  the rest as today.
- The one submodule hunk: in `MainActivity.java` where the margins are applied, also set the
  layout gravity of `lorieView` to top + centre-horizontal when a padding extra is present, so
  top/bottom margins mean what they say (view at row `top`, height = parent - top - bottom). Do
  not touch the XML or anything else there. Say in the report that the submodule now has an
  uncommitted change.

### H6. Scale from the density
`ui_scale` gets the values `auto`, `1`, `2`, default `auto` (preference XML + its arrays +
`PhoshdroidPreferences`; a stored `1` or `2` keeps meaning itself). Pure, unit-tested function:
`auto` = 2 when `densityDpi >= 240` and the shorter window edge / 2 >= 360, else 1. Tests: tablet
(352 dpi, 2136) -> 2; 1080x2400 at 420 -> 2; 1920x1200 at 240 -> 2; 1280x800 at 160 -> 1;
720x1600 at 320 -> 2; 600x1024 at 240 -> 1. The service receives the resolved integer.

### H7. The service never starts a session it cannot show
On a null intent (system restart of a sticky service) do not start anything: stop the service.
Return `START_NOT_STICKY`. `buildEnvironment` leaves out `OMARCHY_OUTPUT_WIDTH` and
`OMARCHY_OUTPUT_HEIGHT` unless both are greater than 0 (unit test with the existing builder).

### H8. Remove the dead host compositor
Delete the weston launch from `ProotService` and delete `WestonCompositor.kt`. The prefix counts
as ready on its `.extraction_complete` marker alone. Do not delete binaries or touch
`bootstrap.bin`: list in the report what in the APK is now dead weight
(`jniLibs/arm64-v8a/libweston-bin.so`, the weston tree inside the Termux prefix) for the parent.

### H9. Final gate
`:app:compileDebugKotlin :app:test` green, `:app:lintDebug` run (report new warnings in files you
touched), `git status --short` and `git diff --stat -- app termux-x11` in the report.

## Report format (`docs/omarchy/r2-host-report.md`)

```
# R2 host report
HEAD: <git rev-parse --short HEAD>   Model that ran: deepseek-flash   Device: none (by brief)

## STATUS TABLE
| Item | Status | Proof (test names + result, or "compiles, UNTESTED ON DEVICE") |
| H1 settings page | ... |
| H2 readiness | ... |
| H3 hand-over | ... |
| H4 display shift | ... |
| H5 geometry | ... |
| H6 scale | ... |
| H7 service | ... |
| H8 weston removed | ... |
| H9 gate | (the gradle summary lines, test count) |

## Contract: anything that is wrong or that you could not meet (with evidence), else "met"
## Changes (file -> what changed, one line each)
## Device checklist for the parent
(numbered, each with the exact observable: a logcat `launch:` line, a probe value, a screen row)
## Dead weight left in the APK
## Not done / risks
## Log (short, appended as you go)
```

Flip a row the moment its proof exists. You have roughly 90 tool calls per turn: read the files
you need in batches, and when you are near the end write the table's final state before anything
else.

## Final reply

At most 12 lines: per item its status, the gradle result line and test count, the files changed,
the three most important device checks, the report path.


---

## CONTINUATION (added by the parent after your first turn ended at the call cap)

Your first turn's work is in the tree and the parent has read it: H1-H7 are DONE as you reported
(last green gate after H7: 34 unit tests). Do not redo or re-verify them item by item. The tree
does NOT compile right now. Finish in this order, with one gradle run after C1 and one after C2:

**C1. Finish H8 (verified state of the tree at 18:25):**
- `app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt` still calls `weston?.stop()` at
  about lines 188, 193 and 206 (the field is gone) and still has
  `private val runtimeDir get() = ...` at line 34. Remove the three calls and the comment
  `// No guest left to render into weston.`; remove `runtimeDir` if nothing uses it any more.
- Delete `app/src/main/kotlin/com/phoshdroid/app/proot/WestonCompositor.kt` (it is an untracked
  file, plain `rm` of that one path). If the command is blocked for approval, do not retry:
  overwrite the file with a single comment line instead and say so in the report.
- `LauncherActivity.kt` about lines 370-380: the prefix is ready when `.extraction_complete`
  exists; drop the `lib/libweston-16/x11-backend.so` condition and fix the comments around it.
- Comments that are now false and sit next to code you own: `ProotCommandBuilder.kt` lines 21 and
  49 ("Shared with weston", "weston and the guest"), `LauncherActivity.kt` about lines 470 and 513.
  One-line corrections only.
- Then: `./gradlew :app:compileDebugKotlin :app:test -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" --console=plain`
  (600 s timeout). Fix what it reports.

**C2. H9 gate:** `./gradlew :app:lintDebug` with the same `-D` option; then
`git status --short app termux-x11` and `git diff --stat -- app` and `git -C termux-x11 diff --stat`.

**C3. The report is the deliverable and is still the skeleton.** Write
`docs/omarchy/r2-host-report.md` in full, in the format of this brief: every row of the STATUS
TABLE with its proof (test class and method names, the gradle summary line and test count, or
"compiles, UNTESTED ON DEVICE"), the Changes list (file -> one line), the numbered Device
checklist (exact observable per check: the `launch:` logcat lines in order, what `probe.sh` must
print, which screen rows the desktop must occupy with the new gaps: X view from row 0, 1971 px
tall on the tablet, extra-key bar below it), Dead weight left in the APK, Not done / risks
(include: the submodule has an uncommitted hunk; what happens with an OLD guest image that writes
no ready marker; anything in H3/H4 you are unsure about), and the Log.
Write the report BEFORE anything optional. If the call budget runs short, the order of priority
is: compiling tree, then the report, then lint.

Final reply as the brief says (at most 12 lines), starting with whether the tree compiles and the
test count.
