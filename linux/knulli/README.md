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
  - `RAOfflineProxy Status`
  - `RAOfflineProxy Start`
  - `RAOfflineProxy Stop`
  - `RAOfflineProxy Uninstall`

`RAOfflineProxy Status` writes its rendered screen to `/userdata/system/raofflineproxy/ui-screen.txt`, renders a framebuffer-friendly image to `/userdata/system/raofflineproxy/ui-screen.bmp`, and then tries to display that image with `fbv`.

The display duration defaults to 15 seconds and can be overridden by setting `RAOFFLINEPROXY_STATUS_SECONDS` before launching the dashboard script.

Start and Stop write summary output to `/userdata/system/raofflineproxy/ui-state.txt` and also try to trigger a frontend notification if KNULLI exposes a compatible helper.

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

```bash
Run `RAOfflineProxy Uninstall` from EmulationStation Tools.
```
