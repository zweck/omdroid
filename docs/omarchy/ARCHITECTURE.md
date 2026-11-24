# Omarchy on Android: architecture decision

Status: proposal, 2026-11-23. Supersedes the pmOS + Phosh guest.

## Goal

Swap the guest from postmarketOS + Phosh to Arch Linux ARM + Omarchy 4 (Hyprland,
omarchy-shell/Quickshell, omarchy themes and commands), keeping this project's
Android host (single APK, embedded Termux prefix, embedded X server, proot,
display-shift restart, settings).

## Why "native" is not available

- Hyprland's backend library (aquamarine) has four backends: DRM, Wayland
  (nested), headless, null. **There is no X11 backend.** Hyprland refuses to
  start on an X11 display, so it cannot replace Phoc in the current stack.
- Android's app sandbox gives no `/dev/dri`; DRM/GBM allocation is impossible
  inside proot. Unpatched Hyprland therefore cannot start in an Arch proot
  container at all.
- Consequence: Omarchy on Android is only possible as a **nested** compositor,
  and only with patches that carry its buffers over the nested Wayland path.

## The working chain

```
Android Surface
  Termux:X11 / Xlorie (in-process X server, DISPLAY=:0)      [host, bionic]
    weston -B x11 --shell=kiosk --renderer=pixman|gl        [host, Termux prefix]
      Hyprland (aquamarine WAYLAND backend, SHM/dma-buf)    [guest, Arch ARM proot]
        omarchy-shell (Quickshell), hyprlock/hypridle/hyprsunset, apps
```

Input path is the reverse: Android touch/key → Xlorie → weston → Hyprland.
Audio: Termux PulseAudio with `module-aaudio-sink`, published on
`tcp:127.0.0.1:4715` (proot cannot pass the credential-bearing unix handshake);
guest gets `PULSE_SERVER=tcp:127.0.0.1:4715`.

## Precedent (proven, MIT)

`BlackFireAlex/omarchy-android` v0.1.1 (MIT) runs exactly this chain, rootless,
as a Termux script bundle: patched aquamarine + Hyprland (Android KGSL/SHM
present path), patched weston x11 backend (XRandR refresh timing), patched
omarchy-shell (proot compatibility), Arch Linux ARM rootfs, 555-package closure.
Validated on Adreno 840. Its release bundle is a viable first guest payload; we
own the build later.

Our device (adb `SM-F976B`): Android 17, Snapdragon 8 Elite Gen 5 ("canoe"),
`ro.hardware.egl=adreno`, Adreno 840, `/dev/kgsl-3d0` mode `crw-rw-rw-`,
`persist.sys.fflag.override.settings_enable_monitor_phantom_procs=false`
(phantom-process limit already disabled), 142 GB free. That is the reference
class for the accelerated path.

## What Omarchy loses without systemd

proot cannot run systemd (no PID 1, no cgroup delegation). Therefore:

- Hyprland is exec'd directly (`Hyprland --config ~/.config/hypr/hyprland.lua`)
  under `dbus-run-session` with a restricted session bus config.
- omarchy's systemd **user units** (quickshell/omarchy-shell, hypridle, hyprlock,
  hyprsunset, xdg-desktop-portal-hyprland) are started by our session script
  instead of `systemctl --user`.
- Any omarchy command that calls `systemctl` fails or is replaced; upstream's
  own roadmap lists "extend Android replacements for Omarchy features that
  assume systemd".

## Refactor surface (file ownership)

| Area | Change |
| --- | --- |
| `rootfs/build-rootfs.sh` | pmbootstrap → Omarchy payload builder (consume upstream bundle; own pins later) |
| `rootfs/package-rootfs.sh` | stage Omarchy rootfs + overlay; no pmOS chroot path |
| `rootfs/overlay/etc/phoshdroid/start.sh` | guest session: fake-proc/resolv prep → `dbus-run-session` → Hyprland + omarchy supervisor; no phoc/phosh, no `su` (use proot `-i 1000:1000`) |
| `app/.../proot/ProotCommandBuilder.kt` | `-i 1000:1000`, bind runtime dir (wayland socket), AQ_*/HYPRLAND_* env, drop pmroot/bwrap-shim relics |
| `app/.../proot/ProotService.kt` | also own host-side `weston` (+ optional `virgl_test_server_android`) process lifecycle and socket wait; stop both on exit |
| `app/.../LauncherActivity.kt` | readiness probe on `$XDG_RUNTIME_DIR/hypr/*` instead of the phosh log line; drop phoc.ini/phosh gsettings; keep display-shift restart and gaps |
| `app/.../settings/*` | GPU mode (kgsl/virgl), scale, refresh, audio; drop Phosh-only keys |
| `app/.../extraction/AssetExtractor.kt` | add XZ support (upstream guest ships `rootfs.tar.xz`) |
| assets | `rootfs.bin` = Omarchy guest (~1.3-1.5 GB gz), `bootstrap.bin` gains weston + helpers + Termux audio/virgl packages |
| naming | keep `com.phoshdroid.app` until the stack boots; rename is a separate, upgrade-breaking change |

## Milestones

- **M0 de-risk (device):** run the upstream Termux installer on the phone, boot
  Omarchy, record frame cadence, input latency, GPU mode actually used. Gate for
  everything else.
- **M1 payload:** verified bundle, guest repacked for the app, host weston
  relocatable into our prefix, findings recorded.
- **M2 app wiring:** builder + Kotlin + session script; `./gradlew :app:test`
  and `:app:assembleDebug` green.
- **M3 device bring-up:** GPU mode fallback chain kgsl → virgl → pixman,
  scaling/orientation/fold, audio, input edge cases.
- **M4 UX:** settings, notification, splash copy, docs, optional rename.

## Risks

- Android 17 / 8 Elite Gen 5 kernel differences from the validated Adreno 840
  reference (KGSL buffer path is the sensitive part).
- APK size ~1.6 GB (sideload only; Play is already out of scope for this size).
- Patched third-party compositor stack: we inherit their pins and must track
  upstream Hyprland changes.
- omarchy-shell under proot is the least proven layer (frequent QML child
  processes; phantom-process limit already disabled on our device).
