# Omarchy guest payload — audit findings

Measured 2026-11-23 on this host. Work dir `~/omarchy-payload/` (nothing under it is
tracked); the only repo file written is this one. No gradle, no installs, no commits.

Artefacts audited:

| artefact | source | bytes | sha256 |
|---|---|---|---|
| `omarchy-android-aarch64-0.1.1.bundle.tar` | releases v0.1.1 | 1,181,941,760 | `7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2` |
| `omarchy-android-host-aarch64-0.1.0.tar.xz` | releases v0.1.0 | 56,920 | `9dfabd5bb8bfcae7705aa90d5bad28414a12bf9e9b39337101f3ed92d4037fe4` |

## 1. Acquisition and integrity

| # | Assertion | Verdict | Measured |
|---|---|---|---|
| 1a | bundle sha256 matches `manifest/release.lock` | **PASS** | `7e9f1cd6…5021cdb2` (byte-identical) |
| 1b | host artefact sha256 matches `manifest/host-artifacts.lock` | **PASS** | `9dfabd5b…2d4037fe4` |
| 1c | host artefact's own `SHA256SUMS` verifies | **PASS** | 6/6 `OK` (both helpers, the x11 module, 2 licences, `HOST-MANIFEST`) |
| 1d | bundle `SHA256SUMS` verifies | **PASS** | 8/8 `OK`, incl. `./rootfs.tar.xz` (`dbee00c0…0366e2c5`) |

Commands: `curl -sSL … -o <file>; sha256sum <file>; tar -xf bundle.tar -C bundle; cd bundle && sha256sum -c SHA256SUMS`.

Two lock-file notes (not defects in the payload):

- `manifest/host-artifacts.lock` has a stray trailing `"` after the sha256 field
  (`…2d4037fe4"`); the digest itself is correct and the archive matches it.
- `BUNDLE-MANIFEST` cross-checks agree with the shipped files: `packages_lock_sha256=1d2935de…`
  equals the digest of `manifest/packages-aarch64-0.1.1.lock`, and
  `host_artifact_sha256=9dfabd5b…` equals the host artefact above.

## 2. Bundle inventory

Bundle tar: 21 members total (11 directories).

| member | bytes |
|---|---|
| `./BUNDLE-MANIFEST` | 588 |
| `./SHA256SUMS` | 799 |
| `./host/bin/omarchy-process-guard` | 11,848 |
| `./host/bin/omarchy-x11-keyboard` | 8,456 |
| `./host/opt/weston/lib/libweston-14/x11-backend.so` | 156,000 |
| `./host/share/licenses/omarchy-android/LICENSE` | 1,070 |
| `./host/share/licenses/weston/COPYING` | 1,393 |
| `./manifest/packages-aarch64-0.1.1.lock` | 10,604 |
| `./rootfs.tar.xz` | 1,181,733,044 |

The bundle's `host/` tree is byte-identical to the separate host artefact (same three
digests — `49ce43ed…`, `d4b1c844…`, `8cfb78a4…` — verify in both `SHA256SUMS` files).

`rootfs.tar.xz` (`xz --robot -l`): 1,181,733,044 compressed → **4,077,096,960**
uncompressed, ratio 0.290, single CRC64 block; **83,151 members**.

Guest accounts (`/etc/passwd`, 22 entries): the desktop user is

```
omarchy:x:1000:1000:Omarchy:/home/omarchy:/bin/bash
```

and `/etc/group` has `omarchy:x:1000:`. Everything else is systemd/dbus/polkit plumbing
(`root:0`, `dbus:81`, `polkitd:102`, `systemd-*` 969-978, `nobody:65534`). `/home/omarchy`
is owned `1000/1000`; root-owned guest binaries are `0/0`.

## 3. Guest paths — proven from the tar listing only

| # | Assertion | Verdict | Measured |
|---|---|---|---|
| 3a | `/opt/omarchy-android/hyprland/bin/Hyprland` | **PASS** | 17,316,744 B, `-rwxr-xr-x 0/0` |
| 3b | `/opt/omarchy-android/aquamarine/lib` + its `.so` files | **PASS** | 1 real lib + 2 symlinks (below) |
| 3c-1 | `/opt/omarchy-android/mesa/root/usr/lib/dri/kgsl_dri.so` | **PASS** | symlink → `libdril_dri.so` |
| 3c-2 | `swrast`/`kms_swrast` in that dir | **PASS** | both symlinks → `libdril_dri.so` |
| 3c-3 | `virpipe` driver anywhere in the guest | **FAIL** | absent from both DRI dirs and from `/opt/omarchy-android/SHA256SUMS` (565 files) |
| 3c-4 | `/opt/omarchy-android/mesa/freedreno_icd.json` | **PASS** | 211 B, `library_path=/opt/omarchy-android/mesa/root/usr/lib/libvulkan_freedreno.so`, `api_version 1.4.359` |
| 3d-1 | `/usr/share/omarchy` | **PASS** | 1,962 members: 252 dirs, 1,709 files, 1 symlink; `version` file 12 B |
| 3d-2 | `omarchy-*` commands in **`/usr/bin`** | **FAIL** | `/usr/bin` has 0 `omarchy-*` of its 1,730 entries |
| 3d-3 | where the commands really are | (correction) | `/usr/local/bin`: 440 `omarchy-*` entries, symlinks into `/usr/share/omarchy/bin` (442 entries, 441 files) |
| 3e | `/usr/lib/systemd/user/*.service` | **PASS** | 86 unit files/symlinks — but all stock Arch (dbus, pipewire, wireplumber, gpg, foot-server, xdg-desktop-portal*); **no omarchy/hypr units here** |
| 3f-1 | `/home/omarchy/.config/hypr/hyprland.lua` | **PASS** | 1,513 B, `1000/1000` |
| 3f-2 | `~/.config/omarchy/*` | **PASS** | 24 members (dir + 22 files/symlinks), see below |
| 3g | `/var/lib/pacman/local` entry count | **PASS** | 556 package dirs (+`ALPM_DB_VERSION`), each with `desc`+`files`+`mtree` (556/556/556) |

Exact paths and sizes from step 3:

```
/opt/omarchy-android/hyprland/bin/Hyprland          17,316,744   patched build (see §5 evidence)
/opt/omarchy-android/hyprland/bin/hyprctl              310,544
/opt/omarchy-android/hyprland/bin/hyprpm               534,288
/opt/omarchy-android/hyprland/bin/start-hyprland        302,720   Hyprland's own watchdog launcher (glibc ELF)
/opt/omarchy-android/hyprland/bin/hyprland          symlink -> Hyprland
/opt/omarchy-android/aquamarine/lib/libaquamarine.so.0.14.0   1,661,600  SONAME libaquamarine.so.13
/opt/omarchy-android/aquamarine/lib/libaquamarine.so.13        symlink -> .so.0.14.0
/opt/omarchy-android/aquamarine/lib/libaquamarine.so           symlink -> .so.13
/opt/omarchy-android/aquamarine/lib/pkgconfig/aquamarine.pc        322
/opt/omarchy-android/mesa/root/usr/lib/dri/  (44 entries: 43 symlinks + 1 real lib)
        libdril_dri.so                144,552  (the only real file)
        kgsl_dri.so, swrast_dri.so, kms_swrast_dri.so, zink_dri.so, vkms_dri.so,
        virtio_gpu_dri.so, msm_dri.so, … -> libdril_dri.so
        NO virpipe_dri.so
/opt/omarchy-android/mesa/root/usr/lib/libgallium-26.3.0-devel.so   28,206,784
/opt/omarchy-android/mesa/root/usr/lib/libvulkan_freedreno.so       16,867,392
/opt/omarchy-android/mesa/root/usr/lib/libEGL_mesa.so.0.0.0            540,920
/opt/omarchy-android/mesa/root/usr/lib/libGLX_mesa.so.0.0.0            494,992
/opt/omarchy-android/mesa/root/usr/lib/libgbm.so.1.0.0                  75,832
/opt/omarchy-android/mesa/root/usr/lib/gbm/dri_gbm.so                  150,336
/opt/omarchy-android/mesa/freedreno_icd.json                               211
/opt/omarchy-android/mesa/root/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json  180
/opt/omarchy-android/mesa/root/usr/share/glvnd/egl_vendor.d/50_mesa.json   105
/opt/omarchy-android/SHA256SUMS                                         72,245   (565 entries)
/usr/share/omarchy/version                                                  12
/usr/local/bin/           445 entries: 440 omarchy-* (437 symlinks into /usr/share/omarchy/bin,
                          3 real scripts: omarchy-dbus-service 3,644, omarchy-voxtype-daemon 1,601,
                          omarchy-voxtype-install 890), plus omarchy, Xwayland (495), uwsm-app (435),
                          voxtype (14,575,608 — the dictation engine binary, not a shim)
/usr/share/omarchy/bin/   442 entries / 441 files, incl. omarchy (27,955), omarchy-shell (3,113),
                          omarchy-launch-shell, omarchy-agent*, omarchy-audio-*
/usr/lib/systemd/user/     86 units/symlinks (stock Arch only)
/usr/share/omarchy/default/systemd/user/  8 units + 10-oomd.conf:
        bt-agent.service, omarchy-crash-watch.service, omarchy-fcitx5.service,
        omarchy-migrate-notify.service, omarchy-recover-internal-monitor.service,
        omarchy-sleep-lock.service, omarchy-speaker-tuning.service,
        omarchy-tailscale-receive.service
/home/omarchy/.config/hypr/hyprland.lua                                1,513
/home/omarchy/.config/hypr/{monitors,input,bindings,looknfeel,autostart}.lua 1,058/196/1,429/2,044/452
/home/omarchy/.config/omarchy/shell.json                               1,194
/home/omarchy/.config/omarchy/proot-session-bus.conf                   1,261
/home/omarchy/.config/omarchy/current   symlink -> /home/omarchy/.local/state/omarchy/current
/home/omarchy/.config/omarchy/{hooks,themes,extensions,dbus-services,themed}/ …
/var/lib/pacman/sync/{core,extra,alarm,aur}.db   255,193 / 10,522,797 / 62,222 / 10,559
```

Five `omarchy-*` command examples (all in `/usr/local/bin` → `/usr/share/omarchy/bin`):
`omarchy`, `omarchy-menu`, `omarchy-shell`, `omarchy-launch-shell`, `omarchy-restart-audio`.

Two builds of the same components ship in the guest, and only the `/opt` copy carries the
Android patches (string evidence):

| component | `/opt/omarchy-android/…` (patched) | system copy (stock) |
|---|---|---|
| Hyprland | 17,316,744 — contains `Android KGSL: keeping linux-dmabuf v3 without a DRM node` | `/usr/bin/Hyprland` 15,439,648 — no KGSL string |
| aquamarine | 1,661,600 — contains `Aquamarine Android DMA-BUF allocator`, `AQ_ANDROID_DMABUF_HEAP/VULKAN/GLFINISH`, `DMAHeap: using Android system heap` | `/usr/lib/libaquamarine.so.0.14.0` 1,380,192 — neither string |
| mesa | 26.3.0-devel `libgallium` 28,206,784 + `/opt` DRI dir | 26.2.1-arch1.1 `libgallium` 53,537,304 + `/usr/lib/dri` (67 entries) |

Also absent from the guest, contrary to what the architecture note might imply:
`hyprlock`, `hypridle`, `hyprsunset` binaries (only `~/.config/hypr/hyprsunset.conf`
exists); `quickshell` **is** present (`/usr/bin/quickshell`, 7,161,848) and is what
`omarchy-launch-shell` runs; `uwsm` itself is absent, only the `uwsm-app` shim.

## 4. gzip repack

Command (the `tar -xJf … -O | gzip` form in the brief produces concatenated *file
contents*, not a tar stream — `tar -tzf` then answers "This does not look like a tar
archive". The correct one-liner recompresses the tar stream):

```bash
xz -dc bundle/rootfs.tar.xz | gzip > rootfs-omarchy.tar.gz
```

| measurement | value |
|---|---|
| compressed (gzip, default level) | **1,595,969,731 B** (1.49 GiB) |
| source xz | 1,181,733,044 B (1.10 GiB) — gz is **+35.0 %** |
| uncompressed tar stream | **4,077,096,960 B** (3.80 GiB), identical to the xz's own uncompressed size |
| wall clock | **111.2 s** (`xz -dc | gzip`, 24-core box, decompress is single-threaded) |
| peak disk in the work dir | **3,996,416,628 B** (3.72 GiB) at peak; 2,413,590,617 B before the repack, so the extra file needs ~1.59 GB |
| free space after | 65 GB (94 % used, 952 GB root) |
| `gzip -t` | OK |
| member count | `tar -tzf` = **83,151** = `tar -tJf` source = **83,151** — **PASS** |
| content spot-check | `tar -xOzf` of `./etc/passwd` and `./opt/omarchy-android/hyprland/bin/Hyprland` → sha256 identical to the `xz` source for both |

Hardlinks: 1,832 members are hardlinks (`h`), all tiny hand-linked duplicates (terminfo),
so the `-O`-style expansion a naive repack would cause is negligible here; the byte
totals above are equal on both sides.

The repacked file is at `~/omarchy-payload/rootfs-omarchy.tar.gz`. That is the right
container for this app: `AssetExtractor` wraps its input in `GZIPInputStream` +
`TarArchiveInputStream` and has **no xz support** (`extraction/AssetExtractor.kt:32`).

## 5. Host artefact

Tree (7 files, 196 KB unpacked):

```
HOST-MANIFEST, SHA256SUMS
host/bin/omarchy-process-guard                          11,848
host/bin/omarchy-x11-keyboard                            8,456
host/opt/weston/lib/libweston-14/x11-backend.so        156,000
host/share/licenses/{omarchy-android/LICENSE,weston/COPYING}
```

| file | `file` | RUNPATH | notes |
|---|---|---|---|
| `omarchy-process-guard` | ELF aarch64 PIE, interpreter `/system/bin/linker64`, **for Android 30**, NDK r29, NEEDED `libc.so`,`libdl.so` | `/data/data/com.termux/files/usr/bin/../../usr/lib` | Android process guard; `usage: %s UID LIMIT STATE_DIR LOG_FILE CONTAINER`; scans `/proc/<pid>/cmdline`+`/proc/<pid>/status`; logs `"%s safety stop: %d Termux processes reached guard limit %d"`; embeds `.virgl-omarchy`, `wayland-omarchy` |
| `omarchy-x11-keyboard` | same ABI, NEEDED `libX11.so`,`libc.so`,`libdl.so` | same Termux path | X11 XKB repeat-rate helper: `usage: %s query \| off \| on [delay-ms interval-ms]`, uses `XkbGetAutoRepeatRate`/`XAutoRepeatOff`/`XAutoRepeatOn` |
| `libweston-14/x11-backend.so` | ELF aarch64 shared object, **for Android 30**, with debug_info | `/data/data/com.termux/files/usr/bin/../../usr/lib:$ORIGIN/..` | patched weston 14 x11 backend; NEEDED `libweston-14.so`, `libandroid-shmem.so`, `libwayland-server.so`, `libpixman-1.so`, `libxkbcommon.so`, `libxcb*`, `libX11*`, `libpng16.so`; SONAME `x11-backend.so` |

Patch evidence in the module: `WESTON_X11_REFRESH_MHZ`, `x11_compositor_get_refresh_mhz`,
`x11 output refresh selected: %.3f Hz` (source path in debug info:
`omarchy-android-forks/weston/libweston/backend-x11/x11.c`).

`weston` itself is **not in the host artefact**, nor is `libweston-14.so`,
`libandroid-shmem.so` or any Wayland/X11 dependency — and neither the guest nor the host
artefact ships any of them (the guest listing has zero members named `weston*`,
`libweston*`, `libandroid-shmem.so`, `virgl*`). Only the plug-in module is provided.

**Relocatability verdict: partially — the code is relocatable, the RUNPATH is not.**
All three ELFs are normal Android/bionic binaries (`/system/bin/linker64`, Android 30 ABI),
so they run under any app's data dir; but both helpers and the module hardcode
`/data/data/com.termux/files/usr/lib` in RUNPATH, and the module additionally resolves
`$ORIGIN/..`. So as shipped they will load libraries only from the Termux prefix. Options,
in order of cheapness: (a) export `LD_LIBRARY_PATH` to our prefix lib dir when
`ProotService` spawns weston (the proot side already does this in
`ProotCommandBuilder.kt:70`); (b) `patchelf --set-rpath '$ORIGIN/..:<prefix>/lib'`; (c)
place the module at `<prefix>/lib/libweston-14/x11-backend.so` so `$ORIGIN/..` resolves the
sibling libs. Debug-info strings also contain `/data/data/com.termux/files/home/omarchy-android/.work/…`
build paths — cosmetic, not runtime.

## 6. What would break a 4 GB guest on Android 17

- **Size**: 4.08 GB uncompressed / 1.49 GB as gz. `noCompress` on `bin` keeps the APK asset
  near 1.49 GB; extraction needs ~4.1 GB free app data on top. Nothing in the listing
  suggests a 4 GB limit problem inside proot (no device nodes; the 18 files and 2 dirs
  carrying setuid/setgid bits are inert under proot, which ignores them).
- **No Termux paths in the guest**: 0 files in the extracted scripts/configs contain
  `/data/data` or the string `com.termux`; the four hits for the word "Termux" are comments
  and one echo. So the guest itself is prefix-agnostic — the Termux coupling is entirely in
  the host-side launcher (outside this bundle).
- **systemd is present but unusable**: systemd itself, stock user units and
  `/etc/systemd/user/*.wants` all ship, and **29 omarchy scripts call `systemctl` (88
  occurrences)** — top offenders `omarchy-upgrade-to-quattro` (18), `omarchy-audio-tuning`
  (16), `omarchy-restart-audio` (5), `omarchy-dns` (5). The 8 omarchy user units
  (`/usr/share/omarchy/default/systemd/user/`) never start under proot; the payload
  already compensates: `~/.config/hypr/autostart.lua` starts `omarchy-voxtype-daemon`
  instead of the unit, and `default/hypr/autostart.lua` short-circuits on
  `OMARCHY_PROOT=1` (no `systemctl --user import-environment`, no
  `dbus-update-activation-environment`, no monitor-watch/udiskie).
- **`/run/user/1000` does not exist in the guest** (the tar has no `/run` members), yet the
  shipped `proot-session-bus.conf` listens on `unix:tmpdir=/run/user/1000` and the session
  expects `XDG_RUNTIME_DIR` there. Proot must bind/create it, or dbus and the Wayland
  socket have nowhere to live.
- **`omarchy-proot-audio` is referenced but missing**: `omarchy-restart-audio` calls
  `omarchy-proot-audio status` under `OMARCHY_PROOT=1`, and no such member exists anywhere
  in the rootfs. It must come from our guest-side overlay (audio plan: host PulseAudio on
  `tcp:127.0.0.1:4715`, `PULSE_SERVER` is already exported by that script).
- **Dangling Vulkan ICD**: `/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` (guest)
  points at `/usr/lib/libvulkan_freedreno.so`, which does not exist; the real library and a
  self-consistent ICD JSON live only under `/opt/omarchy-android/mesa/`. Any app using
  default ICD discovery finds nothing.
- **Double compositor start under proot**: `hyprland.lua` replaces Omarchy's autostart
  module when `OMARCHY_PROOT=1` with `flock -n $XDG_RUNTIME_DIR/omarchy-shell-launch.lock
  omarchy-launch-shell`, i.e. they already hit "hyprland.start fires twice" under proot.
  Our session script should keep that guard rather than start the shell itself.
- **GPU modes**: the scripts default to `OMARCHY_GPU_MODE=virgl`, but **no virpipe/virgl
  guest driver exists**; the only accelerated guest paths are `kgsl_dri` (Mesa KGSL) and
  `zink`→Turnip (`libvulkan_freedreno.so` + `/opt` ICD). A kgsl→virgl→pixman fallback chain
  as planned in `ARCHITECTURE.md` cannot work as written for the virgl leg.
- **Env contract the payload expects from whoever launches it** (all read from the guest's
  own files): `OMARCHY_PATH` (default `/usr/share/omarchy`), `OMARCHY_PROOT=1`,
  `OMARCHY_OUTPUT_WIDTH`, `OMARCHY_OUTPUT_HEIGHT`, `OMARCHY_REFRESH_MHZ` (also
  `WESTON_X11_REFRESH_MHZ` on the host side), `OMARCHY_SCALE` (default **2**, feeds
  `GDK_SCALE` too), `OMARCHY_GPU_MODE`, `XDG_RUNTIME_DIR`, `PULSE_SERVER`.
  `omarchy-launch-shell` also self-detects via `uname -r` matching `*PRoot*`; our
  `ProotCommandBuilder` passes no `-k`, so setting `OMARCHY_PROOT=1` explicitly is the
  reliable route.
- **Phantom-process guard is warranted**: `default/hypr/autostart.lua` documents that
  Android 12+ may kill unrelated Termux children during the startup burst — the same
  reasoning as `omarchy-process-guard` in the host artefact.

## 7. What this means for the app

1. **Use the bundle as the guest payload, repacked to gzip** — 1.49 GiB `.bin` at
   `app/src/main/assets/rootfs.bin`, ~4.1 GB extracted. XZ support in `AssetExtractor` is
   not needed if we repack at build time (and `package-rootfs.sh` would be the place).
2. **Ship weston + libweston-14 + `libandroid-shmem.so` in the bootstrap prefix**, and drop
   the shipped `x11-backend.so` into `<prefix>/lib/libweston-14/`; set
   `LD_LIBRARY_PATH` for the host weston process, or patchelf its RUNPATH off the Termux path.
   The bundle gives us only the patched module and the two helpers, not weston.
3. **Pin the guest to the `/opt` (patched) copies** of Hyprland and aquamarine — the
   `/usr/bin` and `/usr/lib` copies are the stock Arch builds without the KGSL/DMA-BUF
   allocator patches. That means a PATH/LD_LIBRARY_PATH order the session script must
   enforce, plus the `AQ_ANDROID_DMABUF_*` knobs if we need to steer the allocator.
4. **The session script must own**: `/run/user/1000` + `XDG_RUNTIME_DIR`, the dbus session
   bus with `proot-session-bus.conf`, `OMARCHY_PROOT=1` and the output/scale env set, the
   `omarchy-proot-audio` shim (audio over `PULSE_SERVER=tcp:127.0.0.1:4715`), and the
   kgsl-only GPU mode until a virgl driver actually exists in the payload.
5. **Drop hyprlock/hypridle/hyprsunset from the M2/M3 scope**: they are not installed;
   idle/lock live in `shell.json` (`screensaver` 150 s, `lock` 300 s) inside Quickshell.

## FAIL rows (all reproduced above)

| row | what failed | evidence |
|---|---|---|
| 3c-3 | `virpipe` driver requested by the brief does not exist in the payload | 0 members named `*virpipe*`; `/opt/…/SHA256SUMS` (565 entries) has none |
| 3d-2 | `omarchy-*` commands are **not** in `/usr/bin` | `/usr/bin` 1,730 entries, 0 `omarchy-*`; they are in `/usr/local/bin` (440) |
| 5-a | host artefacts are **not** relocatable as shipped | RUNPATH `/data/data/com.termux/files/usr/lib` in all three ELFs |
| 5-b | weston and `libweston-14.so` are **not** in the host artefact (nor the guest) | 7-file tree; 0 guest members named `weston*`/`libweston*` |
| 6-a | `omarchy-proot-audio` is referenced but absent from the guest | `omarchy-restart-audio:9`, no member matches |
| 6-b | guest Vulkan ICD JSON points at a non-existent `/usr/lib/libvulkan_freedreno.so` | only `/opt/omarchy-android/mesa/root/usr/lib/libvulkan_freedreno.so` exists |
