# Hyprland dialog compatibility utilities

These are the ARM64 `hyprland-dialog`, `hyprland-update-screen` and
`hyprland-donate-screen` binaries from upstream **hyprland-qtutils v0.1.5**,
the Qt predecessor of hyprland-guiutils. They provide the commands used by the
pinned Hyprland session. This is not the newer hyprland-guiutils package.

The wrappers select Qt software rendering and the bundled Fusion controls style.
The newer hyprtoolkit implementation fails in this nested Android guest: it
requests `zwp_linux_dmabuf_v1` v4 when v3 is advertised, then requires a DRM/GBM
device. The Qt utilities render without either requirement. No warning is
suppressed and no base compositor library is replaced.

Source: https://github.com/hyprwm/hyprland-qtutils/tree/v0.1.5

Source archive SHA-256:
`c02e47c9f720c20a5adca1baebbcfee0db5d3d36ce9595779563a2e5d27bafca`.
BSD-3-Clause; upstream license is included in `LICENSE`.

Rebuild with `rootfs/build-guiutils.sh` using the pinned guest rootfs, host Clang,
CMake, Ninja, Qt 6 tools, lld and llvm-strip. The script pins and checksums its
inputs, uses guest headers/libraries, removes build paths, and strips binaries.
Copy its three `out-qt/` binaries to `libexec/` after a device smoke test.

Build-only changes: explicitly find `WaylandClientPrivate` for Qt 6.11, and
disable QML ahead-of-time caching so host Qt bytecode is not embedded for a
different guest Qt version. QML remains embedded as source and loads in the
guest runtime. Guest libraries: Qt 6.11.2, hyprutils 0.14.1, glibc 2.43.

Device validation: a visible Wayland dialog, button label returned on stdout,
and clean process exit. Checksums of the shipped binaries are in `SHA256SUMS`.
