# Brief R3-README: bring README.md and AGENTS.md up to date (branding, demo, details)

You are the WORKER for this brief. Do the work yourself in this session: never create agents,
never call `delegate_task`. You have no device and you run no gradle, no packager, no adb.

Repo: `/home/phil/Development/personal/proot-post` (branch `omarchy-embed`). The GitHub repo is
`zweck/omdroid` and it is PUBLIC.
You own exactly two files: `README.md` and `AGENTS.md`. Touch nothing else. No git command that
changes anything. Your notes go to `~/.hermes/cache/scratch/omarchy/r3/readme-claims.md`
(create the directory), not into the repo.

## What happened since the README was written

`README.md` (167 lines) was written this afternoon by a previous agent, before the new app build
existed. Read it whole first: its structure, tone and header are good and stay. Its style rules
are in `docs/brand/brief-brand-readme.md` (sections about the README): they still apply — read
them. Since then:

- Phil chose logo **concept A**. The README header already shows it
  (`docs/brand/omdroid-logo-dark.svg` / `-light.svg`). The app icon and label ("Omdroid") are in
  the app (`app/src/main/res/drawable/ic_launcher_*.xml`, `res/values/strings.xml`).
- The host app was rewritten (round 2) and extended (round 3), the guest session scripts were
  rewritten (round 2), and the first build containing both was installed on the test tablet and
  measured. The evidence is in four reports. **They are your only source for anything the README
  says about behaviour on a device:**
  - `docs/omarchy/r3-device-report.md` — the new build on the tablet (STATUS TABLE rows D1..D9).
    It is still being written by another agent: rows marked `PENDING` have no result yet.
  - `docs/omarchy/r2-guest-report.md` — guest session: start time, ready marker, live size
    changes, the screen-size matrix (measured with the OLD app), scales.
  - `docs/omarchy/r2-host-report.md` and `docs/omarchy/r3-host-report.md` — what the app does.
    Round 3 host items (H10-H15) are unit-tested only; NOT yet run on a device.
  - `docs/omarchy/launch-contract.md` — how host and guest agree on launch and geometry.
  - `docs/omarchy/ARCHITECTURE.md`, `docs/omarchy/device-bringup-report.md` — background.
- The parent is adding three media files (they may not exist yet when you start; use these exact
  paths, do not invent others):
  - `docs/media/omdroid-demo.gif` — a short screen recording: cold start to desktop, a terminal.
  - `docs/screenshots/desktop.jpg` — the desktop right after a cold start.
  - `docs/screenshots/terminal.jpg` — a terminal window open in the session.
  The current README points at `docs/screenshots/desktop.png` and `terminal.png`: change those.

Facts about the test device, read from the device itself by the parent (`getprop`): Xiaomi Pad 8
Pro, model `25091RP04G`, Android 16 (API 36), HyperOS 3.0, SoC `SM8750P` with an Adreno GPU,
panel 3200x2136, 12 GB RAM. You may state these. Do NOT print any adb serial number, any person's
name or e-mail address, or any absolute `/home/...` path in README.md or AGENTS.md.

## R1. README.md

Keep what is right, change what is stale, add what is missing. Do not rewrite sections that are
still true. Required result:

1. **Header.** Logo as now. Directly under the one-paragraph description, the demo GIF
   (`<img src="docs/media/omdroid-demo.gif" width="800" alt="...">`, alt text that says what the
   recording shows). The two screenshots stay where the README uses them, with the `.jpg` paths.
2. **Status.** One honest section saying what works and how it was checked, in a table:
   claim | how it was measured | result | report row. Only rows whose report row says `PASS`.
   Cover at least: cold start (how many runs, seconds to desktop, no settings page, no restart),
   the geometry on the test tablet, and the guest's screen-size matrix from the r2 guest report
   (say plainly that this matrix was measured with the previous app build if the r3 device row
   D7 is still PENDING when you finish). Under the table, "Not verified yet": every r3 device row
   still PENDING, the round-3 host items (unit-tested only), and the foldable phone (never tested).
   The old text about the app opening the all-files settings page on every launch is no longer
   true for the current build (r3 device row D2): remove it or move it to history in one line.
3. **How it works.** The chain (Android surface -> in-app X server (Termux:X11 / Xlorie) -> cage
   (wlroots, X11 backend, pixman) -> Hyprland -> Omarchy's Quickshell shell, all inside a proot
   Arch guest with no systemd), then the launch sequence in 5-7 numbered steps taken from
   `launch-contract.md` section 5 and what the reports confirm (ready marker, hand-over, runtime
   dir wipe), then screen sizes: who decides the size (the X root), what happens on rotation or
   resize (the guest restarts only its shell, about 2-3 s), integer scales 1 and 2 only.
4. **Build and install.** What is needed (JDK, SDK/NDK versions from `AGENTS.md` and
   `app/build.gradle.kts`), the guest image and prefix scripts (`rootfs/package-omarchy-rootfs.sh`,
   `rootfs/build-bootstrap.sh`), the build helper `docs/omarchy/tools/build-chain.sh`, the APK size
   (about 1.7 GB) and the space the extracted guest needs (read the numbers from the reports or
   the tree; if you cannot find a number, leave it out), sideload only. HyperOS/MIUI refuses
   `adb install`; `docs/omarchy/tools/install.sh` drives the on-device installer instead — say so
   in two lines. Read each script you mention before you describe it.
5. **Developing / debugging on a device.** A short table of the tools in `docs/omarchy/tools/`
   (read each file's header comment: `probe.sh`, `coldstart.sh`, `screen.sh`, `hc.sh`, `sx.sh`,
   `gx.sh`, `gpush.sh`, `shot.sh`, `install.sh`), one line each. Mention that they pick the device
   through the `TABLET_SERIAL` environment variable.
6. **Branding.** Three or four lines: the mark (concept A: an Android head in a square frame with
   a break in the bottom edge, block wordmark), where the files are (`docs/brand/`), that
   everything is generated by `docs/brand/build_brand.py`, and the terminal version
   (`docs/brand/omdroid-logo.txt`) shown in a code block.
7. **Known limits.** Only things a file proves. Candidates to check in the reports and the tree:
   the Android package id is still `com.phoshdroid.app`; no systemd inside the guest and what
   that costs; audio (the device bring-up report mentions a missing helper); scale 1.5 is not
   accepted (r2 guest G6); a rotation blinks the bar and wallpaper for 2-3 s; the Termux prefix
   still installs an unused weston (`rootfs/build-bootstrap.sh`); sideload only.
8. **Credits and licence.** As the current README does it. Name upstream projects; state a
   licence only for a project whose licence file you have read in this tree.

Length: whatever the content needs, but no padding; the current 167 lines growing to about
200-240 is the expected range.

## R2. AGENTS.md

`AGENTS.md` is the instruction file coding agents read at the start of every session in this
repo. It is stale: it still describes the Phosh/postmarketOS product. Rewrite it for what the
tree is now, same headings as today (Layout, Dev environment, Build & test, Conventions,
Pitfalls), 80-130 lines, every statement checked against the tree:

- What the product is now (Omdroid: Omarchy/Hyprland in a proot Arch guest; package id
  unchanged), the chain in one line.
- Layout: `app/` (and its `launch/`, `proot/`, `settings/`, `extraction/` packages — list what
  each new file in `launch/` is for, one clause each), the two submodules, `rootfs/` (which
  scripts are current: read them), `rootfs/overlay/etc/phoshdroid/` (what each script does),
  `docs/omarchy/` (contract, reports, tools), `docs/brand/`.
- Build & test: the CI gate (`.github/workflows/ci.yml`), the local gate, how the APK is built
  (`docs/omarchy/tools/build-chain.sh`), how the guest image is repacked, version bump rule.
- Conventions: keep the ones that are still true (commit style, test style, lint scope, the two
  gradle pins). Add: the host/guest contract file is edited by neither side without the other.
- Pitfalls: keep the ones still true (submodules required, never hand-edit `assets/*.bin`,
  `conn_fd` split and the touch rules in termux-x11, `start.sh` quoting, `LauncherActivity`
  flags are process-scoped, CI does not assemble the APK). Drop the Phosh-only ones (bwrap shim,
  glycin) only after checking that nothing in the current tree still uses them; if something
  does, keep them. Add the ones the reports of today establish, each in one or two lines: the X
  root is the size authority and odd sizes are normal; the runtime dir must be wiped before a
  session; a live size change restarts only the shell; delete the output APK before assembling
  after an asset change (the packager otherwise doubles it); run gradle in the foreground;
  `adb install` is refused on HyperOS, use `install.sh`; guest scripts are synced from the APK on
  every start (r3 host H13), so the overlay and the Gradle `syncGuestOverlay` list must match
  the packager.

## Rules for every sentence you write

- A claim about behaviour needs a source line in a report or in the tree. Keep a list while you
  work: `claim | file:line` in your notes file. No source, no sentence.
- Never upgrade "unit-tested" to "works", and never describe a PENDING row as a result.
- Relative paths and links only; check that every path you link exists
  (`ls` it), except the three media files named above.
- No device serial numbers, no personal names or e-mail addresses, no `/home/...` paths.
- Plain prose. No emoji, no badges, no marketing words.

## Before you finish

Re-read the STATUS TABLE of `docs/omarchy/r3-device-report.md` once more (rows may have been
filled in while you worked) and bring the README's Status section in line with it.
Check: `grep -nE '<tablet-serial>|/home/|@' README.md AGENTS.md` prints nothing that is a serial, a home
path or an e-mail address.

Final reply, at most 10 lines: line counts of both files, what changed in the README per item
1-8 in a few words each, which r3 device rows were still PENDING when you finished, the notes
path, and anything you left out because no file proved it.
