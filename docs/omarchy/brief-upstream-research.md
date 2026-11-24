# Brief: upstream facts for the Omarchy-on-Android shell (READ ONLY research)

You are the RESEARCHER for this brief. Do the work yourself in this session: never create
agents, never call `delegate_task`.

## Rules

- READ ONLY. No `adb` at all (another agent is driving the tablet). No edits anywhere in
  `/home/phil/Development/personal/proot-post`. No git commands that change anything.
  Do not post, push or comment anywhere.
- Your directory: `~/.hermes/cache/scratch/omarchy/upstream/`. Everything you download or
  extract goes there. Your deliverable is `~/.hermes/cache/scratch/omarchy/upstream/findings.md`.
- Write `findings.md` in your first calls (headings Q1..Q7, each `PENDING`) and fill each answer
  in the moment you have it: the other agent reads the file while you work. Q1, Q2, Q3 first.
- Every answer: file path + line number + a short quoted snippet. Label each statement `QUOTED`
  (you read it) or `INFERRED` (your reasoning). No answer from memory. If something is not in the
  sources, write `NOT FOUND` and where you looked.
- At most 250 lines in `findings.md`.

## Context

Phoshdroid runs the Omarchy desktop inside an Android app: an X server in the app (Xlorie), a
proot Arch guest, `cage` (wlroots, x11 backend) as a nested Wayland parent, the patched Hyprland
from `/opt/omarchy-android`, and Omarchy's shell `quickshell -n -p /usr/share/omarchy/shell`.
The guest image comes from the upstream project `BlackFireAlex/omarchy-android` (MIT).

Measured on the device: Hyprland runs (one monitor 3200x2136, scale 2). Quickshell runs and owns
one layer surface, namespace `omarchy-bar`, 1600x26. **The bar is only a background band: no
text, no icons. There is no wallpaper layer at all.** The guest env has `OMARCHY_PROOT=1`.
`~/.config/hypr/autostart.lua` only starts `omarchy-voxtype-daemon`.

## Sources (use in this order)

1. The shipped guest image, which is exactly what is on the device:
   `/home/phil/Development/personal/proot-post/app/src/main/assets/rootfs.bin` (gzipped tar, 1.5 GB,
   83313 members, paths start with `./`). List it ONCE into your directory
   (`tar -tzf <rootfs.bin> > ~/.hermes/cache/scratch/omarchy/upstream/members.txt`, takes about a
   minute), then extract only the small paths you need into your directory, for example
   `tar -xzf <rootfs.bin> -C ~/.hermes/cache/scratch/omarchy/upstream/guest ./usr/share/omarchy/shell ./usr/share/omarchy/bin ./home/omarchy/.config/hypr ./home/omarchy/.config/omarchy ./home/omarchy/.config/quickshell`
   (drop paths that are not in the listing). Never extract the whole image.
2. Upstream source: `curl -sSL https://api.github.com/repos/BlackFireAlex/omarchy-android/tarball -o up.tgz`
   then `tar -xzf up.tgz` in your directory. If that fails, try
   `gh api repos/BlackFireAlex/omarchy-android/tarball > up.tgz`. If both fail, say so and carry on
   with source 1.
3. Local notes, read first so you do not redo them:
   `/home/phil/Development/personal/proot-post/docs/omarchy/{ARCHITECTURE,CONTRACT,payload-findings,bootstrap-findings,device-debug}.md`.
4. `~/omarchy-payload/` may hold the upstream release bundle; list it, read only.

## Questions

- **Q1. What starts the shell?** In the guest under `OMARCHY_PROOT=1`: which script or config line
  launches quickshell (exact command, environment it sets, what it waits or polls for before
  starting, e.g. `hyprctl -j monitors`), and what restarts it. Trace from
  `~/.config/hypr/hyprland.lua` through every file it loads.
- **Q2. What does the bar need?** Which QML files build the bar (`omarchy-bar`), and for each
  widget (workspaces, clock, tray, network, audio, battery, bluetooth, ...) the data source it
  needs: Hyprland IPC, UPower, NetworkManager, PipeWire/Pulse, BlueZ, systemd/logind, a script.
  Which of these are stubbed or patched for proot (search for `OMARCHY_PROOT`, `proot`, `android`
  in the shell). What happens when a source is missing: does one widget stay empty, or does the
  whole bar content fail to load? Any Qt/Quickshell environment the launcher sets
  (`QT_QUICK_BACKEND`, `QSG_RHI_BACKEND`, `QSG_RENDER_LOOP`, `QT_QPA_PLATFORM`, `QS_*`, icon theme, font).
  Which font family and icon font the bar text uses.
- **Q3. What draws the wallpaper?** `swaybg`, `hyprpaper`, the shell itself, or something else;
  launched from where; under which conditions when `OMARCHY_PROOT=1`; the expected layer namespace.
  Is the binary in the image (check `members.txt`)?
- **Q4. Output geometry.** How upstream computes output width/height/scale/refresh, what
  `~/.config/hypr/monitors.lua` does with `OMARCHY_OUTPUT_WIDTH/HEIGHT/REFRESH_MHZ/SCALE`, and how
  resize or rotation is handled. Does upstream expect the forced mode to equal the parent
  compositor's window size?
- **Q5. hyprctl from outside.** How does upstream's host-side wrapper
  (`runtime/host/omarchy-android-hyprctl` or similar) reach Hyprland's IPC socket? Any mention of
  socket path length or proot path rewriting?
- **Q6. Known issues.** Anything in upstream README, docs, issues folder or changelog about: blank
  or empty bar, missing wallpaper, black screen, Xwayland, GPU/KGSL fallbacks.
- **Q7. Input.** How touch and keyboard are expected to travel from Termux:X11 to Hyprland, and any
  Termux:X11 preference or Hyprland input setting upstream requires.

## Final reply

At most 10 lines: the one-sentence answer to Q1, Q2 and Q3, anything that looks like the cause
of the empty bar or missing wallpaper, and the path of `findings.md`.
