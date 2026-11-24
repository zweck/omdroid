#!/usr/bin/env python3
"""hyprctl for a SECOND proot instance.

proot rewrites an over-long AF_UNIX path (the Hyprland IPC path translated to the
host is ~140 bytes, sun_path holds 108) to a temp name `$PROOT_TMP_DIR/proot-<pid>-XXXXXX`
and records the mapping only inside the proot instance that did the bind. So
`hypr/<sig>/.socket.sock` is invisible from adb `run-as` and from any other proot
(stock hyprctl answers "Couldn't connect ... (4)"), while the socket itself is alive.
The guest /tmp is the same host directory, so this script talks to the temp name.

usage: hyprsock.py <request> [<request> ...]   e.g.  j/monitors  j/layers  "dispatch exec foot"
       hyprsock.py -f <file>                   one request per line
       hyprsock.py --list                      show the candidate sockets only
"""
import glob
import os
import socket
import stat
import sys


def candidates():
    socks = []
    for p in glob.glob("/tmp/proot-*"):
        try:
            st = os.stat(p)
        except OSError:
            continue
        if stat.S_ISSOCK(st.st_mode):
            socks.append((st.st_mtime, p))
    socks.sort(reverse=True)
    if not socks:
        return []
    newest_pid = socks[0][1].split("-")[1]
    return [p for _, p in socks if p.split("-")[1] == newest_pid]


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


def main():
    args = sys.argv[1:]
    socks = candidates()
    if not socks:
        sys.exit("NO-SESSION: no proot-* sockets in /tmp (no live session, or Hyprland has not bound its IPC sockets yet)")
    if args == ["--list"]:
        print("\n".join(socks))
        return
    if args[:1] == ["-f"]:
        with open(args[1]) as fh:
            args = [line.rstrip("\n") for line in fh if line.strip()]
    # Two sockets per session: the request socket answers, the event socket never does.
    ctl = None
    for p in socks:
        try:
            if ask(p, "j/version", 2.0).strip():
                ctl = p
                break
        except (OSError, socket.timeout):
            continue
    if ctl is None:
        sys.exit("NO-IPC: found %d socket(s) %s but none answered j/version" % (len(socks), socks))
    print("# control socket:", ctl)
    for req in args or ["j/monitors"]:
        print("## " + req)
        try:
            print(ask(ctl, req))
        except (OSError, socket.timeout) as e:
            print("ERROR:", e)


if __name__ == "__main__":
    main()
