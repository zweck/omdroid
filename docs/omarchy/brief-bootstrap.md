# Brief: build the Omarchy bootstrap prefix (weston + closure)

Goal: extend this app's Termux bootstrap asset so the host side can run
`weston` (the nested Wayland parent) from the app prefix, with a complete
library closure. Read `docs/omarchy/CONTRACT.md` first; it defines the exact
weston command and module path.

## Facts

- Current asset: `app/src/main/assets/bootstrap.bin` (gzipped tar, Termux
  bootstrap, no wayland/weston libraries).
- Rebuild script: `rootfs/build-bootstrap.sh` (downloads the upstream Termux
  bootstrap zip, fixes symlinks, splits native binaries into jniLibs).
- Termux repos (aarch64):
  - `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`
  - `https://packages.termux.dev/apt/termux-x11/dists/x11/main/binary-aarch64/Packages.gz`
  - package URLs are `https://packages.termux.dev/apt/termux-x11/pool/...` exactly
    as the `Filename:` field says.
- `weston` (x11 repo) is `16.0.2`; its declared Depends are:
  `freerdp, libaml, libandroid-shmem, libcairo, libdisplay-info, libevdev,
  libglvnd, libneatvnc, libseat, libwayland, libwebp, libxcb, libxcursor,
  libxkbcommon, littlecms, pango, xcb-util-cursor` — those in turn have their
  own deps (resolve recursively).
- `.deb` layout: `data.tar.xz` whose members are under
  `./data/data/com.termux/files/usr/...`; strip that prefix when extracting into
  the app prefix. Preserve symlinks and modes.

## Work

1. Add `rootfs/fetch-termux-packages.py` (stdlib only): given a list of package
   names, resolve the transitive `Depends` closure across both indexes, pick the
   highest version, handle `a | b` alternatives by taking the first name, ignore
   version constraints, download each `.deb` into a cache dir, verify its
   `SHA256` field, and extract `data.tar.xz` into `--prefix` with the
   `./data/data/com.termux/files/usr/` prefix stripped.
   Add `--index` overrides and `--cache`.
2. Wire it into `rootfs/build-bootstrap.sh` after the upstream bootstrap is
   unpacked, installing exactly `weston` (plus closure) into the staged prefix.
   Keep the existing jniLibs split behaviour for bash/proot unchanged. Write a
   NEW asset `app/src/main/assets/bootstrap-omarchy.bin` (gzipped tar); do not
   overwrite `bootstrap.bin`.
3. Verify, on this x86_64 host (static checks only — the binaries are bionic and
   cannot run here):
   - `bin/weston`, `lib/libweston-16.so`, `lib/libweston-16/x11-backend.so` exist;
   - the weston **binary** is also copied to
     `app/src/main/jniLibs/arm64-v8a/libweston-bin.so` (chmod 755): Android
     refuses exec from app data (W^X), so the app runs it from nativeLibraryDir.
     Keep the prefix copy as well; it is harmless.
   - `readelf -d lib/libweston-16/x11-backend.so` NEEDED includes
     `libweston-16.so` and `libandroid-shmem.so`;
   - for every ELF in `bin/weston` and `lib/*.so*`, every NEEDED entry is either
     present somewhere in the staged prefix or in this Android system allowlist:
     `libc.so libdl.so liblog.so libm.so libandroid.so libz.so libstdc++.so
     libEGL.so libGLESv2.so libvulkan.so libOpenSLES.so libmediandk.so
     libjnigraphics.so libc++_shared.so`.
     Print any unresolved NEEDED entry — the list must be empty.
   - `du -sh` prefix and the new asset; member count `tar -tzf`.
4. Report in `docs/omarchy/bootstrap-findings.md`: the closure list (names +
   versions + bytes), unresolved list, asset size, and the exact weston
   invocation used to prove the module path resolves.

## Rules

- Do not modify `app/src/main/kotlin/**`, `app/build.gradle.kts`, existing
  assets, or `docs/omarchy/{ARCHITECTURE,CONTRACT,payload-findings}.md`.
- Do not commit. Do not run gradle. Do not touch the phone.
- If a package is missing from both indexes, STOP and report it rather than
  substituting an older weston.
- Report PASS/FAIL per check with the command output.
