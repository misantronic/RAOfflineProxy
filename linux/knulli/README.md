# KNULLI Bundle

This directory contains a portable KNULLI bundle for the early Linux `RAOfflineProxy` client.

## Current State

The alpha KNULLI flow is now working end to end in testing for the proxy path:

- Start patches the required RetroArch and Batocera settings
- RetroAchievements requests are intercepted locally
- offline game launches still reach the proxy
- offline softcore achievements can be queued
- queued achievements can be flushed on reconnect

This is still considered alpha and may need additional hardening across devices and KNULLI versions.

## What It Does

- installs the Python app under `/userdata/system/raofflineproxy/app`
- installs small launcher scripts under `/userdata/system/raofflineproxy/bin`
- adds EmulationStation Tools entries for:
  - `RAOfflineProxy`

`RAOfflineProxy` is the primary interactive fullscreen UI. It provides one place to launch Start, Stop, Cached Games, Pending Awards, Uninstall, and Exit actions.

The current `RAOfflineProxy` implementation uses SDL/`pygame` for responsive controller-driven navigation. The launcher adds `/userdata/roms/pygame` to `PYTHONPATH` automatically when that directory exists.

Current SDL menu features:

- Start / Stop proxy
- Enable / Disable autostart
- Cached Games view
- Pending Awards view
- Add ROM from a controller-driven file browser
- per-game cache actions
- Clear cache
- Uninstall
- Exit Menu

Cached Games currently supports:

- previewing cached game images in the top-right corner when available
- previewing the selected unlocked achievement badge beside the game image when available
- removing a selected cached game's cache entries
- clearing game-related cache entries while preserving cached login and User-Agent data
- manual ROM adding for multiple RetroAchievements-supported hash formats including Game Boy, NES, SNES, N64, NDS, PSP, and PSX families
- showing unlocked achievement titles in the per-game action view

Manual ROM adding and online game launch caching are intended to produce the same local game cache:

- `gameid:<hash>` game lookup entries
- `patch:<gameId>:<user>` achievement metadata
- `unlocks:<gameId>:<user>:0` softcore unlock state
- `startsession:<gameId>:<user>:0` synthetic offline session data built from cached unlocks

Live upstream `startsession` responses are not cached. They are a special case so offline launches use the local synthetic response instead.

KNULLI authentication is token-first:

- if `cheevos_token` exists in `retroarch.cfg`, RAOfflineProxy uses it directly
- if no token exists, `cheevos_username` and `cheevos_password` are used once with `login2` to retrieve and cache a token
- `cheevos_password` is not used as the token for later `patch`, `unlocks`, or award flush requests

This bundle now patches RetroArch config and launches the background Linux proxy service.

Autostart support currently uses:

- `/userdata/system/custom.sh` as the startup hook
- `/userdata/system/.config/raofflineproxy/` as the stable config/cache directory

This keeps the same cached login/game data visible to:

- the SDL menu
- manual CLI launches
- autostarted proxy services after reboot

## Build

From repo root:

```bash
./linux/knulli/build_bundle.sh
```

This creates:

- `linux/knulli/dist/RAOfflineProxy-Knulli-v1.3.1-alpha1-Install.sh`

## Install On KNULLI

Copy the single-file installer to `/userdata/roms/tools` and launch it from EmulationStation Tools.

```bash
cp "path/to/RAOfflineProxy/linux/knulli/dist/RAOfflineProxy-Knulli-v1.3.1-alpha1-Install.sh" /path/to/mounted/knulli/share/roms/tools/
```

Then run `RAOfflineProxy Install` from EmulationStation Tools.

The installer removes itself after a successful install.

After install, the on-screen message asks you to update gamelists if needed so the new Tools entry appears immediately.

When updating an existing install, the installer now preserves prior proxy running state:

- if the proxy was already running, it is stopped before files are replaced
- after installation, it is started again so the updated code is active immediately
- if the proxy was stopped before install, it stays stopped

## Uninstall

Use `Uninstall` inside `RAOfflineProxy`.

The KNULLI uninstall flow now:

- stops the proxy first
- disables autostart
- removes the Tools entry
- removes the installed bundle
- removes the persistent RAOfflineProxy config/cache directory
- removes legacy root-home RAOfflineProxy config/cache data from older builds when present

This clears cached game data, cached login/token data, queued awards, logs, and other RAOfflineProxy state on uninstall.

On KNULLI, the final file cleanup is performed by a short detached cleanup step after the menu/uninstall process exits, so RAOfflineProxy-owned config/cache files are not recreated by the still-running app during shutdown.
