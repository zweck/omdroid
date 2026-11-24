# R3 host report — one launch decision, session lifecycle, guest overlay

Status table. `PENDING` until the item is proven on the gate
(`./gradlew :app:compileDebugKotlin :app:test`), then `PASS`/`FAIL` plus the proof.

| Item | Status | Proof |
|---|---|---|
| H10 one launch decision | PASS | `launch/LaunchDecision.kt` + 7 tests; `bootstrap()` skips the X block on a restart (`launch: restart-in-process`), `SessionPrep.prepare(keepXServer)` + test; gate `:app:compileDebugKotlin :app:test` BUILD SUCCESSFUL |
| H11 service tells the truth | PASS | `ProotService.finishSession` on the main thread clears `active`, records `lastExitCode`/`lastExitAtMs`, drops the notification and starts `LauncherActivity` with `session_ended`; retry now gated by `proot/SessionExit.shouldRetry` (4 tests); ended screen + `Start again` in `activity_launcher.xml`. Gate BUILD SUCCESSFUL |
| H12 not-ready launch ends in error | PASS | `bootstrap()` on `TIMEOUT`/`SESSION_DIED`: `launch: failed <reason>`, `ProotService.stop`, error view with log tail + `Start again`, returns `false`; the `handing over anyway` branch is gone. Gate BUILD SUCCESSFUL |
| H13 guest scripts travel with the APK | PASS | `syncGuestOverlay` (app/build.gradle.kts) copies the packager's five files + `fake-proc/**` into `build/generated/guestOverlay/guest-overlay/etc/phoshdroid/`; it is a registered assets srcDir and every `merge*Assets` depends on it. Proof below: the merged assets output lists `guest-overlay/etc/phoshdroid/...`, byte-identical to the repo overlay. `launch/GuestOverlay.kt` + 5 tests. Gate BUILD SUCCESSFUL |
| H14 names the user sees | PASS | Channel name `Omdroid Service` (+ description), clipboard label `Omdroid Error`; every other `Phoshdroid` hit is an identifier or log tag — table below. Gate BUILD SUCCESSFUL |
| H15 gate + report | PASS | `:app:test :app:lintDebug` BUILD SUCCESSFUL; `unit tests 51 failed 0`; lint `0 errors, 41 warnings`, none new; details below |

## Premise checks

All four premises the brief lists were read in the tree before editing and hold:

- `LauncherActivity` gates every entry point on `bootstrapCompleted` alone; `bootstrapFailed` was the only other input. (Read: `onCreate`, `onNewIntent`, `onResume`.)
- `ProotService.active` is set true in `start()` and `startProot()` and cleared only in `stopSession()` and the "retry exited non-zero" branch, so exit 0 and a successful retry left it true.
- `desktop not ready (...); handing over anyway` existed verbatim in `bootstrap()`.
- The rootfs is extracted only when `.extraction_complete` is missing, so overlay changes never reached an existing install.
- Packager block "Applying overlay" (`rootfs/package-omarchy-rootfs.sh`, lines 66-76) copies exactly `start.sh`, `session-child.sh`, `omarchy-plugin-index.sh`, `fit-x11.py`, `omarchy-watch.py` and `fake-proc/`, and chmods 755 all but `fit-x11.py`.

## Log sequences (H10)

Expected `launch:` lines (tag `Phoshdroid`, milliseconds appended by `logLaunch`):

(a) cold start, fresh process: `decision start` → (`stopped previous session` only if the flag was stale) → `prefix-extracted already=false` → `rootfs-extracted` → `x11-socket exists=true` → `session-started` → `ready marker` → `handover done`.

(b) icon tap while running (process alive, session up): `decision forward` → `handover done`.

(c) icon tap after the notification's Stop: `decision start` → `restart-in-process` → `removed stale <prefix>/tmp/omarchy-runtime` → `x11-socket exists=true` → `session-started` → `ready marker` → `handover done`. No `CmdEntryPoint` load and no second `X11Server` thread; `.X11-unix/X0` and `.X0-lock` are not in the removed list.

## The Stop path, line by line (H11)

1. Stop is a `PendingIntent.getService` on `ProotService` with `ACTION_STOP` (`buildNotification`). The user is in `com.termux.x11.MainActivity`; no screen is ever started by this path.
2. `onStartCommand` → `stopSession()`: `stopping = true`, `active = false`, `prootProcess.destroy()`, `waitFor()`, `prootProcess = null`. Then `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` → `onDestroy` → `stopSession()` again (idempotent).
3. The monitor thread, blocked in `runProcess` on `readText()`, unblocks on the closed pipe, writes `tmp/proot-exit.log`, and returns at `if (stopping) return@Thread`. `finishSession` is not reached, so there is no `launch: session ended` line and no activity start.
4. The app process stays alive (no `killProcess` on this path), so `bootstrapCompleted` stays `true` and `xServerStarted` stays `true`.
5. Next icon tap: `forwardToMainActivity()` finished the launcher, so the tap is `onCreate` → `consumeSessionEnded(intent)` returns null → `LaunchDecision.decide(inProgress=false, completed=true, sessionRunning=ProotService.isRunning())`. `isRunning()` reads `active`, which step 2 cleared → `LaunchAction.START`.
6. `startBootstrap()` → `bootstrap()`: `ProotService.isRunning()` is false, so no pre-stop; extraction checks pass; `SessionPrep(tmpDir).prepare(keepXServer = true)` keeps `.X11-unix/X0`; `xServerStarted` is true, so it logs `launch: restart-in-process` and skips the env/`CmdEntryPoint`/thread block; it still waits for and logs `x11-socket exists=true`; `ProotService.start()` sets `active = true`; then the ready wait and the hand-over.

So after Stop the next tap starts a session, not a hand-over — and it reuses the X server of the still-alive process instead of starting a second one on `:0`.


## H13 proof — the guest overlay in the APK

`./gradlew :app:mergeDebugAssets --console=plain` → `BUILD SUCCESSFUL`, then

```
$ find app/build -path '*guest-overlay*' -type f | sort
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fake-proc/cpuinfo
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fake-proc/loadavg
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fake-proc/meminfo
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fake-proc/stat
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fake-proc/uptime
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/fit-x11.py
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/omarchy-plugin-index.sh
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/omarchy-watch.py
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/session-child.sh
app/build/generated/guestOverlay/guest-overlay/etc/phoshdroid/start.sh
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fake-proc/cpuinfo
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fake-proc/loadavg
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fake-proc/meminfo
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fake-proc/stat
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fake-proc/uptime
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/fit-x11.py
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/omarchy-plugin-index.sh
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/omarchy-watch.py
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/session-child.sh
app/build/intermediates/assets/debug/mergeDebugAssets/guest-overlay/etc/phoshdroid/start.sh
```

Byte identity with the repo overlay (`diff -r rootfs/overlay/etc/phoshdroid <merged>/guest-overlay/etc/phoshdroid`):
the only differences are the two files the packager does not install (`bwrap-shim.sh`, `config.defaults`), and
`sha256sum` matches on `start.sh` (`fe3f35a9…5cc`) on both sides. The copied list in `app/build.gradle.kts` **must
stay identical to the "Applying overlay" block of `rootfs/package-omarchy-rootfs.sh`** — that block is what the
packager installs, this task is what an APK update installs, and a file added to one without the other is a guest
that differs between a fresh install and an updated one.

Wiring note, found the hard way: the dir registered with `assets.srcDir(...)` is an asset **root**, so the task writes
into `…/guestOverlay/guest-overlay/etc/phoshdroid/` and the **parent** (`…/generated/guestOverlay`) is the srcDir —
registering `guest-overlay` itself puts the scripts at `assets/etc/phoshdroid/`, which is not the path
`GuestOverlay.ASSET_ROOT` reads. A registered srcDir only reaches the merge if `merge*Assets` depends on the copy
task, which `tasks.matching { merge*Assets }.configureEach { dependsOn(syncGuestOverlay) }` does for every variant.

## H14 names

Every `Phoshdroid` hit under `app/src/main` (`.kt`/`.xml`), changed or left:

| Hit | Decision | Why |
|---|---|---|
| `PhoshdroidApp.kt` channel name `Phoshdroid Service` | **changed** → `Omdroid Service` | Read by the user in Android Settings → Apps → Notifications. Same channel id, so the name updates in place on an existing install. |
| `PhoshdroidApp.kt` channel description `Shows when the postmarketOS desktop is running` | **changed** → `Shows when the Omdroid desktop is running` | User-visible text, and the guest is Omarchy now, not pmOS. Same channel id. |
| `LauncherActivity.kt` clipboard label `Phoshdroid Error` | **changed** → `Omdroid Error` | Shown in the clipboard UI when the user copies an error. |
| `LauncherActivity.kt`, `DisplayShiftMonitor.kt`, `ProotService.kt` — `Log.*` tag `Phoshdroid` / `Phoshdroid.Monitor` | left | Log tags, not user text; the contract pins the tag `Phoshdroid` and the device tools grep for it. |
| `PhoshdroidApp` class + `AndroidManifest android:name=".PhoshdroidApp"`, `PhoshdroidPreferences` class, `Theme.Phoshdroid` style, prefs names `phoshdroid_prefs` / `phoshdroid_display_state`, channel id `phoshdroid_service`, package `com.phoshdroid.app` | left | Identifiers, not text. Renaming a prefs file or a channel id loses the user's settings on update; the package id is pinned by the brief. |
| `strings.xml` | left | `app_name` is already `Omdroid`; `notification_title` says "Omarchy desktop is running" (the guest OS name, deliberately not the product name) and `welcome_toast` already says Omdroid. The four new strings are English text with no product name. |

## H15 gate

`./gradlew :app:test :app:lintDebug -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" --console=plain`:

```
BUILD SUCCESSFUL in 19s
325 actionable tasks: 34 executed, 291 up-to-date
```

- Unit tests: `python3 docs/omarchy/tools/unit-count.py` → `unit tests 51 failed 0` (34 before this round + 17 new: 7 LaunchDecision, 4 SessionExit, 5 GuestOverlay, 1 SessionPrep `keepXServer`).
- Lint: 41 warnings, `0 errors` in `app/build/reports/lint-results-debug.txt`. **No new warning from this round.** The only warning on new code was `SetWorldReadable` in `GuestOverlay.setExecutable`; it is intentional (the guest reads the scripts as uid 1000 through proot) and is now `@SuppressLint`ed with that reason. The three warnings that mention `LauncherActivity.kt` sit on code the brief told me not to touch: `currentDisplayId()`'s `SDK_INT >= R` check (`ObsoleteSdkInt`), `checkForDisplayShift`'s `commit()` (`ApplySharedPref`, deliberate: a restarted process must see the new values), and `forwardToMainActivity`'s `"Display failed: …"` (`SetTextI18n`).
- `git status --short app` (whole app dir; entries marked `mine` are this round):

```
 M app/build.gradle.kts                                              mine
 M app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt        mine
 M app/src/main/kotlin/com/phoshdroid/app/PhoshdroidApp.kt           mine
 M app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt      mine
 M app/src/main/res/layout/activity_launcher.xml                     mine
 M app/src/main/res/values/strings.xml                               mine
?? app/src/main/kotlin/com/phoshdroid/app/launch/LaunchDecision.kt   mine (new)
?? app/src/main/kotlin/com/phoshdroid/app/launch/GuestOverlay.kt     mine (new)
?? app/src/main/kotlin/com/phoshdroid/app/proot/SessionExit.kt       mine (new)
?? app/src/test/kotlin/com/phoshdroid/app/launch/LaunchDecisionTest.kt  mine (new)
?? app/src/test/kotlin/com/phoshdroid/app/launch/GuestOverlayTest.kt    mine (new)
?? app/src/test/kotlin/com/phoshdroid/app/proot/SessionExitTest.kt      mine (new)
 M app/src/main/kotlin/com/phoshdroid/app/launch/SessionPrep.kt       mine
 M app/src/test/kotlin/com/phoshdroid/app/launch/SessionPrepTest.kt   mine
 M app/src/main/jniLibs/arm64-v8a/libbash.so                          not mine (round 2)
?? app/src/main/jniLibs/arm64-v8a/libweston-bin.so                    not mine
 M app/src/main/kotlin/com/phoshdroid/app/DisplayShiftMonitor.kt      not mine
 M app/src/main/kotlin/com/phoshdroid/app/SettingsFragment.kt         not mine
 M app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt not mine
 M app/src/main/kotlin/com/phoshdroid/app/proot/ProotCommandBuilder.kt not mine
 M app/src/main/kotlin/com/phoshdroid/app/proot/ProotDistroManager.kt not mine
 M app/src/main/kotlin/com/phoshdroid/app/settings/PhoshdroidPreferences.kt not mine
 M app/src/main/kotlin/com/phoshdroid/app/settings/ProotConfigWriter.kt not mine
 M app/src/main/res/drawable/ic_launcher_background.xml               not mine
 M app/src/main/res/drawable/ic_launcher_foreground.xml               not mine
 M app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml                 not mine
?? app/src/main/res/drawable/ic_launcher_monochrome.xml               not mine
 M app/src/main/res/xml/preferences.xml                               not mine
 M app/src/test/kotlin/com/phoshdroid/app/settings/ProotConfigWriterTest.kt not mine
```

- `git diff --stat` (whole tree, so it includes other rounds' uncommitted work — `rootfs/`, `README.md`, `.gitignore`, the Termux submodule pointer):

```
 .gitignore                                         |   8 +-
 README.md                                          | 233 ++++---
 app/build.gradle.kts                               |  33 +
 app/src/main/jniLibs/arm64-v8a/libbash.so          | Bin 880240 -> 880416 bytes
 .../com/phoshdroid/app/DisplayShiftMonitor.kt      |  22 +-
 .../kotlin/com/phoshdroid/app/LauncherActivity.kt  | 757 ++++++++++++++-------
 .../com/phoshdroid/app/PhoshdroidApp.kt            |   4 +-
 app/src/main/kotlin/com/phoshdroid/app/SettingsFragment.kt |  38 +-
 .../phoshdroid/app/extraction/AssetExtractor.kt    |  15 +-
 .../com/phoshdroid/app/proot/ProotCommandBuilder.kt | 186 ++--
 .../com/phoshdroid/app/proot/ProotDistroManager.kt |  16 +-
 .../com/phoshdroid/app/proot/ProotService.kt       | 293 ++++++--
 .../app/settings/PhoshdroidPreferences.kt          |  23 +-
 .../phoshdroid/app/settings/ProotConfigWriter.kt   |   4 +-
 .../main/res/drawable/ic_launcher_background.xml   |   4 +-
 .../main/res/drawable/ic_launcher_foreground.xml   |  74 +-
 app/src/main/res/layout/activity_launcher.xml      |  14 +-
 app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml |   2 +
 app/src/main/res/values/strings.xml                |  57 +-
 app/src/main/res/xml/preferences.xml               |  42 +-
 .../app/settings/ProotConfigWriterTest.kt          |   6 +-
 rootfs/build-bootstrap.sh                          | 110 ++-
 rootfs/overlay/etc/phoshdroid/start.sh             | 372 +++-------
 termux-x11                                         |   0
 24 files changed, 1387 insertions(+), 926 deletions(-)
```

The `LauncherActivity.kt` line count includes round 2's uncommitted changes; the new files are untracked, so `git diff --stat` does not list them.

## Device checks for the parent

Follow in this order on the tablet; each step names the line or screen that proves it.

**H13 first — it changes what the guest runs.**
1. Install the new APK over the existing install (same app id, so `adb install -r <apk>` keeps app data), open it once and let it finish launching, then close it.
2. `adb logcat -d -s Phoshdroid | grep 'launch: guest-overlay'` → one `launch: guest-overlay files=10 changed=<m>` line. On the very first launch after the update `changed` is the number of files that differed (up to 10) and each is named by a `launch: guest-overlay updated etc/phoshdroid/<file>` line; on the next launch `changed=0`.
3. `adb shell run-as com.phoshdroid.app sha256sum files/usr/var/lib/proot-distro/installed-rootfs/omarchy/etc/phoshdroid/start.sh` against `sha256sum rootfs/overlay/etc/phoshdroid/start.sh` on the host → equal.

**H10 — one launch decision.**
1. Cold start from the icon: log has `launch: decision start`, then `launch: x11-socket exists=true`, `launch: session-started`, `launch: ready marker`, `launch: handover done`. Screen shows the desktop.
2. Go back to the launcher/recents and tap the icon again while the desktop runs: exactly `launch: decision forward` → `launch: handover done`, and **no** new `=== Phase 5: About to start X11 server ===` line.
3. Notification → Stop. The app goes away and the notification disappears. Tap the icon: `launch: decision start` → `launch: restart-in-process` (and **no** `CmdEntryPoint class loaded OK` line) → `launch: x11-socket exists=true` → `launch: session-started` → `launch: ready marker` → `launch: handover done`. The `launch: removed stale …` lines must not name `.X11-unix/X0` or `.X0-lock`. The desktop comes back.

**H11 — the service tells the truth.**
1. End the desktop from inside the guest (`hyprctl dispatch exit`, or the shell's logout). Expected: notification gone, the app comes forward showing **"The desktop session ended."** plus the last lines of the session log and a **Start again** button; log `launch: session ended exit=0 ui=shown` (or `ui=blocked` when Android refused the background start — then no screen, and the next icon tap still starts).
2. Tap **Start again**: `launch: decision start` → `launch: restart-in-process` → `launch: ready marker` → `launch: handover done`; desktop returns.
3. Crash case: inside the guest `pkill -9 Hyprland` (or `kill -9 1`) → screen says "The desktop session stopped unexpectedly (exit <n>)." with `launch: session ended exit=<n>`. A non-zero exit **inside 30 s** is retried once (`proot retry exited`), a non-zero exit after 30 s must not appear as a retry line at all.
4. Stop action: no `launch: session ended` line and no screen — the Stop stays silent.

**H12 — a launch that is not ready ends in an error.**
1. Set the guest's custom startup script (Settings → startup script) to `/bin/sleep 600`, launch, wait out the 120 s cap: log `launch: ready timeout` → `launch: failed timeout` → screen **"The desktop did not start (timeout)."** with the log tail and Start again; **no** `launch: handover done`, no blank display.
2. Restore the startup script and tap Start again → a normal launch from the START path.
3. Session died: script `/bin/false` → the retry runs, exits, and the screen shows the session-ended text (exit 1); the readiness probe's `launch: failed session_died` may also appear — the ended screen is the one the user must see.

## Risks (cannot be proven without a device)

- **In-process restart after Stop.** The second bootstrap reuses the X server (`xServerStarted`) and the tests only prove the decision and the prep. If Lorie's server has died inside the still-alive process, `x11-socket exists=false` and the launch continues into a session nothing can display — the 10 s socket wait (20 × 500 ms) is the only signal. Watch step H10-3 for this.
- **`finishSession`'s activity start.** Android 14+ may refuse a background activity start; the refusal is caught and logged as `ui=blocked`, and the next icon tap still starts. Which of the two happens is device behaviour.
- **Exit code fidelity.** `Process.waitFor()` reports proot's exit code, not the guest's; a guest that exits 0 through a killed proot may surface as 137/143. The screen's wording is still right ("stopped unexpectedly"), but the number may not be the guest's.
- **Guest ownership/modes.** `GuestOverlay.sync` writes with the app's uid and sets 0755; proot maps the guest's uid 1000 onto it. That the guest can always exec the rewritten scripts is proven only by running it.
- **The overlay copy list is duplicated** between `app/build.gradle.kts` and the packager's "Applying overlay" block. Nothing enforces equality; a file added to one side only is a guest that differs between a fresh install and an updated one. A future round could generate both from one list.
- **Main-thread ordering.** `finishSession` is posted to the main looper so the flag, notification and activity start are ordered, but the readiness probe polls from an IO thread; a guest exit inside the same 200 ms poll window can still be seen as `SESSION_DIED` first. The user-visible outcome is the same screen either way.
- **H14 channel rename** only lands on an existing install because `createNotificationChannel` runs again with the same channel id on every start; if a device keeps the old name, that call is the place to look.

## Files changed

Mine, this round:

- `app/build.gradle.kts` — `syncGuestOverlay` + the assets srcDir and the dependency wiring.
- `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt` — `LaunchDecision` at the three entry points, `bootstrapFailed` removed, `startBootstrap`, stop-previous-session, `xServerStarted`, `startXServer` extraction, guest-overlay sync, H12 failure branch, session-ended screen, `Omdroid Error` clipboard label.
- `app/src/main/kotlin/com/phoshdroid/app/launch/LaunchDecision.kt` — new.
- `app/src/main/kotlin/com/phoshdroid/app/launch/GuestOverlay.kt` — new (pure `sync` + `GuestOverlayAssets` edge).
- `app/src/main/kotlin/com/phoshdroid/app/launch/SessionPrep.kt` — `prepare(keepXServer)`.
- `app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt` — `finishSession`, `runProcess`, retry rule, `lastExitCode`/`lastExitAtMs`.
- `app/src/main/kotlin/com/phoshdroid/app/proot/SessionExit.kt` — new.
- `app/src/main/kotlin/com/phoshdroid/app/PhoshdroidApp.kt` — channel name + description.
- `app/src/main/res/layout/activity_launcher.xml` — `startAgainButton`.
- `app/src/main/res/values/strings.xml` — four new strings.
- `app/src/test/kotlin/com/phoshdroid/app/launch/LaunchDecisionTest.kt`, `GuestOverlayTest.kt`, `SessionPrepTest.kt`, `app/src/test/kotlin/com/phoshdroid/app/proot/SessionExitTest.kt`.
- `docs/omarchy/r3-host-report.md` — this file.

