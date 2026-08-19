# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux version is for handheld Linux devices where you want the same basic offline flow as on Android:

- Start the proxy
- Play a game online once so it is cached
- Keep earning casual achievements while offline
- Let queued awards sync later when you reconnect

Use the `KNULLI`, `Onion`, `muOS`, and `ROCKNIX` tabs throughout the Linux section to switch target-specific instructions.

## Supported Targets

- KNULLI (alpha)
- Onion (alpha)
- muOS (alpha)
- ROCKNIX (alpha)
- spruce (experimental)

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

:::

## spruce

A separate `RAOfflineProxy-Spruce-*.zip` build exists for [spruceOS](https://github.com/spruceUI/spruceOS), tested on a Miyoo Mini running spruce 4.3.4. Extract it over the SD card root so the app lands in `App/RAOfflineProxy`, then follow the Onion instructions throughout this section — the two builds behave the same except:

- **RetroAchievements must be set to Softcore** in spruce's own settings. spruce rewrites the achievements keys of the RetroArch config on every game launch, and forces achievements off when its mode is set to Disabled.
- **The proxy uses port 8099** rather than 8080, which spruce's SFTPGo already occupies.
- Only the Miyoo Mini family is covered. The bundled Python and SDL2 libraries are 32-bit ARM builds for that hardware; spruce's other devices are 64-bit and are not supported by this build.

Unlike Onion, there is no minimum OS version — spruce 4.3.x already ships a RetroArch build that works with a custom host.

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux install, startup, and UI behavior can vary a lot by firmware and frontend.
- Always check the correct tab for your device instead of assuming KNULLI, Onion, muOS, and ROCKNIX behave the same way.
