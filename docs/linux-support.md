# Linux Support

Linux support is planned, and an experimental Linux/KNULLI implementation now exists in the repository.

At the moment, the current public release and the main supported target remain **Android**.

Linux and KNULLI work is still **experimental** and not yet documented as a supported public installation path.

## Current State

The current Linux work is focused on **KNULLI / Batocera-style** devices running RetroArch.

What is already working in the experimental implementation:

- patching RetroArch config for the local proxy
- patching Batocera/KNULLI config so achievements are still routed through the proxy
- running a local Python proxy service
- forwarding RetroAchievements requests while online
- serving cached game data while offline
- queuing softcore achievement awards while offline
- flushing queued awards automatically when connectivity returns

What is still rough:

- installation and update flow are still experimental
- KNULLI integration is still being hardened and simplified
- logs and tooling are still evolving
- Android remains the only fully documented and supported target

Treat Linux support as a development preview rather than an officially supported feature for now.
