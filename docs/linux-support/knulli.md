# KNULLI

KNULLI is the first Linux target with an end-to-end alpha install and on-device menu flow in `RAOfflineProxy`.

KNULLI support is currently **alpha**. It is usable as a public prerelease target, but it should not be treated as stable yet.

It is currently intended for [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

## Current State

KNULLI already has a usable alpha flow:

- Install through a single `RAOfflineProxy Install.sh`
- Launch `RAOfflineProxy` from the Tools menu
- Start and stop the proxy from the on-device menu
- Cache games for offline play
- Queue softcore achievements while offline and send them later
- Manage cached games directly on the device

## Install Flow

The current KNULLI install flow is:

1. Download `RAOfflineProxy-Knulli-v1.0.1-alpha4-Install.sh` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy it into `/userdata/roms/tools`
3. Launch `RAOfflineProxy Install` from EmulationStation Tools
4. Update gamelists if needed so the new Tools entry appears immediately

After install, the main Tools entry is:

- `RAOfflineProxy`

## SDL Menu

Current SDL menu capabilities:

- Start / Stop proxy
- Enable / Disable autostart
- Cached Games
- Pending Awards
- Add ROM
- Clear cache
- Uninstall
- Exit Menu
- Game image and achievement badge previews in cached game views

## Authentication

Current authentication behavior:

- RAOfflineProxy first uses RetroArch's saved `cheevos_token` when present
- If no token is available, `cheevos_username` and `cheevos_password` are used once to retrieve and cache a token
- `cheevos_password` is never treated as the API token

## Notes

- Tested on an ANBERNIC RG40XX H
- Cached data works both from normal online RetroArch launches and from manual ROM adding in the menu
- Install and update flow are still alpha-quality
- KNULLI integration is still being refined across devices and firmware versions
- The SDL menu depends on `pygame` being available on-device

## What Is Still Rough

- Visual presentation is still being tuned for device-specific KNULLI behavior
- Clearing cache while the proxy is actively running does not stop the service first, so live requests can repopulate game cache entries again
- Autostart is currently implemented for KNULLI/Batocera-style startup hooks, not every Linux environment
