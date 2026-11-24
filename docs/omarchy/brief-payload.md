# Brief: acquire and audit the Omarchy guest payload

You are a mechanical payload auditor. Work only with downloaded artefacts and
new files. Do not modify any existing repo file, do not run gradle, do not
commit, do not install anything on any phone.

## Facts already established (do not re-litigate)

- Upstream: `BlackFireAlex/omarchy-android` v0.1.1, MIT. Release lock at
  `https://raw.githubusercontent.com/BlackFireAlex/omarchy-android/main/manifest/release.lock`.
- Bundle: `omarchy-android-aarch64-0.1.1.bundle.tar`,
  sha256 `7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2`.
- Host artefacts: `omarchy-android-host-aarch64-0.1.0.tar.xz`,
  sha256 `9dfabd5bb8bfcae7705aa90d5bad28414a12bf9e9b39337101f3ed92d4037fe4`
  (from `manifest/host-artifacts.lock`).
- Target host app expects a gzipped tar renamed `*.bin` (see
  `app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt`), and the
  existing guest asset is `app/src/main/assets/rootfs.bin` (679 MB, pmOS).

## Work

Work in `~/omarchy-payload/` (create it; do not write large files into the repo).

1. Download both artefacts, verify their sha256 exactly as above, print both
   hashes. Unpack the bundle and run its `SHA256SUMS` verification.
2. Inventory the bundle: every top-level member with size; `rootfs.tar.xz` size;
   the guest's account list (`/etc/passwd`) and uid/gid of the desktop user.
3. Prove or disprove, from the tar listing only (no root needed), that these
   exist in the guest and report their sizes:
   - `/opt/omarchy-android/hyprland/bin/Hyprland`
   - `/opt/omarchy-android/aquamarine/lib` (list its .so files)
   - `/opt/omarchy-android/mesa/root/usr/lib/dri` (kgsl_dri.so, swrast/llvmpipe,
     virpipe) and `/opt/omarchy-android/mesa/freedreno_icd.json`
   - `/usr/share/omarchy` (count `omarchy-*` commands in `/usr/bin`, report 5
     examples) and `/usr/lib/systemd/user/*.service` (list them)
   - `/home/omarchy/.config/hypr/hyprland.lua` and `~/.config/omarchy/*`
   - pacman database presence (`/var/lib/pacman/local` entry count)
4. Repack the guest rootfs to a gzipped tar at
   `~/omarchy-payload/rootfs-omarchy.tar.gz` (`tar -xJf ... | gzip`; you do not
   need to preserve original uid/gid). Report compressed and uncompressed size,
   wall-clock time, and peak disk used. Then confirm with
   `tar -tzf ... | wc -l` that it lists the same member count as the xz source.
5. Audit the host artefact: unpack to `~/omarchy-payload/host/`, list its tree,
   and report for each ELF (`file`, `readelf -d | grep -E 'RPATH|RUNPATH'`, and
   any hardcoded `/data/data/com.termux` strings in `weston` and its modules):
   whether it can run from a prefix other than Termux's, which weston modules
   are present (esp. `libweston-*/x11-backend.so`), and what the helper
   binaries are (`omarchy-process-guard`, `omarchy-x11-keyboard`).
6. Note anything that would break a 4 GB guest on Android 17 (e.g. absolute
   Termux paths inside guest scripts, systemd units our session script must
   replace, references to `/data/data/com.termux` in the guest).

## Deliverable

Write `docs/omarchy/payload-findings.md` (new file, do not touch others) with:
a table of every assertion in steps 1-5 as PASS/FAIL plus the measured number,
the exact guest paths from step 3, the sizes from step 4, the relocatability
verdict from step 5, and a short "what this means for the app" section.

Reply with a 10-line summary: sha256 results, rootfs gz size, relocatability
verdict, and any FAIL rows. If a check fails, report it as FAIL with the command
output - do not work around it silently.
