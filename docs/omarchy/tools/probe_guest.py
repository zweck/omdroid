#!/usr/bin/env python3
"""Guest half of probe.sh. Prints one line `PROBE-JSON: {...}` holding:

  x      X root size and every top-level window on DISPLAY (default :0)
  hypr   monitors, layers and clients, read over the proot-remapped IPC socket (see hyprsock.py)
  ready  content of $XDG_RUNTIME_DIR/omarchy-ready, or null when the marker is absent

It runs through gx.sh (a second proot), so it needs nothing from the live session's environment.
"""
import ctypes
import ctypes.util
import glob
import json
import os
import socket
import stat


def xinfo():
    out = {"root": None, "windows": [], "error": None}
    try:
        x11 = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
    except OSError as e:
        out["error"] = "libX11: %s" % e
        return out
    win_t = ctypes.c_ulong
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    x11.XDefaultRootWindow.restype = win_t
    x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
    x11.XQueryTree.argtypes = [ctypes.c_void_p, win_t, ctypes.POINTER(win_t), ctypes.POINTER(win_t),
                               ctypes.POINTER(ctypes.POINTER(win_t)), ctypes.POINTER(ctypes.c_uint)]
    x11.XGetGeometry.argtypes = [ctypes.c_void_p, win_t, ctypes.POINTER(win_t),
                                 ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                                 ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
                                 ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
    x11.XSetErrorHandler.restype = ctypes.c_void_p
    # A window can vanish between XQueryTree and XGetGeometry; Xlib's default handler would exit.
    handler = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda d, e: 0)
    x11.XSetErrorHandler(handler)

    dpy = x11.XOpenDisplay(os.environ.get("DISPLAY", ":0").encode())
    if not dpy:
        out["error"] = "cannot open the X display (X server not running)"
        return out

    def geom(win):
        r, x, y = win_t(), ctypes.c_int(), ctypes.c_int()
        w, h, bw, d = (ctypes.c_uint() for _ in range(4))
        ok = x11.XGetGeometry(dpy, win, ctypes.byref(r), ctypes.byref(x), ctypes.byref(y),
                              ctypes.byref(w), ctypes.byref(h), ctypes.byref(bw), ctypes.byref(d))
        return (x.value, y.value, w.value, h.value) if ok else None

    root = x11.XDefaultRootWindow(dpy)
    g = geom(root)
    if g:
        out["root"] = {"w": g[2], "h": g[3]}
    r, p = win_t(), win_t()
    kids = ctypes.POINTER(win_t)()
    n = ctypes.c_uint()
    if x11.XQueryTree(dpy, root, ctypes.byref(r), ctypes.byref(p), ctypes.byref(kids), ctypes.byref(n)):
        for i in range(n.value):
            g = geom(kids[i])
            if g:
                out["windows"].append({"id": "0x%x" % kids[i], "x": g[0], "y": g[1], "w": g[2], "h": g[3]})
    x11.XCloseDisplay(dpy)
    return out


def candidates():
    socks = []
    for path in glob.glob("/tmp/proot-*"):
        try:
            st = os.stat(path)
        except OSError:
            continue
        if stat.S_ISSOCK(st.st_mode):
            socks.append((st.st_mtime, path))
    socks.sort(reverse=True)
    if not socks:
        return []
    newest_pid = socks[0][1].split("-")[1]
    return [path for _, path in socks if path.split("-")[1] == newest_pid]


def ask(path, req, timeout=4.0):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(path)
        s.sendall(req.encode())
        data = b""
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
        return data.decode("utf-8", "replace")
    finally:
        s.close()


def hyprinfo():
    out = {"error": None, "monitors": None, "layers": None, "clients": None}
    socks = candidates()
    if not socks:
        out["error"] = "NO-SESSION: no proot-* sockets in /tmp (Hyprland has not bound its IPC yet)"
        return out
    ctl = None
    for path in socks:
        try:
            if ask(path, "j/version", 2.0).strip():
                ctl = path
                break
        except (OSError, socket.timeout):
            continue
    if ctl is None:
        out["error"] = "NO-IPC: %d socket(s) found, none answered" % len(socks)
        return out
    for key in ("monitors", "layers", "clients"):
        try:
            out[key] = json.loads(ask(ctl, "j/" + key))
        except Exception as e:  # noqa: BLE001 - report every failure as data
            out["error"] = "%s: %s" % (key, e)
    return out


def ready():
    path = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000"), "omarchy-ready")
    try:
        with open(path) as fh:
            return fh.read().strip()
    except OSError:
        return None


print("PROBE-JSON: " + json.dumps({"x": xinfo(), "hypr": hyprinfo(), "ready": ready()}))
