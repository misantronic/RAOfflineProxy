# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux version is for handheld Linux devices where you want the same basic offline flow as on Android:

- Start the proxy
- Play a game online once so it is cached
- Keep earning casual achievements while offline
- Let queued awards sync later when you reconnect

Use the `KNULLI`, `Onion`, and `muOS` tabs throughout the Linux section to switch target-specific instructions.

## Supported Targets

- KNULLI (alpha)
- Onion (alpha)
- muOS (alpha)
- ROCKNIX (planned)

## Specifics

:::tabs key:linux-target

== KNULLI

It is currently intended for [KNULLI Scarab](https://github.com/knulli-cfw/knulli-linux/releases/tag/20260511) and [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

Current rough edges:

- Install and update flow are still alpha-quality
- Autostart is currently implemented for KNULLI/Batocera-style startup hooks, not every Linux environment

== Onion

It is currently compatible with [OnionOS v4.3.1-1](https://github.com/OnionUI/Onion/releases/tag/v4.3.1-1) and [Onion V4.4.0-beta-20260120](https://github.com/OnionUI/Onion/releases/tag/latest).

Current rough edges:

- The bundled runtime is still larger than ideal and takes time to copy to SD storage
- Patch-state persistence still deserves more cleanup

== muOS

It is currently compatible with [MustardOS 2601.1 Funky Jacaranda](https://muos.dev/release/current/2601_1).

The muOS target ships as a `.muxapp` package installed through Archive Manager and runs the same SDL menu used on the other Linux targets.

Current rough edges:

- The bundled runtime (including `pygame`, which stock muOS does not ship) is larger than ideal and takes time to copy to SD storage
- Patching has to keep both `retroarch.cfg` and the `retroarch.cheevos.cfg` appendconfig in sync

:::

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux install, startup, and UI behavior can vary a lot by firmware and frontend.
- Always check the correct tab for your device instead of assuming KNULLI, Onion, and muOS behave the same way.
