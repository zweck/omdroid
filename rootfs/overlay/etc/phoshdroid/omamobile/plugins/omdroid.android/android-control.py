#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Request a fixed host control through the app-private mailbox."""
import os
from pathlib import Path
import sys
import uuid

ACTIONS = {"wifi", "bluetooth", "display", "rotation-lock", "rotation-auto", "volume", "airplane", "battery", "settings"}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ACTIONS:
        return 2
    directory = Path("/tmp/omdroid-android/requests")
    if not directory.is_dir():
        return 1
    temporary = directory / (uuid.uuid4().hex + ".new")
    try:
        with temporary.open("x") as stream:
            stream.write(sys.argv[1] + "\n")
        os.replace(temporary, temporary.with_suffix(".request"))
    except OSError:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
