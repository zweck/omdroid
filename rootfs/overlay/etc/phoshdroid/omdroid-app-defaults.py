#!/usr/bin/python3
"""Repair unusable defaults in the pinned image without replacing user choices."""
import hashlib
import os
from pathlib import Path
import shutil
import tempfile

BUNDLED_CHROMIUM_SHA256 = "c451db4944960607e50440cd2b2b71c9a870abfed0d36ba9465cb5d7e2f0346c"


def configure_browser(home):
    flags = home / ".config/chromium-flags.conf"
    if not flags.is_file() or flags.is_symlink():
        return False
    original = flags.read_bytes()
    if hashlib.sha256(original).hexdigest() != BUNDLED_CHROMIUM_SHA256:
        return False
    # The bundle targets a different parent compositor. X11 never maps a
    # Chromium window here; Wayland/Vulkan also requires a DRM render node.
    updated = original.decode().replace("--ozone-platform=x11", "--ozone-platform=wayland")
    updated = updated.replace("--ozone-platform-hint=x11", "--ozone-platform-hint=wayland")
    updated += "\n# Omdroid nested Wayland compatibility (stock flags backed up).\n"
    updated += "--disable-gpu\n--disable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan\n"
    backup = flags.with_name(flags.name + ".before-omdroid-wayland")
    # Exclusive creation preserves an earlier backup and never follows a link.
    with backup.open("xb") as stream:
        stream.write(original)
    fd, temporary = tempfile.mkstemp(prefix=".chromium-flags-", dir=flags.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(updated)
        os.chmod(temporary, flags.stat().st_mode & 0o777)
        os.replace(temporary, flags)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return True


def configure_editor(home):
    editor = home / ".local/state/omarchy/defaults/editor"
    if editor.exists() or editor.is_symlink() or shutil.which("nvim") or not shutil.which("nano"):
        return False
    editor.parent.mkdir(parents=True, exist_ok=True)
    with editor.open("x") as stream:
        stream.write("nano\n")
    return True


if __name__ == "__main__":
    for configure in (configure_editor, configure_browser):
        try:
            configure(Path.home())
        except OSError as error:
            print(f"omdroid-app-defaults: {configure.__name__}: {error}", flush=True)
