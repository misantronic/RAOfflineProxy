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
- KNULLI installation through a single-file `RAOfflineProxy Install.sh` Tools entry
- a single KNULLI Tools entry for `RAOfflineProxy Menu`
- on-screen framebuffer status and action feedback using `fbv`
- an interactive KNULLI menu for Start, Stop, Uninstall, and Exit

## KNULLI Flow

The current experimental KNULLI flow is:

1. Build `linux/knulli/dist/RAOfflineProxy Install.sh`
2. Copy it into `/userdata/roms/tools`
3. Launch `RAOfflineProxy Install` from EmulationStation Tools

After install, this Tools entry is created:

- `RAOfflineProxy Menu`

Current on-device feedback behavior:

- `RAOfflineProxy Menu` is the primary interactive KNULLI UI for Start, Stop, Uninstall, and Exit
- `RAOfflineProxy Install` still displays a short framebuffer feedback screen during installation

Current install UX:

- after running `RAOfflineProxy Install`, the installer asks the user to `Please Update Gamelists.`
- the installer removes itself after a successful install
- the Tools list is expected to expose only `RAOfflineProxy Menu`

What is still rough:

- installation and update flow are still experimental
- KNULLI integration is still being hardened and simplified
- visual presentation is still being tuned for device-specific framebuffer behavior
- the current interactive menu is stable enough to use, but navigation still has noticeable delay because menu presentation is using the safer `fbv` path instead of the faster direct framebuffer experiments
- Android remains the only fully documented and supported target

Treat Linux support as a development preview rather than an officially supported feature for now.
