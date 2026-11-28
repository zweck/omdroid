# R12: Chromium-family browsers launch in the session

Measured and fixed on the tablet, 2026-11-27. The trigger was Brave, but the cause is
the whole Chromium family, so the fix covers the family.

## The symptom

Launching Brave from its own entry in the session failed: no window, and the next launch showed
"Brave Browser quit unexpectedly".

## The cause, measured

A bare `brave` aborts before it draws anything:

```
FATAL:sandbox/linux/suid/client/setuid_sandbox_host.cc:172] The SUID sandbox helper binary was
found, but is not configured correctly. Rather than run without sandboxing I'm aborting now.
You need to make sure that /opt/brave-bin/chrome-sandbox is owned by root and has mode 4755.
```

Chromium's setuid sandbox wants a helper owned by root with mode 4755, and its zygote wants user
namespaces. Neither exists inside proot. The image's two helpers each fail the check for a
different reason:

| helper | mode | owner | why it fails |
| --- | --- | --- | --- |
| `/opt/brave-bin/chrome-sandbox` | `-rwsr-xr-x` | `omarchy` | the setuid bit is there, but the owner is the session user, never root |
| `/usr/lib/chromium/chrome-sandbox` | `-rwx------` | `omarchy` | no setuid bit at all |

So this is not a Brave defect: any Chromium-family binary started bare in this session dies the
same way. Chromium appears to work only because Omarchy's launcher hard-codes the flags in its
proot branch:

```
omarchy-launch-browser:  exec chromium --no-sandbox --disable-dev-shm-usage --password-store=basic ...
```

That is a per-app fix of the same shape, for one app.

## The fix, one launcher for the family

`complete/bin/chromium-proot` takes the name it was called by, so one file serves every member:

```
name=${0##*/}
exec "/usr/bin/$name" --no-sandbox --disable-dev-shm-usage --password-store=basic "$@"
```

(This is the launcher as this round left it. Software GPU mode later added two flags; see
"Follow-up: Chromium in software GPU mode" below.)

The complete hook links it into `/usr/local/bin` for each name the image actually has (`brave`,
`chromium`, `google-chrome-stable`, `microsoft-edge-stable`, `vivaldi-stable`, `opera` are tried;
only present ones are linked). `/usr/local/bin` comes before `/usr/bin` in the session PATH, so a
desktop entry's `Exec=brave %U`, the Omarchy menu, `omarchy-launch-browser` and a plain `brave` in
a terminal all get the flags. Adding a browser to the image needs no code change.

Switch: `OMDROID_COMPLETE_BROWSER=0` keeps the image launchers (`OMDROID_COMPLETE=0` turns it off
with the rest of the hook).

## What stays per-app, and what cannot be fixed

- **Electron apps** do not read a wrapper: they honour `ELECTRON_DISABLE_SANDBOX=1` from the
  environment. The image has none installed; if one is added, that variable belongs in the session
  environment rather than in this launcher.
- **The sandbox itself cannot be made to work here.** It needs a root-owned 4755 helper and
  unprivileged user namespaces; proot fakes uid 0 for the process, not for file ownership or
  namespace creation. Running with the sandbox off is the correct trade inside this session, and it
  is what Omarchy itself does under proot.

## Measured on the tablet

| step | result |
| --- | --- |
| bare `brave`, before | aborts with the sandbox FATAL, no window; processes die |
| bare `brave`, after | a full Brave window (tab strip, address bar, new-tab page), 8-10 processes alive at 45 s |
| both family members linked | `/usr/local/bin/brave` and `/usr/local/bin/chromium` point at `complete/bin/chromium-proot` |
| which display it uses | Wayland on the session's own socket (`wayland-1`, Hyprland); the `drmGetDevices2`, udev and dbus errors in its log are benign here |

The "quit unexpectedly" dialog that still appears is Brave's own record of the earlier crashed
launches; "Don't ask again" clears it.

## Host gate

`docs/omarchy/tools/complete-hook-test.sh` covers the links against a fake `/usr/bin`: an installed
browser is linked to the launcher, a second family member shares it, a name the image lacks is not
linked, and the switch off removes them.

## Not verified

- `chromium` launched through the link was not opened to a page; only its `--version` was checked.
  It already worked via Omarchy's own flags, which the launcher duplicates.
- A cold start carrying this fix: no APK with it has been built yet, so the links in the running
  session came from running the hook by hand.

## Follow-up: Chromium in software GPU mode

`r10-gpu-fallback.md` left one thing open: the image's `~/.config/chromium-flags.conf` forces
`--use-angle=vulkan`, which only the KGSL path can serve. This closes it in the launcher, because
the flags file belongs to the image and Arch's Chromium launcher reads no environment variable for
flags.

### What happens without the fix

Measured outside the tablet: the image's own Chromium 151.0.7922.137 and Mesa in an arm64 Linux
container, with the software-mode environment as `start.sh` exports it, `cage` on the wlroots
headless backend standing in for the session, no proot. Three starts:

- the GPU process is started 3 to 5 times per browser start and exits every time
  (`eglInitialize Vulkan failed with error EGL_NOT_INITIALIZED`, `Initialization of all (1) EGL
  display types failed`, `Exiting GPU process due to errors during initialization`);
- the browser then runs without one: `SystemInfo.getInfo` reports `gl=disabled`, WebGL off,
  compositing and rasterisation in software, and `canvas.getContext('webgl')` returns null;
- pages still draw. A page that requests an animation frame every frame got 60 per second, and a
  scripted 6000 px scroll kept its frame count. No slow start showed in the container (the
  DevTools pipe answered in about 0.3 s on a warm profile). On a tablet each failed GPU process is
  a process start under proot; see "On a tablet" below.

### The fix

In software mode, and only when the browser's flags file asks for `--use-angle=vulkan`, the
launcher adds `--use-angle=gles --disable-gpu-compositing`. Arch's launcher puts the file's flags
before its arguments and Chromium keeps the last value of a switch, so these override the file and
a caller's own switches still override these. Brave has no flags file and is left as it was, and
KGSL mode gets exactly the three sandbox flags as before.

| variant, same container and image | GPU process | WebGL | animation frames per second | log lines that are not D-Bus noise |
| --- | --- | --- | --- | --- |
| before, 3 runs | exits 3 to 5 times, then none | no | 60.0 | 63, 105, 105 |
| `--use-angle=gles` alone, 2 runs | ANGLE on llvmpipe, GPU compositing | yes | 46.6, 49.0 | 4 |
| `--use-angle=gles --disable-gpu-compositing` (the fix), 3 runs | ANGLE on llvmpipe, software compositing | yes, by readback | 59.8 to 60.0 | 4 |
| `--disable-gpu`, 1 run | none | no | 60.0 | 0 |

GLES alone keeps GPU compositing, so llvmpipe draws every frame, and the rate fell by about a
fifth on a 14-core host; a tablet has less to spare. Turning GPU compositing off keeps the
software compositor that the unfixed browser ends up with anyway, and keeps the GPU process for
WebGL. The ozone platform is not touched: `--ozone-platform=wayland --use-angle=gles` was tried
too and gave the same renderer, with GPU compositing dropping to software by itself after a
failed context.

### The desktop entry

Arch's `chromium.desktop` says `Exec=/usr/bin/chromium %U`, so the app launcher walks past the
link in `/usr/local/bin` and never reaches this launcher. That went unnoticed because the flags
file already carries the sandbox flags. The hook now writes a copy of such an entry to
`/usr/local/share/applications`, which `XDG_DATA_DIRS` lists before `/usr/share`, with the path
dropped from each `Exec` line. The copy is rewritten every session, ends with a marker line, and
only a file that ends with that marker is ever replaced or removed. `OMDROID_COMPLETE_BROWSER=0`
removes it together with the links.

### Host gate

`complete-hook-test.sh` gained 13 checks: the entry copy (made for a full-path `Exec`, not made
for a bare one, other lines kept, somebody else's file neither replaced nor removed, removed by
the switch) and the launcher's argument list (KGSL mode, no mode, software mode, a caller's
switch last, a browser without a Vulkan flags file, the last `--use-angle` of the flags files
deciding). The fake browsers print their arguments, so the checks compare the exact list.

### On a tablet

Four runs on a Xiaomi Pad Mini (Mali-G925, Android 16, not rooted), under proot, in a software-mode
session: llvmpipe, no Vulkan ICD in the environment. This was not an APK of this branch. It was a
separate lab build based on 0.0.18-alpha, with the same Chromium 151.0.7922.137 and the same
`chromium-flags.conf` in its guest. The launcher on `main` and the launcher of this change were
copied into the guest and started by path, with `OMDROID_GPU=software` set by hand because that
build names its mode variable differently. Each run was a cold start of the session, one browser
start on a new profile, and the same probe as in the container; before and after alternated.

| | before, 2 runs | the fix, 2 runs |
| --- | --- | --- |
| GPU process | started 3 times, exits every time | one, ANGLE on llvmpipe, initialised in 0.14 s |
| WebGL | no | yes, by readback |
| DevTools pipe answers after | 1.9 s, 1.6 s | 1.8 s, 1.5 s |
| `SystemInfo.getInfo` answers after | 2.9 s, 2.5 s | 1.8 s, 1.6 s |
| test page loaded after | 3.5 s, 3.1 s | 1.9 s, 1.6 s |
| frames during the scripted 6000 px scroll (3.3 s) | 84, 82 | 78, 86 |
| log lines | 77, 78 | 18, 18 |

So on this device the three failed GPU process starts cost about 1.5 s before the first page is
loaded, and the scroll lost nothing. The 18 remaining lines are D-Bus, inotify, udev and netlink
messages that proot causes, plus one group saying the GLES backend has no Vulkan instance; none is
an EGL initialisation failure.

Limits of these runs:

- In that lab build the browser's X11 window went to the Android X server directly (`DISPLAY=:0`)
  and was not a Hyprland client. The page drew in both variants (one screenshot each), but frame
  pacing through the compositor was not measured. The idle animation-frame rate was 110 to 114 per
  second in all four runs; nothing paces it there, so it says nothing.
- The hook itself did not run on the device: the launchers were started by path, so the link in
  `/usr/local/bin` and the desktop entry copy were not exercised.
- Two runs per side, one device.

### Not verified

- A software-mode session of this branch as an APK, and frame pacing of the browser as a Hyprland
  client on a device.
- That the Omarchy app launcher picks the copy in `/usr/local/share/applications`. It follows from
  the XDG lookup order and was not observed.
- KGSL mode is unchanged by construction: the launcher adds nothing unless `OMDROID_GPU=software`.
  The host gate pins that; no device run does.
