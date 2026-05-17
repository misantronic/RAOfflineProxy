# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux implementation is focused on keeping the same core proxy behavior as Android.

## Current State

The Linux version already covers the main offline proxy flow:

- Cache games for offline use
- Queue softcore achievements while offline and send them later
- Manage cached games from an on-device menu on supported targets
- Support manual ROM adding for supported systems

## ROM Hashing

Current Linux ROM hashing coverage includes:

- Game Boy / Game Boy Color / Game Boy Advance
- NES / FDS / SNES
- PC Engine
- Atari 7800 / Atari Lynx
- Super Cassette Vision
- Nintendo 64
- Nintendo DS
- PSP
- PSX

Starting a game through RetroArch while the proxy is active still caches that game normally. The ROM hashing list above is specifically about manual ROM adding and offline game identification paths.

## Supported Targets

The first Linux target with a more complete installation and menu flow is:

- [KNULLI](/linux-support/knulli) (alpha)
- ROCKNIX (planned)
- [Onion](/linux-support/onion) (experimental)

Additional Linux targets may be documented later, but they are not considered public install targets yet.

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux-specific install, startup, and UI behavior can vary by firmware and frontend.
