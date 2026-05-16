# ROCKNIX Target Investigation

This directory is reserved for a future `RAOfflineProxy` ROCKNIX target.

At the moment, this is an investigation snapshot rather than an implementation.

## Summary

Current evidence suggests a ROCKNIX port is likely feasible, but it should not be treated as a direct copy of the existing KNULLI target.

The Linux core in `linux/raofflineproxy/` already appears reusable. The main work is expected to be a ROCKNIX-specific integration layer for:

- install paths
- launcher placement
- config detection
- autostart integration
- optional SDL menu runtime packaging

## Current Status

What looks reusable as-is:

- the shared Linux proxy/service code in `linux/raofflineproxy/`
- RetroArch config patching logic, conceptually
- local cache, pending award, and flush logic
- the existing Python CLI entrypoints

What is currently KNULLI/Batocera-specific and would need adaptation:

- `/userdata/...` filesystem assumptions
- `batocera.conf` patching
- `/userdata/system/custom.sh` autostart handling
- KNULLI installer packaging in `linux/knulli/`
- KNULLI launcher locations under `/userdata/system/raofflineproxy/`

## Confirmed Findings

### Python 3

ROCKNIX includes Python 3 in its distribution tree.

Evidence:

- `packages/lang/Python3/package.mk`

This strongly suggests the shared Linux client can run on-device without needing a Python runtime bundled from scratch.

### RetroArch Integration Exists

ROCKNIX ships RetroArch as a first-class package.

Evidence:

- `projects/ROCKNIX/packages/emulators/libretro/retroarch`
- `projects/ROCKNIX/packages/emulators/libretro/retroarch/sources/RK3566/retroarch.cfg`
- `projects/ROCKNIX/packages/emulators/libretro/retroarch/sources/SM8650/retroarch.cfg`

This makes a RetroArch-targeted proxy flow realistic.

### EmulationStation Integration Exists

ROCKNIX includes EmulationStation and an autostart flow for it.

Evidence:

- `projects/ROCKNIX/packages/ui/emulationstation`
- `projects/ROCKNIX/packages/ui/emulationstation/autostart/001-emulationstation`
- `projects/ROCKNIX/packages/ui/emulationstation/sources/start_es.sh`

### Tools and Ports Integration Exists

ROCKNIX exposes tool and port configuration in its tree.

Evidence:

- `config/emulators/tools.conf`
- `config/emulators/ports.conf`

Earlier investigation also indicated that tools are exposed through simple shell-script based integration rather than requiring a native compiled frontend extension.

### Autostart Infrastructure Exists

ROCKNIX clearly has a system autostart mechanism, even though a KNULLI-style user `custom.sh` hook has not been confirmed.

Evidence:

- `projects/ROCKNIX/packages/sysutils/autostart/sources/autostart`
- `projects/ROCKNIX/packages/sysutils/autostart/system.d/rocknix-autostart.service`
- `projects/ROCKNIX/packages/rocknix/autostart/001-setup`

This means autostart is likely possible, but it probably needs a ROCKNIX-specific implementation rather than reusing the KNULLI path directly.

## Likely Findings

### `/storage` Is The Primary Mutable Root

ROCKNIX appears to be more `/storage`-centric than KNULLI.

Observed evidence from public docs and repository exploration points to:

- `/storage/.config/...`
- `/storage/roms/...`
- module/tool integration under ROCKNIX-managed config locations

This is close enough to the current Linux target shape that a platform abstraction should be sufficient.

### `retroarch.cfg` Is Likely Under `/storage/.config/retroarch/`

This path has not been fully confirmed from a live device in this repository, but it remains the most likely runtime location based on ROCKNIX conventions and current documentation.

Likely candidate:

- `/storage/.config/retroarch/retroarch.cfg`

### Tool Launchers Are Likely Script-Driven

ROCKNIX appears friendly to shell-script launchers for tool or port entries. That aligns well with the current Linux/KNULLI launcher approach.

## Important Nuance About `pygame`

There is now strong evidence that `pygame` can run on ROCKNIX, but not that it ships preinstalled by default.

The external project [`cw-dojo`](https://github.com/DavidClawson/cw-dojo) is a Python and `pygame` application targeting ROCKNIX on the R36S.

Its README shows:

- `python3` is available on-device
- `pygame` and `numpy` are installed manually into `/storage/lib/python3.11/site-packages`
- ROCKNIX system SDL libraries are reused by symlinking them into `pygame.libs`

That means:

- Python 3 support is effectively confirmed
- `pygame` runtime support is practically proven
- `pygame` should currently be treated as an extra packaged dependency, not an assumed built-in

For `RAOfflineProxy`, this means the SDL menu is likely viable on ROCKNIX, but it may need either:

- manual dependency installation
- bundled wheels
- a ROCKNIX-specific packaging flow for `pygame`

## Unknowns

These items still need confirmation on a real ROCKNIX device or through deeper repo inspection:

- the exact active runtime location of `retroarch.cfg`
- whether a `batocera.conf` equivalent must also be patched
- the preferred user-facing install location for `RAOfflineProxy`
- the cleanest autostart hook for a background proxy service
- whether `pygame` should be bundled, installed separately, or avoided for first bring-up
- whether any device families need special launcher or resolution handling

## Implication For Implementation

The most likely implementation path is:

1. keep using the shared Linux code in `linux/raofflineproxy/`
2. add a dedicated `linux/rocknix/` target instead of reusing `linux/knulli/`
3. replace KNULLI-specific path assumptions with ROCKNIX-specific ones
4. add a ROCKNIX installer/launcher flow for Tools or Ports
5. decide separately whether the first version includes the SDL menu or only the CLI/service flow

## Status Verdict

Current verdict: likely feasible.

Current caution: not a drop-in KNULLI build.

The core Linux port appears reusable, but ROCKNIX should be treated as its own platform target.
