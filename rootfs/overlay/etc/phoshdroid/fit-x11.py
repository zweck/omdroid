#!/usr/bin/env python3
"""Keep the nested compositor's X11 window the size of the X root.

Termux:X11 (Xlorie) has no window manager, so nothing sizes top-level windows.
wlroots' x11 backend (cage) opens a fixed 1024x768 window and takes any later
ConfigureNotify as the new output mode, so one XMoveResizeWindow per window is
all it takes to make the compositor fill the screen.

Parity: the X root may be odd (the host does not round anything), so cage's
window is the root size with each dimension rounded DOWN to even. At scale 2
the logical size is then integral and the spare pixel row stays black.

usage: fit-x11.py [--wait [max-seconds]] [display] [poll-seconds]
  --wait   one-shot for session start: resize, confirm the geometry, exit 0.
           Gives up after max-seconds (default 10) with exit 1 and one line.
  default  follow the root for ever (the poller start.sh starts).
"""
import ctypes
import os
import sys
import time

ARGS = sys.argv[1:]
WAIT = None
if ARGS and ARGS[0] == "--wait":
    WAIT = 10.0
    ARGS = ARGS[1:]
    if ARGS and ARGS[0].replace(".", "", 1).isdigit():
        WAIT = float(ARGS[0])
        ARGS = ARGS[1:]

DISPLAY = ARGS[0] if len(ARGS) > 0 else os.environ.get("DISPLAY", ":0")
POLL = float(ARGS[1]) if len(ARGS) > 1 else 1.0
VERBOSE = bool(os.environ.get("FIT_X11_VERBOSE"))
FIT_OUT = os.environ.get("FIT_X11_OUT", "/tmp/omarchy-fit")

x11 = ctypes.CDLL("libX11.so.6")
Window = ctypes.c_ulong
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XDefaultRootWindow.restype = Window
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XQueryTree.argtypes = [
    ctypes.c_void_p, Window,
    ctypes.POINTER(Window), ctypes.POINTER(Window),
    ctypes.POINTER(ctypes.POINTER(Window)), ctypes.POINTER(ctypes.c_uint),
]
x11.XGetGeometry.argtypes = [
    ctypes.c_void_p, Window, ctypes.POINTER(Window),
    ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
    ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
    ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
]
x11.XMoveResizeWindow.argtypes = [
    ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint,
]
x11.XFetchName.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(ctypes.c_char_p)]
x11.XFree.argtypes = [ctypes.c_void_p]
x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
x11.XSetErrorHandler.restype = ctypes.c_void_p

# A window can vanish between XQueryTree and the resize; that is not fatal.
_HANDLER = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda d, e: 0)
x11.XSetErrorHandler(_HANDLER)


def even(n):
    """A root dimension rounded DOWN to even (contract section 1)."""
    return n & ~1


def geometry(dpy, win):
    root, x, y = Window(), ctypes.c_int(), ctypes.c_int()
    w, h, bw, depth = (ctypes.c_uint() for _ in range(4))
    ok = x11.XGetGeometry(dpy, win, ctypes.byref(root), ctypes.byref(x), ctypes.byref(y),
                          ctypes.byref(w), ctypes.byref(h), ctypes.byref(bw), ctypes.byref(depth))
    return (x.value, y.value, w.value, h.value) if ok else None


def name(dpy, win):
    p = ctypes.c_char_p()
    if x11.XFetchName(dpy, win, ctypes.byref(p)) and p.value:
        s = p.value.decode("utf-8", "replace")
        x11.XFree(p)
        return s
    return ""


def target(dpy):
    """The size cage's window must have: the X root, each side rounded down to even."""
    rg = geometry(dpy, x11.XDefaultRootWindow(dpy))
    if not rg:
        return None
    return even(rg[2]), even(rg[3])


def windows(dpy):
    """(window, geometry, name) for every child of the root big enough to be a surface."""
    root = x11.XDefaultRootWindow(dpy)
    r, parent = Window(), Window()
    kids = ctypes.POINTER(Window)()
    n = ctypes.c_uint()
    out = []
    if not x11.XQueryTree(dpy, root, ctypes.byref(r), ctypes.byref(parent),
                          ctypes.byref(kids), ctypes.byref(n)):
        return out
    for i in range(n.value):
        win = kids[i]
        g = geometry(dpy, win)
        # Only surfaces: 1x1 helper windows are left alone.
        if not g or g[2] < 64 or g[3] < 64:
            continue
        out.append((win, g, name(dpy, win)))
    if kids:
        x11.XFree(kids)
    return out


def fit_once(dpy):
    """Resize every surface that is not already exactly the target. Returns count moved."""
    t = target(dpy)
    if not t:
        return 0
    tw, th = t
    moved = 0
    for win, g, nm in windows(dpy):
        gx, gy, gw, gh = g
        if VERBOSE:
            print(f"win 0x{win:x} {gx},{gy} {gw}x{gh} name={nm!r}", flush=True)
        if (gx, gy, gw, gh) != (0, 0, tw, th):
            x11.XMoveResizeWindow(dpy, win, 0, 0, tw, th)
            moved += 1
            print(f"fit-x11: 0x{win:x} {gw}x{gh}@{gx},{gy} -> {tw}x{th}@0,0", flush=True)
    x11.XSync(dpy, 0)
    return moved


def fits(dpy, tw, th):
    """True when a surface exists and every surface is exactly tw x th at 0,0."""
    wins = windows(dpy)
    if not wins:
        return False
    return all(g == (0, 0, tw, th) for _, g, _ in wins)


def open_display(tries=300, delay=0.2):
    for _ in range(tries):
        dpy = x11.XOpenDisplay(DISPLAY.encode())
        if dpy:
            return dpy
        time.sleep(delay)
    print(f"fit-x11: cannot open {DISPLAY}", file=sys.stderr, flush=True)
    return None


def wait_mode(dpy, wait):
    """One-shot: resize and confirm. Exit 0 on confirmed geometry, 1 on the cap.

    On success the confirmed size is written to FIT_OUT (`<w> <h>`), so the
    session can hand Hyprland the size cage really has instead of the host's
    prediction (which is only a hint and may be odd).
    """
    start = time.monotonic()
    announced = None
    while True:
        t = target(dpy)
        if t:
            fit_once(dpy)
            if fits(dpy, *t):
                try:
                    with open(FIT_OUT, "w") as fh:
                        fh.write(f"{t[0]} {t[1]}\n")
                except OSError as e:
                    print(f"fit-x11: cannot write {FIT_OUT}: {e}", flush=True)
                print(f"fit-x11: confirmed {t[0]}x{t[1]} after "
                      f"{time.monotonic() - start:.2f}s", flush=True)
                return 0
            if announced != t:
                print(f"fit-x11: waiting for a surface at {t[0]}x{t[1]}", flush=True)
                announced = t
        if time.monotonic() - start >= wait:
            print(f"fit-x11: not confirmed after {wait:.0f}s; starting Hyprland anyway",
                  flush=True)
            return 1
        time.sleep(0.05)


def main():
    dpy = open_display(tries=40 if WAIT is not None else 300,
                       delay=0.05 if WAIT is not None else 0.2)
    if not dpy:
        return 1
    if WAIT is not None:
        return wait_mode(dpy, WAIT)
    while True:
        fit_once(dpy)
        time.sleep(POLL)


if __name__ == "__main__":
    sys.exit(main())
