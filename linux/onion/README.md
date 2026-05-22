# Onion App Bundle

This directory contains the current Onion community app target for the Linux `RAOfflineProxy` client.

## Current State

The Onion target is now a working experimental app bundle built on top of the shared Linux backend.

What currently works:

- Onion community app packaging under `/App/RAOfflineProxy`
- bundled private Python 3 runtime support
- terminal-driven controller-friendly menu
- proxy start and stop from the Onion app
- `retroarch.cfg` patch and revert for Onion's RetroArch path
- online login and normal online proxy flow
- offline award queueing and reconnect flush
- per-game `achievementsets` caching for the Miyoo request pattern
- cached real `startsession` reuse for offline unlocked-count display
- Onion autostart via `/.tmp_update/startup/raofflineproxy.sh`
- Onion shutdown cleanup via `/.tmp_update/checkoff/raofflineproxy.sh`

What is still rough:

- runtime payload size and copy time are still larger than ideal
- patch-state persistence on Onion still deserves cleanup even though fallback revert now works

## Runtime

This bundle expects a Python 3 runtime that can execute the shared Linux backend.

The launcher checks these locations in order:

1. `/mnt/SDCARD/App/RAOfflineProxy/runtime/bin/python3`
2. `/mnt/SDCARD/App/RAOfflineProxy/runtime/python/bin/python3`
3. `python3` on `PATH`
4. `python` on `PATH` if it reports a compatible major version

On stock Onion, a matching runtime is not assumed to exist, so this bundle is designed to include a private runtime during build.

Recommended runtime archive:

```text
cpython-3.10.20+20260510-armv7-unknown-linux-gnueabihf-install_only_stripped.tar.gz
```

The current helper uses the public `python-build-standalone` Linux `armv7-unknown-linux-gnueabihf` install-only stripped archive as the bundled runtime source.

## Build

From repo root:

```bash
./linux/onion/fetch_runtime.sh
./linux/onion/build_bundle.sh
```

This creates:

- `linux/onion/dist/raofflineproxy-onion-app/`
- `linux/onion/dist/RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip`

## Install On Onion

Copy the generated `App` folder contents to the SD card root:

```text
SDCARD_ROOT/App/RAOfflineProxy/
```

If you are preparing a private runtime manually, place it under either:

```text
SDCARD_ROOT/App/RAOfflineProxy/runtime/bin/python3
```

or:

```text
SDCARD_ROOT/App/RAOfflineProxy/runtime/python/bin/python3
```

The simpler path is to let the build include the cached runtime automatically after `./linux/onion/fetch_runtime.sh`.

## App Layout

The build output creates this app structure:

```text
App/
  RAOfflineProxy/
    app/
      raofflineproxy/
    autostart-cleanup.sh
    autostart-launch.sh
    autostart-template.sh
    checkoff-template.sh
    common.sh
    config.json
    icon.png
    launch.sh
    onion-menu.sh
    runtime/
```

The shared Python package is copied into `App/RAOfflineProxy/app/raofflineproxy` during the bundle build.

## Menu

The current Onion menu supports:

- `Start proxy` or `Stop proxy` depending on current state
- `Cached games`
- `Pending awards`
- `Clear cached games`
- `Enable autostart` or `Disable autostart` depending on current state
- `Exit`

The menu also shows live status information each time it redraws.

Onion does not cache RA image assets during game caching, since the current Onion UI never displays them.

The menu keeps the simple numbered input flow.

- Typing `1` to `4` still selects an action
- D-pad up cycles the selection through `1` to `4`
- D-pad down cycles the selection through `4` to `1`

## Autostart And Shutdown Hooks

Enabling autostart creates:

```text
/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh
```

That script calls the app's headless launcher:

```text
/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh
```

Launching the Onion app also installs a shutdown cleanup hook at:

```text
/mnt/SDCARD/.tmp_update/checkoff/raofflineproxy.sh
```

That hook calls:

```text
/mnt/SDCARD/App/RAOfflineProxy/autostart-cleanup.sh
```

The shutdown hook is best-effort cleanup for orderly Onion shutdown. It is not a guarantee against crashes or hard power loss.

## Onion Defaults

The Onion launcher exports these defaults:

- `RAOFFLINEPROXY_CONFIG_DIR=/mnt/SDCARD/App/RAOfflineProxy/data`
- `RAOFFLINEPROXY_RETROARCH_CFG=/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg`

## Requirements And Notes

- Onion's system clock must be correct for HTTPS to `retroachievements.org` to work
- the bundled runtime provides the CA bundle used by outbound HTTPS requests
- this target is tuned to the Miyoo/Onion request pattern, which uses `achievementsets` and `startsession`
- the cached-games list includes Onion-style `achievementsets` cache entries, not only old `patch` cache

## Current Limitations

- no runtime size optimization yet
- host-mounted SD-card views can lag behind the live device state while Onion is running
- patch-state fallback behavior is safe now, but saved-state persistence still deserves attention
