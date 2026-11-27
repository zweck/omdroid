#!/usr/bin/env python3
"""Request a fixed Android Settings page through the app-private mailbox."""
import os
from pathlib import Path
import sys
import uuid

ACTIONS = {"wifi", "bluetooth", "display", "rotation", "volume", "airplane", "battery", "settings"}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ACTIONS:
        return 2
    directory = Path("/tmp/omdroid-android/requests")
    if not directory.is_dir():
        return 1
    temporary = directory / (uuid.uuid4().hex + ".new")
    with temporary.open("x") as stream:
        stream.write(sys.argv[1] + "\n")
    os.replace(temporary, temporary.with_suffix(".request"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
