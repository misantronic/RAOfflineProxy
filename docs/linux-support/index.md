# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux version is for handheld Linux devices where you want the same basic offline flow as on Android:

- Start the proxy
- Play a game online once so it is cached
- Keep earning casual achievements while offline
- Let queued awards sync later when you reconnect

Use the `KNULLI`, `Onion`, `muOS`, `ROCKNIX`, and `spruce` tabs throughout the Linux section to switch target-specific instructions.

## Supported Targets

- KNULLI (alpha)
- Onion (alpha)
- muOS (alpha)
- ROCKNIX (alpha)
- spruce (alpha)

## Specifics

:::tabs key:linux-target

== KNULLI

It is currently intended for [KNULLI Scarab](https://github.com/knulli-cfw/knulli-linux/releases/tag/20260511) and [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

== Onion

It is currently compatible with [Onion V4.4.0-beta-20260120](https://github.com/OnionUI/Onion/releases/tag/latest).

[OnionOS v4.3.1-1](https://github.com/OnionUI/Onion/releases/tag/v4.3.1-1) ships an older bundled RetroArch build whose achievements client is not reliably compatible with a custom host. RAOfflineProxy detects this on launch and refuses to run, showing an unsupported-version error instead.

== muOS

It is currently compatible with [MustardOS 2601.1 Funky Jacaranda](https://muos.dev/release/current/2601_1).

The muOS target ships as a `.muxapp` package installed through Archive Manager and runs the same SDL menu used on the other Linux targets.

== ROCKNIX

It is currently compatible with [ROCKNIX 20260701](https://github.com/ROCKNIX/distribution/releases/tag/20260701) or newer.

The ROCKNIX target ships as a self-extracting installer and runs the same SDL menu used on the other Linux targets. The app installs under `/storage/.local/share/raofflineproxy` and appears in the **Tools** menu.

Both RetroArch and standalone PPSSPP are supported on ROCKNIX. Starting the proxy patches both `retroarch.cfg` and, when present, `ppsspp.ini`.

== spruce

It is currently compatible with [spruce 4.3.x](https://github.com/spruceUI/spruceOS/releases), tested on a Miyoo Mini Plus running spruce 4.3.4.

The bundled Python runtime and SDL libraries are 32-bit ARM builds, which covers spruce's 32-bit devices: the Miyoo Mini family and the A30. spruce's other devices (TrimUI, Anbernic, Miyoo Flip, GKD Pixel 2, MagicX Zero 28) are 64-bit and would need an aarch64 build, which this bundle does not ship.

spruce needs no minimum version the way Onion does — spruce 4.3.x already ships a RetroArch build that works with a custom host. Two things differ from Onion: the proxy uses port `8099`, because spruce's SFTPGo already occupies `8080`, and RetroAchievements must be set to `Softcore` in spruce's own settings, since spruce forces achievements off when its mode is `Disabled`.

:::

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux install, startup, and UI behavior can vary a lot by firmware and frontend.
- Always check the correct tab for your device instead of assuming KNULLI, Onion, muOS, ROCKNIX, and spruce behave the same way.
