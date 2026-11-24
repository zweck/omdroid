# Brief R3-HOST: one launch decision, a truthful session lifecycle, guest scripts that follow the APK

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`. You have no device and you build no APK.

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`, read `AGENTS.md`).
Background (read once, do not edit): `docs/omarchy/launch-contract.md`,
`docs/omarchy/r2-host-report.md`, `docs/omarchy/r2-guest-report.md`.
Your report, created in your FIRST calls and updated after EVERY item:
`docs/omarchy/r3-host-report.md` (start it with a STATUS TABLE: one row per item H10-H15,
`PENDING` until the item is proven, then `PASS`/`FAIL` plus the proof).

## Why

Phil asked for an app that launches predictably. Round 2 made the cold start right. This round
closes the remaining holes, all found by reading the tree at the end of round 2:

1. **Tapping the icon after the session ended shows a dead display.** `bootstrapCompleted` is a
   per-process flag that is never cleared. After the notification's Stop action, a guest exit or
   a guest crash, the process is still alive, so `LauncherActivity.onCreate` logs
   `already bootstrapped, forwarding to MainActivity` and hands over to an X display that nothing
   draws on.
2. **`ProotService.isRunning()` lies.** `active` is set `true` in `start()`/`startProot()` and
   set `false` only in `stopSession()` and in the "retry exited non-zero" branch. When the guest
   exits with code 0 (the user logs out of Hyprland), or the retry exits 0, the monitor thread
   just ends: `active` stays `true`, the "Omarchy desktop is running" notification stays, and
   nothing is running.
3. **A launch that times out or whose session died still hands over** (`desktop not ready
   (...); handing over anyway`), to a blank display.
4. **A new APK never updates the guest scripts.** The rootfs is extracted only when
   `.extraction_complete` is missing, so a fix to `rootfs/overlay/etc/phoshdroid/*` reaches an
   existing install only if the user wipes 4 GB of app data. This already cost a day (the
   installed guest predated a fix that the repo had).

## Hard rules

- You own `app/**` and `docs/omarchy/r3-host-report.md`. Touch nothing else: not `rootfs/`, not
  `docs/omarchy/tools/`, not `termux-x11/`, not `termux-app/`, not the contract, not other reports.
  If an item cannot be done without one of those, STOP that item, say so in the report with the
  exact change needed, and go on with the next item.
- No git command that changes anything (no add, commit, stash, checkout, restore, clean).
- Never run `assembleDebug`/`assembleRelease` (the APK is 1.7 GB; the parent builds it). Your gate
  is: `./gradlew :app:compileDebugKotlin :app:test -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" --console=plain`
  after EVERY item. An item is not done while that is red. Never start the next item on a red
  tree, and never leave the tree red at the end of a turn: if you are running out of calls,
  finish or back out the item in hand, then write the report.
- Do not rename packages, classes, the log tag `Phoshdroid` or the `phoshdroid_*` intent extras
  (device tools grep for them). Do not touch `RESTART_SESSION_ON_DISPLAY_SHIFT` or the
  display-shift code. Keep `conn_fd`/touch code in the submodules alone.
- Tests: JUnit 5, same package as the class, backtick-sentence names, pure logic only (no
  Robolectric, no Android classes in the tested function). If a piece cannot be tested without
  Android classes, extract the decision into a pure function and test that; do not skip silently.
- Kotlin style: match the file you are in. Comments say why, not what.
- If `patch`/`write_file` answers "Edit approval denied", write files with a terminal heredoc
  (`python3 - <<'EOF'`) for the rest of the run and say so in the report.

## Facts you can rely on (read from the tree; line numbers drift, search for the text)

- `LauncherActivity.kt`: `onCreate` forwards when `bootstrapCompleted && !bootstrapFailed`, waits
  when `bootstrapInProgress`, else runs `bootstrap()` in `lifecycleScope`. `onNewIntent` and
  `onResume` forward on the same condition. `forwardToMainActivity()` starts
  `com.termux.x11.MainActivity` and `finish()`es the launcher, so after a hand-over no
  `LauncherActivity` instance exists; the next icon tap goes through `onCreate`.
- `bootstrap()` (suspend, returns Boolean): extraction checks, then `SessionPrep(tmpDir).prepare()`,
  then the X server block (`Class.forName("com.termux.x11.CmdEntryPoint")`, a thread named
  `X11Server`, then a wait for `tmp/.X11-unix/X0`), then `ProotService.start(...)`, then
  `waitForDesktopReady(...)` (pure rule in `launch/LaunchReadiness.kt`), then the hand-over.
- The X server lives in the app process and is started at most once per process. Nothing guards
  a second start today.
- `SessionPrep.prepare()` removes `tmp/omarchy-runtime` and a list of stale entries that
  includes `.X11-unix/X0` and `.X0-lock`.
- `ProotService`: `ACTION_STOP` -> `stopSession()`, `stopForeground`, `stopSelf`. The monitor
  thread (`ProotMonitor`) waits for proot, writes `tmp/proot-exit.log`, retries once on a
  non-zero exit.
- `showError(message, copyable)` shows `errorText` on the splash layout
  (`res/layout/activity_launcher.xml`).
- Both `LauncherActivity` and `com.termux.x11.MainActivity` are `singleTask` in one task.
- Guest logs on the host side: `<prefix>/tmp/omarchy-session.log`, `<prefix>/tmp/proot-exit.log`
  (`<prefix>` = `filesDir/usr`).
- Guest scripts the packager installs (`rootfs/package-omarchy-rootfs.sh`, block "Applying
  overlay"): `start.sh`, `session-child.sh`, `omarchy-plugin-index.sh`, `fit-x11.py`,
  `omarchy-watch.py` (all mode 755 except `fit-x11.py`, which is run through `python3`) and the
  directory `fake-proc/`, all from `rootfs/overlay/etc/phoshdroid/` to guest `/etc/phoshdroid/`.
  Guest rootfs on the device: `<prefix>/var/lib/proot-distro/installed-rootfs/omarchy`.

Verify each fact you build on before you edit (one grep each). If one is wrong, say so at the top
of the report and work from what the code really does.

## Items, in this order

### H10. One launch decision
New pure object `launch/LaunchDecision.kt`:

```kotlin
enum class LaunchAction { WAIT, FORWARD, START }
object LaunchDecision {
    fun decide(bootstrapInProgress: Boolean, bootstrapCompleted: Boolean, sessionRunning: Boolean): LaunchAction
}
```

Rule: in progress -> `WAIT`. Completed AND session running -> `FORWARD`. Everything else ->
`START` (fresh process, session stopped, session exited, session crashed, earlier bootstrap
failed). Tests for every row of that table (at least 6 cases).

Use it at the three entry points:
- `onCreate` and `onNewIntent` (the user asked for the desktop): `FORWARD` forwards, `WAIT` shows
  the splash and waits, `START` starts a bootstrap.
- `onResume`: only `FORWARD` acts (this is the deferred hand-over). `START` does nothing here,
  otherwise an error screen would restart the session every time it is resumed.
`bootstrapFailed` then has no reader left: remove it. Keep `restartInFlight` as it is.

Make `bootstrap()` safe to run more than once in one process:
- a process-wide flag `xServerStarted`, set when the `X11Server` thread is started. On a later
  run skip the whole X server block (no `Os.setenv` of `XLORIE_*`, no `CmdEntryPoint`), but still
  wait for the socket file and still log `x11-socket exists=...`;
- `SessionPrep.prepare(keepXServer: Boolean = false)`: with `true` it must NOT remove
  `.X11-unix/X0` and `.X0-lock` (they belong to the live X server of this process). Pass
  `xServerStarted`. Test both modes;
- if `ProotService.isRunning()` is true when a `START` begins, stop the service first and wait
  (bounded, 5 s, off the main thread) until `isRunning()` is false; log
  `launch: stopped previous session`;
- log `launch: decision <action>` at each entry point and `launch: restart-in-process` when the X
  server block is skipped.

Proof: the tests; and in the report the exact sequence of `launch:` lines you expect for
(a) a cold start, (b) an icon tap while running, (c) an icon tap after Stop.

### H11. The service tells the truth about the session
In `ProotService`:
- when the guest has finally exited (exit 0 with no retry, or the retry exited with ANY code),
  and it was not a Stop: set `active = false`, record the exit (`lastExitCode`, and
  `lastExitAtMs`), `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`. Do this on the main
  thread (`Handler(Looper.getMainLooper())`), not on the monitor thread;
- retry only when the first run lasted less than 30 s (a session that crashes after an hour must
  not be silently restarted on stale state). Put that rule in a pure function
  (`shouldRetry(exitCode: Int, ranForMs: Long): Boolean`) and test it;
- after such an exit, tell the user: start `LauncherActivity` with
  `FLAG_ACTIVITY_NEW_TASK` and the extras `session_ended=true`, `session_exit_code=<int>`. Wrap it
  in try/catch: Android refuses it when the app is in the background, and that is fine (the next
  icon tap takes the `START` path). Log `launch: session ended exit=<n> ui=<shown|blocked>`.
- `LauncherActivity`, when it receives that extra (in `onCreate` or `onNewIntent`): do NOT start
  a session. Show: `The desktop session ended.` for exit code 0, else
  `The desktop session stopped unexpectedly (exit <n>).`, then the last 20 lines of
  `tmp/omarchy-session.log` (copyable, as `showError` already does), and a `Start again` button.
  The button and a later plain icon tap both run the `START` path. Add the button to
  `activity_launcher.xml` (hidden by default) and put all new texts in `strings.xml`.
- The Stop action keeps its meaning (stop everything, no screen). After it the process stays
  alive and the next icon tap must take the `START` path: prove it from the code path, in the
  report, line by line.

Do not finish or recreate `com.termux.x11.MainActivity` anywhere: re-creating it is not verified
on a device, and `forwardToMainActivity()` already brings the existing instance forward.

### H12. A launch that is not ready ends in an error, not in a blank display
In `bootstrap()`, when `waitForDesktopReady` returns `TIMEOUT` or `SESSION_DIED`: stop the session
(`ProotService.stop`), show the same error view as H11 (`The desktop did not start (<reason>).`,
log tail, `Start again`), log `launch: failed <reason>`, return `false`. `MARKER` and `FALLBACK`
hand over as today. Remove the `handing over anyway` branch.

### H13. Guest scripts travel with the APK
Goal: before every session start the files under guest `/etc/phoshdroid/` that the packager
installs are byte-identical to the ones this APK was built from.
- Gradle (`app/build.gradle.kts`): a `Copy`/`Sync` task that copies
  `start.sh`, `session-child.sh`, `omarchy-plugin-index.sh`, `fit-x11.py`, `omarchy-watch.py` and
  `fake-proc/**` from `../rootfs/overlay/etc/phoshdroid/` into
  `build/generated/guestOverlay/guest-overlay/etc/phoshdroid/`, registered as an extra assets
  source dir and wired so it runs before the assets are merged (for every variant). Add a comment
  there and name in the report that this list must match the packager's "Applying overlay" block.
  Prove the wiring without assembling:
  `./gradlew :app:mergeDebugAssets --console=plain` then
  `find app/build -path '*guest-overlay*' -type f | sort` must list the files under the merged
  assets output (paste the listing). `mergeDebugAssets` also copies the two big `.bin` assets
  (1.7 GB), so give it a 600 s timeout; if it runs longer than that, stop waiting and report.
- Kotlin: `launch/GuestOverlay.kt`, pure core + thin Android edge:
  `fun sync(files: Map<String, ByteArray>, guestRoot: File): List<String>` writes each relative
  path (`etc/phoshdroid/start.sh` ...) under `guestRoot` when the content differs or the file is
  missing (write a temp file in the same directory, then rename), sets owner rwx + group/other
  r-x on every `.sh` and `.py` and on `start.sh` in any case, creates parent directories, never
  deletes anything, refuses any path containing `..`, and returns the list of paths it changed.
  The edge reads the `guest-overlay/` asset tree recursively through `AssetManager`.
  Tests: writes a missing file; leaves an identical file alone (returns empty list, mtime
  unchanged); replaces a different file; sets the executable bit; rejects `..`.
- Call it in `bootstrap()` after the rootfs extraction check and before `SessionPrep`, on
  `Dispatchers.IO`, only when the rootfs is extracted. Log
  `launch: guest-overlay files=<n> changed=<m>` and one `launch: guest-overlay updated <path>`
  line per changed file. A failure here must not stop the launch: log it and go on.

### H14. Names the user sees
Product name is Omdroid (the package id stays `com.phoshdroid.app`). Change only user-visible
text: the notification channel name `Phoshdroid Service` (in `PhoshdroidApp.kt`), the clipboard
label `Phoshdroid Error`, and any other string a user can read in `app/src/main` (search
`Phoshdroid` in `.kt` and `.xml`; list every hit in the report as changed or left, with the
reason). `strings.xml` already says `Omdroid`; do not touch `app_name` or the icon files.

### H15. Gate and report
`./gradlew :app:test :app:lintDebug -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" --console=plain`:
paste `BUILD SUCCESSFUL`, the unit-test count (`python3 docs/omarchy/tools/unit-count.py`) and
any NEW lint warning. `git status --short app` and `git diff --stat` in the report.

## Report, final sections

- STATUS TABLE (H10-H15) with the proof per row.
- "Device checks for the parent": for each of H10-H13 the exact thing to do on the tablet and
  the exact log line or screen that proves it (the device agent will follow this list literally).
- "Risks": anything you changed that cannot be proven without a device.
- Files changed.

Final reply, at most 12 lines: one line per item with PASS/FAIL and its decisive fact, the test
count, the report path, and anything you could not do.
