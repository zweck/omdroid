# Android terminates Linux child processes

A desktop plus a multi-process browser can exceed Android's phantom-process
limit. The Android activity can remain alive while the guest vanishes with
exit code 137. That exit code alone does **not** identify the cause: memory
pressure or another SIGKILL can produce it too.

Confirm with device logs before changing settings:

```bash
adb -s "$TABLET_SERIAL" logcat -d | grep 'Trimming phantom processes'
adb -s "$TABLET_SERIAL" shell dumpsys activity settings | grep max_phantom
adb -s "$TABLET_SERIAL" shell device_config get activity_manager max_phantom_processes
```

Android 16's [ActivityManagerConstants](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActivityManagerConstants.java)
defaults to 32. Device firmware can differ. Record the existing override first.

For a device used as a Linux desktop, a bounded increase can provide headroom:

```bash
adb -s "$TABLET_SERIAL" shell device_config put activity_manager max_phantom_processes 128
adb -s "$TABLET_SERIAL" shell dumpsys activity settings | grep max_phantom
```

This is a **device-wide** setting, keeps process monitoring enabled, and can
permit additional memory and battery use. It is not an app permission. Omdroid
cannot set it as an ordinary app, and the installer must not apply it silently.
Check whether firmware preserves the override after reboot or system updates.

If the original value was unset (`null`), roll back with:

```bash
adb -s "$TABLET_SERIAL" shell device_config delete activity_manager max_phantom_processes
```

Otherwise restore the recorded numeric value using `device_config put`.

The separate `start-hyprland` warning is not evidence of this termination.
Replacing the direct compositor launch with its watchdog needs lifecycle testing:
the watchdog restarts crashes, while Omdroid deliberately limits session retries.
