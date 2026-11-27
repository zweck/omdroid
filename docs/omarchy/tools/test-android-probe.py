#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""The Android probe path must not call Linux hardware or systemd backends."""
import os
from pathlib import Path
import subprocess
import tempfile

script = Path(__file__).resolve().parents[3] / "rootfs/overlay/etc/phoshdroid/omamobile/plugins/omamobile.quicksettings/tile-state.sh"
with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    forbidden = root / "forbidden"
    for name in ("rfkill", "nmcli", "powerprofilesctl", "systemctl", "omamobile-sit"):
        stub = root / name
        stub.write_text('#!/bin/sh\nprintf "%s\\n" "$0" >> "$PROBE_FORBIDDEN"\nexit 99\n')
        stub.chmod(0o755)
    for name, output in {"omarchy-theme-color": "dark", "hyprsunset": "", "omarchy-toggle-nightlight": '{"enabled":false}', "omamobile-screenrecord": "off", "wf-recorder": ""}.items():
        stub = root / name
        stub.write_text("#!/bin/sh\nprintf '%s\\n' '" + output + "'\n")
        stub.chmod(0o755)
    env = dict(os.environ, PATH=f"{root}:/usr/bin:/bin", PROBE_FORBIDDEN=str(forbidden), HOME=str(root))
    result = subprocess.run(["sh", str(script), "--android"], env=env, capture_output=True, text=True, timeout=5, check=True)
    assert not forbidden.exists(), forbidden.read_text() if forbidden.exists() else ""
    for expected in ("airplane=none", "hotspot=none", "powerprofile=none", "theme_mode=dark", "dictation=none", "data=none", "record=off"):
        assert expected in result.stdout, result.stdout
print("Android probe: no Linux hardware/systemd calls; software status retained")
