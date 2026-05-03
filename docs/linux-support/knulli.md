# KNULLI

KNULLI is the first Linux target with an end-to-end experimental install and on-device menu flow in `RAOfflineProxy`.

This is still **experimental** and should be treated as a development preview, not a stable public release path.

## Current State

KNULLI already has a usable experimental flow:

- install through a single `RAOfflineProxy Install.sh`
- launch `RAOfflineProxy` from the Tools menu
- start and stop the proxy from the on-device menu
- cache games for offline play
- queue softcore achievements while offline and send them later
- manage cached games directly on the device

## Install Flow

The current experimental KNULLI flow is:

1. Build `linux/knulli/dist/RAOfflineProxy Install.sh`
2. Copy it into `/userdata/roms/tools`
3. Launch `RAOfflineProxy Install` from EmulationStation Tools

After install, the main Tools entry is:

- `RAOfflineProxy`

## SDL Menu

Current SDL menu capabilities:

- Start / Stop proxy
- Cached Games
- Add ROM
- Clear cache
- Uninstall
- Exit Menu
- game image and achievement badge previews in cached game views

## Authentication

Current authentication behavior:

- KNULLI stores RetroAchievements username/password in RetroArch config, not a reusable API token
- RAOfflineProxy reads those values, performs a normal `login2` request once, and stores the returned token in its local cache
- `cheevos_password` is never treated as the API token

## Notes

- cached data works both from normal online RetroArch launches and from manual ROM adding in the menu
- install and update flow are still experimental
- KNULLI integration is still being refined across devices and firmware versions
- the SDL menu depends on `pygame` being available on-device

## What Is Still Rough

- visual presentation is still being tuned for device-specific KNULLI behavior
- clearing cache while the proxy is actively running does not stop the service first, so live requests can repopulate game cache entries again
- autostart is currently implemented for KNULLI/Batocera-style startup hooks, not every Linux environment
