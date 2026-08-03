# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux version is for handheld Linux devices where you want the same basic offline flow as on Android:

- Start the proxy
- Play a game online once so it is cached
- Keep earning casual achievements while offline
- Let queued awards sync later when you reconnect

Use the `KNULLI`, `Onion`, `muOS`, `ROCKNIX`, and `dArkOS` tabs throughout the Linux section to switch target-specific instructions.

## Supported Targets

- KNULLI (alpha)
- Onion (alpha)
- muOS (alpha)
- ROCKNIX (alpha)
- dArkOS (community-contributed, unverified by the developer)

## Specifics

:::tabs key:linux-target

== KNULLI

It is currently intended for [KNULLI Scarab](https://github.com/knulli-cfw/knulli-linux/releases/tag/20260511) and [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

== Onion

It is currently compatible with [Onion V4.4.0-beta-20260120](https://github.com/OnionUI/Onion/releases/tag/latest).

[OnionOS v4.3.1-1](https://github.com/OnionUI/Onion/releases/tag/v4.3.1-1) ships an older bundled RetroArch build whose achievements client is not reliably compatible with a custom host, so achievements may fail to appear there even though the proxy and caching work correctly.

== muOS

It is currently compatible with [MustardOS 2601.1 Funky Jacaranda](https://muos.dev/release/current/2601_1).

The muOS target ships as a `.muxapp` package installed through Archive Manager and runs the same SDL menu used on the other Linux targets.

== ROCKNIX

It is currently compatible with [ROCKNIX 20260701](https://github.com/ROCKNIX/distribution/releases/tag/20260701) or newer.

The ROCKNIX target ships as a self-extracting installer and runs the same SDL menu used on the other Linux targets. The app installs under `/storage/.local/share/raofflineproxy` and appears in the **Tools** menu.

Both RetroArch and standalone PPSSPP are supported on ROCKNIX. Starting the proxy patches both `retroarch.cfg` and, when present, `ppsspp.ini`.

== dArkOS

Community-contributed target for [dArkOS](https://github.com/christianhaitian/dArkOS) ("Debian based ArkOS"). Not yet verified against real hardware by the developer — please report issues.

Current rough edges:

- dArkOS is systemd-native (no `custom.sh`-style startup hook), so autostart installs a systemd unit instead — this uses passwordless `sudo` for the device user (dArkOS's own Tools scripts rely on the same mechanism), and is silently skipped with an on-screen hint if that's not available
- `pygame` comes from `apt` rather than a bundled runtime; the installer installs it automatically via `sudo`, otherwise install it manually if the menu fails to launch
- No known equivalent of `batocera.conf`/`knulli.conf`, so only the RetroArch-side patch applies

:::

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux install, startup, and UI behavior can vary a lot by firmware and frontend.
- Always check the correct tab for your device instead of assuming KNULLI, Onion, muOS, ROCKNIX, and dArkOS behave the same way.
