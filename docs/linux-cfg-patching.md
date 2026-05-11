# Emulator CFG Patching (Linux)

## Overview

On Linux, RAOfflineProxy currently patches RetroArch so RetroAchievements traffic is redirected to the local proxy.

On KNULLI, this is handled automatically when you start and stop the proxy from the RAOfflineProxy menu.

## What gets patched

When the proxy starts, RAOfflineProxy updates `retroarch.cfg` and forces these settings while the proxy is active:

- `cheevos_enable = "true"`
- `cheevos_custom_host = "<proxy_host>:<proxy_port>"`
- `cheevos_hardcore_mode_enable = "false"`

It also imports your saved RetroAchievements login from RetroArch.

## Config detection

RAOfflineProxy looks for `retroarch.cfg` in common Linux and KNULLI locations.

Default detection order:

1. `RAOFFLINEPROXY_RETROARCH_CFG` environment override
2. `/userdata/system/configs/retroarch/retroarchcustom.cfg`
3. `/userdata/system/configs/retroarch/retroarch.cfg`
4. `/userdata/system/.config/retroarch/retroarchcustom.cfg`
5. `/userdata/system/.config/retroarch/retroarch.cfg`
6. `/storage/.config/retroarch/retroarch.cfg`
7. `~/.config/retroarch/retroarch.cfg`

You can also point RAOfflineProxy to a specific config file manually.

## Automatic patching

When you start the proxy, RAOfflineProxy:

1. Finds the RetroArch config
2. Patches it for proxy use
3. Starts the background local proxy service

While the Linux proxy service is running, it also re-enforces the needed RetroArch settings periodically in case the frontend or OS rewrites `retroarch.cfg`.

## Automatic reverting

When you stop the proxy, RAOfflineProxy stops the service first and then reverts the RetroArch config patch.

This clears the custom RetroAchievements host so RetroArch connects directly to RetroAchievements again.

If hardcore mode was enabled before you started the proxy, it is restored automatically when you stop the proxy.

## KNULLI notes

On KNULLI, starting the proxy also patches the needed frontend-side settings so offline launches still route through the local proxy.

This behavior is part of the KNULLI bundle and happens automatically from the RAOfflineProxy menu.

## Why hardcore mode is disabled

Hardcore mode is not supported by RAOfflineProxy. Any hardcore achievement unlock is rejected.

Keeping hardcore mode enabled while the proxy is active would cause unlock failures, so RAOfflineProxy disables it during patching and restores the previous setting when the proxy stops.
