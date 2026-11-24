# Bootstrap findings: weston + closure in the Termux prefix

Provenance: built from `HEAD` `abbd055` on the `omarchy-embed` branch,
2026-11-23. Scripts: `rootfs/fetch-termux-packages.py` (new),
`rootfs/build-bootstrap.sh` (extended). Full build log:
`~/.hermes/cache/scratch/omarchy-bootstrap/logs/build-final.log`.

Produced asset: `app/src/main/assets/bootstrap-omarchy.bin`
(gzipped tar, `sha256 f6cfe7779f907d0570e5b88b5f9ba1f2ee7b1ee22db6bd9f2e95efb857204105`).
`app/src/main/assets/bootstrap.bin` was not touched
(`sha256 625a63f5d37adc635e51b9c33381311fffe5a89bbdc4cffe83be480e2c7d3d39`,
mtime 2026-04-17).

## Checks

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| 1 | `bin/weston` exists in the staged prefix | PASS | `ls -l prefix/bin/weston` -> `-rwx------ 3976` |
| 2 | `lib/libweston-16.so` exists | PASS | `-rwx------ 482448` |
| 3 | `lib/libweston-16/x11-backend.so` exists | PASS | `-rwx------ 47696` |
| 4 | `x11-backend.so` NEEDED contains `libweston-16.so` | PASS | `readelf -d` row `(NEEDED) Shared library: [libweston-16.so]` |
| 5 | `x11-backend.so` NEEDED contains `libandroid-shmem.so` | PASS | `readelf -d` row `(NEEDED) Shared library: [libandroid-shmem.so]` |
| 6 | Every NEEDED of `bin/weston` + `lib/*.so*` resolves in-prefix or via the Android allowlist | PASS | `ELF files checked: 461` / `unresolved: 0` |
| 7 | `weston` present in the Termux indexes | PASS | x11 index stanza: `Package: weston`, `Version: 16.0.2` |
| 8 | Transitive closure resolvable | PASS | 104 packages, `missing: []` |
| 9 | Every downloaded `.deb` SHA256 verified | PASS | all 104 matched their `SHA256:` field (script exits on mismatch) |
| 10 | Asset written, valid gzip, expected members | PASS | `gzip -t` OK; `tar -tzf | wc -l` -> 14657; `./bin/weston`, `./lib/libweston-16.so`, `./lib/libweston-16/x11-backend.so` all present |
| 11 | `weston` executed to prove the module path resolves | NOT RUN | bionic binary: `qemu-aarch64-static: Could not open '/system/bin/linker64'` |
| 12 | CONTRACT §Host layer env is sufficient to start this payload | **FAIL** | two absolute Termux paths remain compiled in; see "Launch blockers" |

The brief's static checks (1-6) all pass; row 11 is impossible on this x86_64
host by design and row 12 is a real launch blocker found by inspecting the
binaries, not by any of the requested checks.

## Launch blockers (app-side env, found from the binaries)

Termux builds weston with its own prefix baked in, and the app's prefix is
`<filesDir>/usr`, i.e. `/data/data/com.phoshdroid.app/files/usr`, not
`/data/data/com.termux/files/usr`. Two of those baked-in paths are load-time
paths that no asset content can satisfy:

1. **`bin/weston` cannot find `libexec_weston.so`.**
   `bin/weston` is a 4 KB stub whose only real dependency is
   `libexec_weston.so`, and its search path is Termux's:

   ```
   readelf -d prefix/bin/weston
    (RUNPATH) Library runpath: [/data/data/com.termux/files/usr/lib/weston:/data/data/com.termux/files/usr/lib]
    (NEEDED)  Shared library: [libexec_weston.so]
   ```

   The library ships at `lib/weston/libexec_weston.so` (the dt-needed check
   passes because it is "somewhere in the prefix"), but
   `LD_LIBRARY_PATH=$PREFIX/lib` does not cover `lib/weston`, and the compiled
   RUNPATH points outside the app. weston therefore dies before `main()`.
2. **`--shell=kiosk` cannot find `kiosk-shell.so`.**
   The module dir is compiled in as Termux's:

   ```
   strings lib/weston/libexec_weston.so | grep /data/data
   /data/data/com.termux/files/usr/bin
   /data/data/com.termux/files/usr/bin/Xwayland
   /data/data/com.termux/files/usr/lib
   /data/data/com.termux/files/usr/libexec
   /data/data/com.termux/files/usr/lib/libweston-16
   /data/data/com.termux/files/usr/lib/weston
   ```

   `WESTON_MODULE_MAP` (present as a literal in `lib/libweston-16.so`) is the
   supported override, but the current `WestonCompositor` maps only
   `x11-backend.so`. The kiosk shell is loaded through the same module loader
   (`--shell=kiosk` -> `kiosk-shell.so`), so the shell load fails after the
   backend loads.

Required environment (this is the exact set that makes the CONTRACT command
work against this asset):

```bash
LD_LIBRARY_PATH=$PREFIX/lib:$PREFIX/lib/weston
WESTON_MODULE_MAP=kiosk-shell.so=$PREFIX/lib/weston/kiosk-shell.so;x11-backend.so=$PREFIX/lib/libweston-16/x11-backend.so
```

Notes on the map: entries are `name=path` separated by **semicolons**
(libweston's `weston_module_path_from_env`; the weston test harness and the
snap recipes use the same `;` form), and whitespace is significant. Paths are
absolute, so one `WESTON_MODULE_MAP` cannot be shared with a guest that has
different mounts.

`kiosk-shell.so` itself needs `libexec_weston.so` and `libweston-16.so`; the
widened `LD_LIBRARY_PATH` above covers both. No other hardcoded Termux path
matters for this invocation: `Xwayland` is only used with `--xwayland` (not in
the contract), and `libexec/` is not reached by the kiosk shell under the
pixman renderer.

## `bin/weston` invocation used for the static proof

The contract invocation (CONTRACT.md §Host layer) is:

```bash
DISPLAY=:0 \
XDG_RUNTIME_DIR=$PREFIX/tmp/omarchy-runtime \
LD_LIBRARY_PATH=$PREFIX/lib:$PREFIX/lib/weston \
WESTON_MODULE_MAP=kiosk-shell.so=$PREFIX/lib/weston/kiosk-shell.so;x11-backend.so=$PREFIX/lib/libweston-16/x11-backend.so \
$PREFIX/bin/weston -B x11 --renderer=pixman --shell=kiosk \
  --socket=wayland-omarchy --width=<W> --height=<H> --scale=1 \
  --idle-time=0 --log=$PREFIX/tmp/weston.log
```

It was **not executed** here: `bin/weston` is an aarch64/bionic ELF
(`interpreter /system/bin/linker64`), and this host only has
`qemu-aarch64-static`, which fails with
`Could not open '/system/bin/linker64'`. The static proof that the mapped
module path resolves is:

- the asset contains `./lib/libweston-16/x11-backend.so` (mapped path exists);
- `readelf -d` on it lists `libweston-16.so`, `libandroid-shmem.so`,
  `libwayland-server.so`, `libpixman-1.so`, `libxkbcommon.so`, `libxcb.so`,
  `libpng16.so`, `libjpeg.so.8`, `libwebp.so`, `libxcb-shm.so`, `libX11.so`,
  `libX11-xcb.so`, `libxcb-xkb.so`, `libc.so` — every one of which is either in
  `$PREFIX/lib` or in the Android system allowlist (check 6, 461 ELFs, zero
  unresolved);
- `./lib/weston/kiosk-shell.so` exists for the shell module and its NEEDED set
  resolves the same way.

## Closure

Root: `weston` (`16.0.2`, x11 index). Resolved transitively over
`termux-main` + `termux-x11`; `a | b` takes the first alternative, version
constraints are ignored, highest version across indexes wins. 104 packages,
**49,098,988 bytes** of `.deb` payload.

| package | version | .deb bytes | files written |
| --- | --- | ---: | ---: |
| `abseil-cpp` | `20260817.0` | 1,214,712 | 723 |
| `brotli` | `1.2.1` | 344,000 | 18 |
| `ca-certificates` | `1:2026.11.05-1` | 110,148 | 2 |
| `dbus` | `1.16.4` | 428,248 | 71 |
| `fontconfig` | `2.18.4` | 141,012 | 78 |
| `freerdp` | `3.33.1` | 1,966,808 | 348 |
| `freetype` | `2.15.0` | 422,056 | 61 |
| `fribidi` | `1.0.18` | 67,172 | 58 |
| `gdbm` | `1.26-1` | 150,284 | 14 |
| `giflib` | `6.1.3` | 43,832 | 24 |
| `glib` | `2.90.1` | 2,573,108 | 408 |
| `harfbuzz` | `15.2.0` | 994,440 | 64 |
| `libaml` | `1.0.0-1` | 11,816 | 4 |
| `libandroid-execinfo` | `0.1-3` | 14,964 | 4 |
| `libandroid-glob` | `0.6-3` | 7,032 | 3 |
| `libandroid-posix-semaphore` | `0.1-4` | 4,076 | 2 |
| `libandroid-shmem` | `0.7` | 7,216 | 3 |
| `libandroid-stub` | `30-3` | 27,348 | 6 |
| `libandroid-support` | `29-1` | 10,880 | 3 |
| `libbz2` | `1.0.8-8` | 26,116 | 5 |
| `libc++` | `30` | 341,604 | 2 |
| `libcairo` | `1.18.6` | 487,700 | 43 |
| `libcrypt` | `0.2-6` | 8,880 | 3 |
| `libdisplay-info` | `0.4.0` | 107,344 | 14 |
| `libdrm` | `2.4.134` | 180,604 | 41 |
| `libevdev` | `1.14.0` | 44,232 | 6 |
| `libevent` | `2.1.13` | 202,848 | 47 |
| `libexpat` | `2.11.0` | 106,144 | 14 |
| `libffi` | `3.8.1` | 35,808 | 10 |
| `libflac` | `1.5.0-1` | 202,828 | 27 |
| `libglvnd` | `1.7.0-1` | 178,340 | 29 |
| `libgmp` | `6.3.0-2` | 328,368 | 10 |
| `libgnutls` | `3.8.14` | 780,168 | 27 |
| `libgraphite` | `1.3.15` | 82,208 | 9 |
| `libiconv` | `1.19.1` | 562,252 | 19 |
| `libicu` | `79.1` | 10,312,036 | 236 |
| `libidn2` | `2.3.8-1` | 105,308 | 28 |
| `libjpeg-turbo` | `3.2.1` | 455,972 | 28 |
| `libltdl` | `2.6.2` | 17,996 | 5 |
| `liblzma` | `5.8.4` | 194,796 | 41 |
| `liblzo` | `2.10-5` | 69,560 | 23 |
| `libmp3lame` | `4.0-1` | 98,588 | 5 |
| `libmpg123` | `1.33.7` | 175,116 | 11 |
| `libneatvnc` | `1.0.4` | 57,828 | 4 |
| `libnettle` | `4.0+really3.10.2` | 405,656 | 84 |
| `libnghttp2` | `1.71.0` | 96,396 | 6 |
| `libngtcp2` | `1.26.0` | 175,116 | 14 |
| `libogg` | `1.3.6-1` | 188,636 | 90 |
| `libopus` | `1.6.1` | 191,480 | 9 |
| `libpixman` | `0.46.4-1` | 157,712 | 5 |
| `libpng` | `1.6.59` | 195,892 | 16 |
| `libseat` | `0.9.3` | 24,380 | 6 |
| `libsndfile` | `1.2.2-3` | 223,576 | 21 |
| `libsoxr` | `0.1.3-8` | 282,436 | 18 |
| `libsqlite` | `3.54.1-1` | 768,888 | 9 |
| `libtasn1` | `4.21.0` | 101,344 | 52 |
| `libtiff` | `4.7.2` | 869,912 | 385 |
| `libunbound` | `1.26.2` | 388,384 | 36 |
| `libunistring` | `1.5` | 574,056 | 50 |
| `libusb` | `1.0.30` | 59,824 | 4 |
| `libvorbis` | `1.3.7-4` | 261,740 | 147 |
| `libwayland` | `1.26.0` | 129,444 | 28 |
| `libwebp` | `1.6.0.0.0.1+really1.6.0` | 329,548 | 35 |
| `libwebrtc-audio-processing` | `2.1` | 325,772 | 31 |
| `libx11` | `1.8.14` | 1,910,512 | 1163 |
| `libxau` | `1.0.12-2` | 9,120 | 14 |
| `libxcb` | `1.17.0-1` | 913,948 | 2397 |
| `libxcursor` | `1.2.3-1` | 27,452 | 64 |
| `libxdamage` | `1.1.7` | 5,208 | 4 |
| `libxdmcp` | `1.1.5-2` | 24,460 | 5 |
| `libxext` | `1.3.7` | 98,008 | 85 |
| `libxfixes` | `6.0.2` | 11,504 | 5 |
| `libxft` | `2.3.9-1` | 47,968 | 83 |
| `libxi` | `1.8.4` | 141,860 | 82 |
| `libxinerama` | `1.1.6` | 7,704 | 10 |
| `libxkbcommon` | `1.13.2` | 253,504 | 39 |
| `libxkbfile` | `1.2.0` | 57,364 | 9 |
| `libxml2` | `2.15.4-1` | 442,652 | 54 |
| `libxrandr` | `1.5.5` | 22,264 | 19 |
| `libxrender` | `0.9.13` | 24,728 | 6 |
| `libxv` | `1.0.13-1` | 31,692 | 31 |
| `littlecms` | `2.20` | 144,508 | 5 |
| `ncurses` | `6.6.20260307+really6.5.20250830` | 557,792 | 1003 |
| `ncurses-ui-libs` | `6.6.20260307+really6.5.20250830` | 32,940 | 24 |
| `openssl` | `1:3.6.5` | 2,482,764 | 156 |
| `p11-kit` | `0.26.6` | 483,852 | 25 |
| `pango` | `1.58.2` | 364,204 | 75 |
| `pcre2` | `10.49` | 1,001,320 | 227 |
| `pulseaudio` | `17.0-5` | 848,676 | 160 |
| `python` | `3.14.10` | 4,893,180 | 970 |
| `readline` | `8.3.6` | 294,164 | 48 |
| `resolv-conf` | `1.3` | 976 | 3 |
| `speexdsp` | `1.2.1-1` | 37,332 | 9 |
| `ttf-dejavu` | `2.37-8` | 2,468,280 | 29 |
| `weston` | `16.0.2` | 909,172 | 95 |
| `xcb-util` | `0.4.1-1` | 9,036 | 10 |
| `xcb-util-cursor` | `0.1.6` | 8,816 | 4 |
| `xcb-util-image` | `0.4.1-2` | 14,656 | 6 |
| `xcb-util-renderutil` | `0.3.10-1` | 6,804 | 4 |
| `xkeyboard-config` | `2.49` | 362,516 | 304 |
| `xorg-util-macros` | `1.20.2` | 21,948 | 4 |
| `xorgproto` | `2026.1` | 249,848 | 235 |
| `zlib` | `1.4` | 63,780 | 8 |
| `zstd` | `1.5.7-1` | 360,488 | 23 |

Total: **49,098,988 bytes** across **104 packages**.

## Unresolved NEEDED entries

```
ELF files checked: 461
unresolved: 0
```

(The verifier walks `bin/weston` and `lib/*.so*`, extracts every
`(NEEDED) Shared library:` entry, and accepts a match by basename anywhere in
the prefix or one of `libc.so libdl.so liblog.so libm.so libandroid.so libz.so
libstdc++.so libEGL.so libGLESv2.so libvulkan.so libOpenSLES.so
libmediandk.so libjnigraphics.so libc++_shared.so`.)

## Sizes

| Artefact | Size |
| --- | ---: |
| staged prefix (`du -sh`) | 307M |
| `app/src/main/assets/bootstrap-omarchy.bin` | 88M (91,623,594 bytes) |
| `tar -tzf` member count | 14,657 |

Prefix composition (`du -sh` on an unpacked copy): `lib` 139M, `share` 102M,
`include` 41M, `bin` 19M, `libexec` 5.3M, `var` 1.2M, `etc` 780K. Note that
`include/` (41M) and `share/man` + `share/doc` are headers and documentation
that Termux `.deb`s install; the extractor is a faithful `data.tar.xz` unpack
as specified, so nothing was pruned. If the asset size matters for the APK,
pruning those two trees is the cheapest ~50 MB (uncompressed) and does not
affect weston.

## Non-blocking observations

- **The upstream bootstrap no longer ships `proot`/`proot-distro`.**
  `build-bootstrap.sh` prints `ERROR: bin/proot not found in bootstrap` and
  `ERROR: bin/proot-distro not found`, exactly as the old script does for a
  fresh download. Nothing in the weston closure depends on them, and the app
  launches proot from `nativeLibDir/libproot.so` (`ProotCommandBuilder`), not
  from `$PREFIX/bin/proot`, so this does not block the host layer. But the
  script's jniLibs step then prints `WARNING: proot binary not found` and
  leaves the tracked `libproot.so` as-is; a fresh clone has no
  `libproot.so` at all. Same behaviour as before this change, flagged because
  the new asset is built from a fresh upstream bootstrap.
- **The build refreshed `app/src/main/jniLibs/arm64-v8a/libbash.so`** (tracked,
  now modified in the worktree): upstream bash moved 5.3.9 -> 5.3.20. This is
  the existing script's jniLibs behaviour, but it does show up as a tracked
  binary diff; `git checkout -- app/src/main/jniLibs/arm64-v8a/libbash.so`
  reverts it if the round should be assets-only.
- **Both index `Filename:` fields are relative to their repo root.** `weston`
  is in the x11 index but its filename is `pool/main/w/weston/...`, so the
  fetch script derives the base from the index URL
  (`https://packages.termux.dev/apt/termux-x11`), not from a hardcoded
  `pool` path.
- **`bin/weston` is a stub**, not the compositor: all the interesting strings
  (module dirs, Xwayland path) live in `lib/weston/libexec_weston.so`, so any
  future prefix-patching work should target that file.

## Reproduce

```bash
./rootfs/build-bootstrap.sh
# or, packages only:
python3 rootfs/fetch-termux-packages.py --prefix /tmp/prefix weston
```
