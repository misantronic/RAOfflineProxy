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
- on-screen install/status feedback using `fbv`
- an interactive SDL-based KNULLI menu for Start, Stop, Cached Games, Uninstall, and Exit
- autostart toggle support from the SDL root menu
- controller-driven cached game management inside the SDL menu
- manual ROM adding from a file browser rooted in RetroArch/KNULLI paths
- cached game preview images in the SDL menu
- Linux ROM hashing support for multiple RetroAchievements platforms
- login bootstrap from RetroArch/KNULLI `cheevos_username` + `cheevos_password`, with the returned RA token cached locally

## KNULLI Flow

The current experimental KNULLI flow is:

1. Build `linux/knulli/dist/RAOfflineProxy Install.sh`
2. Copy it into `/userdata/roms/tools`
3. Launch `RAOfflineProxy Install` from EmulationStation Tools

After install, this Tools entry is created:

- `RAOfflineProxy Menu`

Current on-device feedback behavior:

- `RAOfflineProxy Menu` is the primary interactive KNULLI UI for Start, Stop, Cached Games, Uninstall, and Exit
- `RAOfflineProxy Menu` now uses the SDL/`pygame` menu path for responsive controller navigation
- `RAOfflineProxy Install` still displays a short framebuffer feedback screen during installation

Current SDL menu capabilities:

- `Enable autostart` / `Disable autostart` is available at the root when the platform supports startup hooks
- `Cached Games` lists currently cached games from local `patch:*` cache entries
- `Add ROM` opens a fullscreen file browser rooted from the platform's RetroArch ROM directory resolution
- `Add ROM` caches the same relevant RA data as launching a game online through the proxy: `gameid`, `patch`, `unlocks`, and a synthetic `startsession`
- selecting a cached game opens a per-game action menu
- `Remove cache` removes the selected game's `patch`, `unlocks`, and `startsession` entries
- `Clear cache` removes game-related cache entries while preserving cached login and User-Agent data
- cached games can show their game image in the top-right of the screen when patch metadata provides one

Current KNULLI persistence behavior:

- the launcher now forces a stable config/cache location under `/userdata/system/.config/raofflineproxy`
- this keeps online-cached login/game data available to both interactive launches and autostarted services after reboot
- autostart uses `/userdata/system/custom.sh` on KNULLI/Batocera-style systems

Current authentication behavior:

- KNULLI stores RetroAchievements username/password in RetroArch config, not a reusable API token
- RAOfflineProxy reads those values, performs a normal `login2` request once, and stores the returned token in its local cache
- after that, game caching, queued award flushing, and background refresh use the cached token
- `cheevos_password` is never treated as the API token

Current cache parity behavior:

- starting a game while online and manually adding a ROM from `Cached Games` write compatible cache entries
- both paths persist `gameid`, `patch`, and `unlocks` responses locally
- `startsession` is never cached from upstream; RAOfflineProxy builds a local synthetic `startsession` from cached unlocks for offline launches
- manual ROM caching stores available hash aliases so RetroArch's later offline `gameid` request can hit the same cached game mapping

Current install UX:

- after running `RAOfflineProxy Install`, the installer asks the user to `Please Update Gamelists.`
- the installer removes itself after a successful install
- if the proxy was already running before install, the installer now restarts it automatically so the updated service code is active immediately
- if the proxy was stopped before install, the installer leaves it stopped
- the Tools list is expected to expose only `RAOfflineProxy Menu`
- uninstall stops the proxy, disables autostart, and removes RAOfflineProxy app/config/cache data locations on KNULLI, including legacy RAOfflineProxy-owned paths from older builds

What is still rough:

- installation and update flow are still experimental
- KNULLI integration is still being hardened and simplified
- visual presentation is still being tuned for device-specific KNULLI behavior
- the SDL menu depends on `pygame` being available on-device; the launcher automatically adds `/userdata/roms/pygame` to `PYTHONPATH` when present
- clearing cache while the proxy is actively running does not stop the service first, so live requests can repopulate game cache entries again
- autostart is currently implemented for KNULLI/Batocera-style startup hooks, not every Linux environment
- Android remains the only fully documented and supported target

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

Treat Linux support as a development preview rather than an officially supported feature for now.
