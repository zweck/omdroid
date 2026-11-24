#!/usr/bin/env python3
"""List top-level X windows with their geometry (what Xlorie actually granted)."""
import ctypes
import ctypes.util
import os

x11 = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
W = ctypes.c_ulong
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XDefaultRootWindow.restype = W
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XQueryTree.argtypes = [ctypes.c_void_p, W, ctypes.POINTER(W), ctypes.POINTER(W),
                           ctypes.POINTER(ctypes.POINTER(W)), ctypes.POINTER(ctypes.c_uint)]
x11.XGetGeometry.argtypes = [ctypes.c_void_p, W, ctypes.POINTER(W),
                             ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                             ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
                             ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
x11.XFetchName.argtypes = [ctypes.c_void_p, W, ctypes.POINTER(ctypes.c_char_p)]
x11.XGetWindowAttributes.argtypes = [ctypes.c_void_p, W, ctypes.c_void_p]

dpy = x11.XOpenDisplay(os.environ.get("DISPLAY", ":0").encode())
root = x11.XDefaultRootWindow(dpy)
r, p = W(), W()
kids = ctypes.POINTER(W)()
n = ctypes.c_uint()
x11.XQueryTree(dpy, root, ctypes.byref(r), ctypes.byref(p), ctypes.byref(kids), ctypes.byref(n))


def geom(win):
    rr, x, y = W(), ctypes.c_int(), ctypes.c_int()
    w, h, bw, d = (ctypes.c_uint() for _ in range(4))
    ok = x11.XGetGeometry(dpy, win, ctypes.byref(rr), ctypes.byref(x), ctypes.byref(y),
                          ctypes.byref(w), ctypes.byref(h), ctypes.byref(bw), ctypes.byref(d))
    return (x.value, y.value, w.value, h.value, d.value) if ok else None


print("root:", geom(root))
for i in range(n.value):
    win = kids[i]
    g = geom(win)
    nm = ctypes.c_char_p()
    name = ""
    if x11.XFetchName(dpy, win, ctypes.byref(nm)) and nm.value:
        name = nm.value.decode("utf-8", "replace")
    print(f"win 0x{win:x} -> {g} name={name!r}")
