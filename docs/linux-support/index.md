# Linux Support

Linux support now exists in `RAOfflineProxy` and is currently in **alpha**.

## Overview

The Linux version is for handheld Linux devices where you want the same basic offline flow as on Android:

- Start the proxy
- Play a game online once so it is cached
- Keep earning softcore achievements while offline
- Let queued awards sync later when you reconnect

Use the `KNULLI` and `Onion` tabs throughout the Linux section to switch target-specific instructions.

## Supported Targets

- KNULLI (alpha)
- Onion (experimental)
- ROCKNIX (planned)

## Specifics

:::tabs key:linux-target

== KNULLI

KNULLI is the first Linux target with an end-to-end alpha install and on-device menu flow in `RAOfflineProxy`.

It is currently intended for [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

KNULLI is the most complete Linux target right now.

Current rough edges:

- Install and update flow are still alpha-quality
- Clearing cache while the proxy is actively running does not stop the service first, so live requests can repopulate game cache entries again
- Autostart is currently implemented for KNULLI/Batocera-style startup hooks, not every Linux environment

== Onion

Onion is an experimental but working Linux target for `RAOfflineProxy`.

It is currently intended for [Onion V4.4.0-beta-20260120](https://github.com/OnionUI/Onion/releases/tag/latest).

Current rough edges:

- The UI is still terminal-based
- The bundled runtime is still larger than ideal and takes time to copy to SD storage
- Patch-state persistence still deserves more cleanup

:::

## Important Notes

- Linux support is currently in alpha and should still be treated as a prerelease feature.
- Linux install, startup, and UI behavior can vary a lot by firmware and frontend.
- Always check the correct tab for your device instead of assuming KNULLI and Onion behave the same way.
