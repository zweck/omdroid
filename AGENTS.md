# Omdroid (`proot-post`)

Single Android APK (`com.phoshdroid.app`) that boots Omarchy -- Hyprland plus the Quickshell-based Omarchy shell on Arch Linux ARM -- in a proot guest on unrooted Android 12+, through an in-process Termux:X11 (Xlorie). The chain in one line: Android surface -> `com.termux.x11.MainActivity` (X server, `DISPLAY=:0`) -> cage (wlroots, x11 backend, pixman) -> Hyprland -> Omarchy shell, with no systemd anywhere. Repo dir is `proot-post`; product name is Omdroid.

## Layout

- `app/` — our Kotlin, namespace `com.phoshdroid.app`. `LauncherActivity` owns the launch; `PhoshdroidApp` creates the notification channel; `DisplayShiftMonitor` records display changes; `SettingsFragment` holds the settings screen.
- `app/src/main/kotlin/com/phoshdroid/app/launch/` — the launch logic, one file per decision:
  - `LaunchDecision.kt` — the single WAIT/FORWARD/START rule behind `onCreate`, `onNewIntent` and `onResume`.
  - `LaunchReadiness.kt` — when to hand over: ready marker, 25 s fallback, 120 s cap, dead session.
  - `SessionPrep.kt` — wipes the guest runtime dir and stale sockets before a session.
  - `DisplayGeometry.kt` — predicts the X root and the extra-key bar's bottom gap from the window.
  - `UiScale.kt` — resolves the integer scale from `auto`, `1` or `2`.
  - `GuestOverlay.kt` — copies the APK's guest scripts into the extracted guest when the bytes differ.
- `app/src/main/kotlin/com/phoshdroid/app/proot/` — `ProotCommandBuilder.kt` (proot args and env), `ProotService.kt` (session process, notification, exit reporting), `SessionExit.kt` (the retry rule), `ProotDistroManager.kt`.
- `app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt` — untars a gzipped asset stream into the prefix and writes `.extraction_complete`; the launcher opens `assets/bootstrap.bin` and `assets/rootfs.bin` and passes them in.
- `app/src/main/kotlin/com/phoshdroid/app/settings/` — `PhoshdroidPreferences.kt`, `ProotConfigWriter.kt`.
- `termux-x11/` — submodule `zweck/termux-x11` on the `phoshdroid-integration` branch (Gradle `:termux-x11-app`), the X server.
- `termux-app/` — submodule `zweck/termux-app` on the `phoshdroid-integration` branch (`:terminal-emulator`, `:terminal-view`, `:termux-shared`).
- `rootfs/` — the current scripts are `package-omarchy-rootfs.sh` (guest image: pinned upstream bundle, overlay, cage/wlroots/libliftoff, gzipped tar) and `build-bootstrap.sh` (Termux prefix + `jniLibs`, via `fetch-termux-packages.py`). `build-rootfs.sh` and `package-rootfs.sh` are pmOS-era and not part of this build.
- `rootfs/overlay/etc/phoshdroid/` — the files the packager installs and the APK carries:
  - `start.sh` — session entry: env, plugin index, resize helper, watcher, `exec cage -- session-child.sh`.
  - `session-child.sh` — bounded wait for cage's geometry, then exec Hyprland.
  - `fit-x11.py` — keeps cage's window equal to the X root, each side rounded down to even.
  - `omarchy-watch.py` — writes the ready marker, then keeps the shell layers in step with the monitor.
  - `omarchy-plugin-index.sh` — builds the plugin index Quickshell reads under proot.
  - `fake-proc/` — the `/proc` files bind-mounted over the guest's.
  - `bwrap-shim.sh` and `config.defaults` are Phosh relics; the packager does not install them.
- `docs/omarchy/` — `launch-contract.md` (the host/guest interface), `ARCHITECTURE.md`, the round reports (`r2-host`, `r2-guest`, `r3-host`, `r3-device`), `device-bringup-report.md`, `payload-findings.md`, and `tools/` (adb helpers, `build-chain.sh`, `install.sh`).
- `docs/brand/` — the logo generator, the SVGs, the contact sheets and the staged Android icon resources.

## Dev environment

Java 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0. `compileSdk`/`targetSdk` 35, `minSdk` 31, NDK `27.2.12479018`. Clone with submodules:

```bash
git clone --recursive https://github.com/zweck/omdroid.git
git config core.hooksPath .githooks   # pre-push runs unit tests
```

`local.properties` is gitignored (SDK path). `gradle.properties` sets `-Xmx32g` so asset packaging does not OOM.

## Build & test

CI (`.github/workflows/ci.yml`) is the gate — checkout `--recursive`, Temurin 17:

```bash
./gradlew :app:lintDebug
./gradlew :app:test
```

`docs/omarchy/tools/build-chain.sh` is the local build: repack unless `--no-repack`, verify the overlay inside the image, delete the old APK, `:app:assembleDebug`, `:app:test`; log `/tmp/omarchy-build-chain.log`. By hand:

```bash
./rootfs/package-omarchy-rootfs.sh   # guest image -> app/src/main/assets/rootfs.bin
./rootfs/build-bootstrap.sh          # Termux prefix -> jniLibs + assets/bootstrap.bin
./gradlew :app:assembleDebug
docs/omarchy/tools/install.sh        # sideload; HyperOS refuses adb install
```

Bump `versionCode` + `versionName` together in `app/build.gradle.kts`; tags look like `0.0.13-alpha`.

## Conventions

- Conventional commits: `feat(scope):`, `fix(scope):`, `perf(…)`, `build:`, `chore(scope):`, `release: 0.0.N-alpha`. Scopes in use: `rootfs`, `launcher`, `proot`.
- Own code lives under `app/src/main/kotlin/com/phoshdroid/app/`. JUnit 5 tests sit in the same package; method names are backtick sentences (`writes default config`).
- Lint only our app (`:app:lintDebug`). `abortOnError = false`; do not lint Termux submodule sources. Root `build.gradle.kts` forces all Android subprojects to compileSdk 35 — do not fight the Groovy Termux files.
- Keep `android.nonTransitiveRClass=false` (Termux uses transitive `R.style.*`). Keep the guava `listenablefuture` → `9999.0-empty-to-avoid-conflict-with-guava` pin.
- `docs/omarchy/launch-contract.md` is the host/guest interface: neither side changes it alone, and a change that alters it updates it in the same round.

## Pitfalls

- Submodules are required. CI checks them out recursively; a bare clone will not compile.
- Do not hand-edit `app/src/main/assets/{rootfs,bootstrap}.bin` (gitignored, generated). Overlay edits live in `rootfs/overlay/` and only reach an install when the image is repacked.
- **The X root is the size authority, and odd sizes are normal** (3200x1971 on the tablet). The host passes a prediction as a hint; the guest ignores it and derives everything from the root, rounding each side down to even for cage's window.
- `<prefix>/tmp/omarchy-runtime/` (the guest's `XDG_RUNTIME_DIR`) must be wiped before a session. A stale `omarchy-plugin-scan.txt` makes the shell load the previous session's plugins.
- A live size change (rotation, fold, extra-key bar) recovers by restarting only the shell — the bar and wallpaper blink for 2-3 s. Do not restart the session for a size change.
- Delete the output APK before assembling after an asset change; the package step otherwise appends to it and doubles the size. Run gradle in the foreground — an assemble takes minutes.
- The device tools in `docs/omarchy/tools/` need `TABLET_SERIAL` (the adb serial) and refuse to run without it, so a second attached device is never picked by accident.
- `adb install` is refused on HyperOS/MIUI (`INSTALL_FAILED_USER_RESTRICTED`); `docs/omarchy/tools/install.sh` drives the on-device installer and verifies the installed sha256.
- Guest scripts travel inside the APK and are copied into the guest on every start (round 3, H13). The `syncGuestOverlay` list in `app/build.gradle.kts`, the packager's "Applying overlay" block and `rootfs/overlay/etc/phoshdroid/` must name the same files, or a fresh install and an updated install run different guests.
- In-process Termux:X11: `conn_fd` is split into `server_conn_fd` / `client_conn_fd` on purpose. Merging them again makes the desktop render into a void. Touch path: do not re-enable `moveCursorToScreenPoint` on Direct-Touch, and keep identical `XI_TouchUpdate` dedup (zero-motion dupes kill swipe-unlock).
- `start.sh` quoting is fragile — an apostrophe in a comment has already broken the script. Prefer no single quotes in comments inside that file.
- `LauncherActivity` is `singleTask`; the `bootstrapCompleted` / `bootstrapInProgress` flags must stay process-scoped or a second instance races the X server.
- CI does not assemble the APK (asset too large). A green `:app:test` is not a bootable image.
