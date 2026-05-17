# Onion

Onion is now an experimental Linux target for `RAOfflineProxy`.

The current implementation uses an Onion community app bundle under `/App/RAOfflineProxy` and has been validated far enough to support real online and offline proxy flows on device.

## Current State

What currently works:

- Onion community app packaging under `/App/RAOfflineProxy`
- bundled private Python 3 runtime support
- terminal-driven menu for controller use
- proxy start and stop from the Onion app
- `retroarch.cfg` patch and revert for Onion's RetroArch path
- online login and normal online proxy traffic
- offline award queueing and reconnect flush
- per-game `achievementsets` caching for the Miyoo request pattern
- cached real `startsession` reuse for offline unlocked-count display
- autostart via `/.tmp_update/startup/raofflineproxy.sh`
- shutdown cleanup via `/.tmp_update/checkoff/raofflineproxy.sh`

## Current Limitations

What is still rough:

- the UI is still terminal-based, not a custom SDL/controller UI like KNULLI
- the bundled runtime is still larger than ideal and takes time to copy to SD storage
- patch-state persistence still deserves cleanup even though safe fallback revert behavior exists now
- host-mounted SD-card views can lag behind live device state while Onion is running

## Runtime Notes

Onion ships Python 2.7 through its Parasyte environment, but the current Linux client requires a newer runtime.

The Onion target therefore uses a bundled private Python 3 runtime built from the `python-build-standalone` Linux `armv7-unknown-linux-gnueabihf` archive.

Onion's system clock must also be correct for HTTPS requests to `retroachievements.org` to succeed.

## RetroArch Path

The Onion target expects RetroArch config at:

```text
/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg
```

## Menu

The current Onion app menu provides:

- `Start proxy` or `Stop proxy` depending on current state
- `Cached games`
- `Enable autostart` or `Disable autostart`
- `Exit`

## What Still Needs Work

Before Onion can be treated as a more stable public target, the remaining work is roughly:

1. improve runtime size and copy-time behavior
2. tighten patch-state persistence so exact revert state is always preserved
3. validate more edge cases around reboot, shutdown, and longer-lived sessions
4. decide whether Onion should get a richer non-terminal UI later

For now, Onion should be treated as an experimental but working target.
