#!/usr/bin/env python3
"""Move the focused Omdroid DeX task into fullscreen and wait for the guest to fit."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

COMPONENT = "com.phoshdroid.app/com.termux.x11.MainActivity"
WM_SHELL = ("dumpsys", "activity", "service",
            "com.android.systemui/.SystemUIService", "WMShell")


def display_task(dump):
    """Return the focused display activity's task, window mode and maximum size."""
    component = re.escape(COMPONENT)
    if not re.search(r"mCurrentFocus=Window\{[^\n]* " + component + r"\}", dump):
        raise RuntimeError("Bring Omdroid's desktop to the foreground first")
    ids = set(re.findall(r"topResumedActivity=ActivityRecord\{[^\n]* " + component
                         + r" t(\d+)\b", dump))
    if len(ids) != 1:
        raise RuntimeError("Could not identify a single active Omdroid display task")
    task = ids.pop()
    header = re.search(r"^\s*\* Task\{[^\n]*#" + task
                       + r"\b[^\n]*\bmode=([\w-]+)[^\n]*\}", dump, re.M)
    if not header:
        raise RuntimeError("Could not read Omdroid's window mode")
    # Stay inside this task, rather than taking another display's maximum bounds.
    block = re.split(r"\n\s*\* Task\{", dump[header.end():], maxsplit=1)[0]
    bounds = re.search(r"mGlobalConfig=[^\n]*mMaxBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", block)
    if not bounds:
        raise RuntimeError("Could not read Omdroid's display bounds")
    left, top, right, bottom = map(int, bounds.groups())
    # Cage rounds the X root down to even pixels, including on odd-height panels.
    size = ((right - left) // 2 * 2, (bottom - top) // 2 * 2)
    if min(size) <= 0:
        raise RuntimeError("Omdroid's display bounds are empty")
    return task, header.group(1), size


def request_fullscreen(android):
    task, mode, _ = display_task(android("dumpsys", "activity", "activities"))
    if mode == "fullscreen":
        return task
    if mode != "freeform":
        raise RuntimeError("Use Android's fullscreen control to leave " + mode + " mode")
    if "moveTaskOutOfDesk" not in android(*WM_SHELL, "help"):
        raise RuntimeError("This DeX version has no fullscreen task command; use its fullscreen button")
    # The help query takes time; do not act on a task that lost focus meanwhile.
    current, mode, _ = display_task(android("dumpsys", "activity", "activities"))
    if current != task:
        raise RuntimeError("Omdroid's active task changed; run the command again")
    if mode == "freeform":
        android(*WM_SHELL, "desktopmode", "moveTaskOutOfDesk", task)
    elif mode != "fullscreen":
        raise RuntimeError("Omdroid's window mode changed; run the command again")
    return task


def guest_fits(output, size):
    try:
        monitors = json.loads(output.split("## j/monitors\n", 1)[1])
        return any((m["width"], m["height"]) == size for m in monitors)
    except (IndexError, KeyError, TypeError, ValueError):
        return False


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    serial = os.environ.get("TABLET_SERIAL")
    if not serial:
        raise RuntimeError("Set TABLET_SERIAL to the adb serial of the test device")
    adb = ["adb", "-s", serial, "shell"]

    def android(*args):
        return subprocess.check_output(adb + list(args), text=True, timeout=10)

    task = request_fullscreen(android)
    time.sleep(.5)  # Allow DeX's task transition to restore window focus.
    deadline = time.monotonic() + 30
    helper = Path(__file__).with_name("hc.sh")
    while time.monotonic() < deadline:
        current, mode, size = display_task(android("dumpsys", "activity", "activities"))
        if current != task:
            raise RuntimeError("Omdroid's active task changed while waiting for fullscreen")
        if mode == "fullscreen":
            probe = subprocess.run([str(helper), "j/monitors"], capture_output=True,
                                   text=True, timeout=10)
            if probe.returncode == 0 and guest_fits(probe.stdout, size):
                print(f"Omdroid fullscreen: {size[0]}x{size[1]} (task {task})")
                return
        time.sleep(.5)
    raise RuntimeError("The guest did not reach fullscreen geometry within 30 seconds")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
