# KNULLI Bundle

This directory contains a portable KNULLI bundle for the early Linux `RAOfflineProxy` client.

## Current State

The experimental KNULLI flow is now working end to end in testing for the proxy path:

- Start patches the required RetroArch and Batocera settings
- RetroAchievements requests are intercepted locally
- offline game launches still reach the proxy
- offline softcore achievements can be queued
- queued achievements can be flushed on reconnect

This is still considered experimental and may need additional hardening across devices and KNULLI versions.

## What It Does

- installs the Python app under `/userdata/system/raofflineproxy/app`
- installs small launcher scripts under `/userdata/system/raofflineproxy/bin`
- adds EmulationStation Tools entries for:
  - `RAOfflineProxy Menu`

`RAOfflineProxy Menu` is the primary experimental interactive fullscreen UI. It provides one place to launch Start, Stop, Uninstall, and Exit actions.

The current `RAOfflineProxy Menu` implementation uses SDL/`pygame` for responsive controller-driven navigation. The launcher adds `/userdata/roms/pygame` to `PYTHONPATH` automatically when that directory exists.

This bundle now patches RetroArch config and launches the background Linux proxy service.

## Build

From repo root:

```bash
./linux/knulli/build_bundle.sh
```

This creates:

- `linux/knulli/dist/raofflineproxy-knulli-bundle.tar.gz`
- `linux/knulli/dist/RAOfflineProxy Install.sh`

## Install On KNULLI

Copy the single-file installer to `/userdata/roms/tools` and launch it from EmulationStation Tools.

```bash
cp "path/to/RAOfflineProxy/linux/knulli/dist/RAOfflineProxy Install.sh" /path/to/mounted/knulli/share/roms/tools/
```

Then run `RAOfflineProxy Install` from EmulationStation Tools.

The installer removes itself after a successful install.

## Uninstall

Use `Uninstall` inside `RAOfflineProxy Menu`.
