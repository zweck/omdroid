# R13: the guest timezone, and a rotation lock that writes

Round 13 is three changes, each merged through its own pull request: two from rustiqly and one that
finishes the second. None of them touches the guest image, so the APK is the whole delivery.

| PR | change | merge |
| --- | --- | --- |
| #19 | the guest's timezone follows Android's | `377fda1` |
| #20 | a rotation-lock tile in the mobile quick settings | `9d29ecb` |
| #23 | the rotation lock writes Android's switch and confirms it | `1091897` |

## The guest timezone

Android's `persist.sys.timezone` is turned into a relative `/etc/localtime` symlink into
`../usr/share/zoneinfo` when a session starts, so the guest's clock and log timestamps match the
phone's zone rather than staying on UTC.

Measured on the tablet: the link was replaced by hand with UTC (`date +%Z` read `UTC`), the session
was restarted, and the link came back as `../usr/share/zoneinfo/Europe/London` with a fresh timestamp
and `date +%Z` reading `GMT`. The sync therefore acts on a change rather than assuming.

A zone id the guest cannot use — a fixed offset such as `GMT+05:30`, or a zone newer than the image's
tzdata — still leaves the guest on UTC, and there is no log line saying so. The review asked for one;
it is the first item of the next round.

## The rotation lock

#20 added the tile; #23 is what makes it true. On the tablet the tile flipped to "Locked" while
Android's auto-rotate stayed on, because the write saved Termux:X11's `forceOrientation` preference
and the published state read that same preference back: the tile was confirming itself, and Android's
switch was never touched.

#23 declares `WRITE_SETTINGS`, grants the app-op in `install.sh` (printed in its proof line, with the
manual Settings path when the grant is refused), writes `USER_ROTATION` then
`ACCELEROMETER_ROTATION`, reads the switch back, and treats a throw or a mismatched read as failure —
logging `rotation lock refused: wanted=… read=…` and writing nothing else. The tile renders the
confirmed snapshot, and a system-settings observer republishes changes made outside the shell.

Measured on the tablet: `install.sh` printed `write_settings=WRITE_SETTINGS: allow`; two taps took
`settings get system accelerometer_rotation` from 1 to 0 and back, with the tile reading "Locked" and
then "Auto-rotate" and the snapshot agreeing; the lock survived a cold start, and an external
`settings put` flipped the tile within three seconds.

## Not verified

- The refusal arm on a device. HyperOS/A17 does not enforce the `WRITE_SETTINGS` app-op for
  `Settings.System.putInt` — writes still landed with the package and uid modes set to `deny`, after a
  cold start — and `pm grant`/`revoke` reports the permission as role-managed. The refusal path is
  proven in the JVM tests and the Qt no-ack case, not on hardware.
- The rotation tile on a docked display; nothing in this round was measured on the phone.
- A zone id the guest rejects, as above: the behaviour is known, the reason is not recorded.

## Gates

On this release's tree (`1091897` plus this commit): `:app:testDebugUnitTest` 254 tests,
0 failures, 1 skipped; `:app:lintDebug` 0 errors, 38 pre-existing warnings; `:app:assembleDebug`
builds the APK with the submodule in it (1,124,894,288 B, `sha256 29db1785…`).
