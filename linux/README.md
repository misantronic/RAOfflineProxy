# RAOfflineProxy Linux

Python-based Linux support for `RAOfflineProxy` lives in this directory.

## Status

This Linux implementation is currently **experimental**, but the core KNULLI flow is now working end to end in testing:

- online RetroAchievements traffic is intercepted and forwarded
- cached game data can be served while offline
- offline softcore achievements can be queued locally
- queued achievements can be flushed successfully on reconnect
- a simple `RAOfflineProxy Status` snapshot can be generated for KNULLI-style environments

It is still not considered a stable public target yet. Android remains the primary supported platform.

`start-proxy` now does two things:

- patches `retroarch.cfg`
- starts a background local proxy service

The config patch currently forces these RetroArch settings while active:

- `cheevos_enable = "true"`
- `cheevos_custom_host = "<proxy_host>:<proxy_port>"`
- `cheevos_hardcore_mode_enable = "false"`

While the Linux proxy service is running, it also re-enforces those values periodically in case the host OS or frontend rewrites `retroarch.cfg` during network changes.

On Batocera/KNULLI, the Linux client also patches `batocera.conf` because Batocera regenerates RetroArch config on every emulator launch.

The Batocera integration uses supported `batocera.conf` keys such as:

- `global.retroachievements=1`
- `global.retroachievements.hardcore=0`
- `global.retroarch.cheevos_enable="true"`
- `global.retroarch.cheevos_custom_host="<proxy_host>:<proxy_port>"`
- `global.retroarch.cheevos_hardcore_mode_enable="false"`

The background service:

- intercepts RetroAchievements API requests on the configured local port
- caches successful `/dorequest.php` responses except live upstream `startsession`
- bypasses upstream requests while online
- serves cached responses while offline where possible
- queues softcore award requests while offline or when upstream is unreachable
- flushes queued awards when connectivity returns

Authentication on KNULLI/Batocera-style systems is bootstrapped from RetroArch config:

- RetroArch/KNULLI normally stores `cheevos_username` and `cheevos_password`, not a reusable RA API token
- RAOfflineProxy reads those values, performs one `login2` request, and stores the returned RA token as a local `login2::<user>` cache entry
- subsequent manual cache, refresh, and award flush requests use the cached token
- `cheevos_password` is not used as the token for `patch`, `unlocks`, or award requests

Game data cache behavior intentionally mirrors the Android client:

- launching a game online through the proxy and manually adding a ROM from the SDL menu produce compatible local cache data
- both paths persist `gameid:<hash>`, `patch:<gameId>:<user>`, and `unlocks:<gameId>:<user>:0`
- upstream `startsession` responses are not cached
- offline `startsession` is synthesized locally from cached unlocks and stored as `startsession:<gameId>:<user>:0`
- manual ROM caching stores available hash aliases for the same ROM so RetroArch's later offline `gameid` request can resolve the cached game consistently

The reconnect award flush now mirrors the Android client more closely:

- backdated awards include the `o` parameter clamped to 14 days
- the award validation hash `v` is recalculated during replay
- replay requests use the original RetroArch User-Agent with a Linux-specific proxy suffix:
  - `RAOfflineProxy/Linux/1.0.0-alpha1`
- tamper-chain metadata is attached to queued award replays

On KNULLI/Batocera-style installs, the launcher also forces a stable config/cache location under:

```text
/userdata/system/.config/raofflineproxy
```

This avoids cache mismatches between interactive menu launches, autostart, and SSH sessions after reboot.

`stop-proxy` stops the service first, then reverts the RetroArch config patch.

## Layout

```text
linux/
  raofflineproxy/
    main.py
    config.py
    retroarch_cfg.py
    state.py
  knulli/
    build_bundle.sh
    README.md
```

## Install

### Generic Linux

No separate wheel or system package is required yet. Run it directly from this repo.

From repo root:

```bash
cd path/to/RAOfflineProxy/linux
python3 -m raofflineproxy.main status
```

If you want a user-local launcher:

```bash
mkdir -p ~/.local/bin
cat > ~/.local/bin/raofflineproxy <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

cd /path/to/RAOfflineProxy/linux
exec python3 -m raofflineproxy.main "$@"
EOF
chmod +x ~/.local/bin/raofflineproxy
```

Then use:

```bash
raofflineproxy status
raofflineproxy start-proxy
raofflineproxy stop-proxy
raofflineproxy ui
```

### KNULLI

Build the portable bundle and single-file installer:

```bash
cd path/to/RAOfflineProxy
./linux/knulli/build_bundle.sh
```

This creates:

```text
path/to/RAOfflineProxy/linux/knulli/dist/RAOfflineProxy Install.sh
```

Recommended install flow:

```bash
cp "path/to/RAOfflineProxy/linux/knulli/dist/RAOfflineProxy Install.sh" /path/to/mounted/knulli/share/roms/tools/
```

Then run `RAOfflineProxy Install` from EmulationStation Tools.

The installer removes itself after a successful install and creates this Tools entry:

- `RAOfflineProxy`

`RAOfflineProxy` now launches the SDL/`pygame` menu path. The KNULLI launcher automatically adds `/userdata/roms/pygame` to `PYTHONPATH` when that directory exists on-device.

On upgrade installs, the KNULLI installer now preserves prior proxy running state:

- if the proxy was running before install, the installer stops it before replacing files
- after install, it starts the proxy again so the running service loads the updated code
- if the proxy was not running before install, it remains stopped

On KNULLI/Batocera-style installs, the launcher also exports:

- `HOME=/userdata/system`
- `XDG_CONFIG_HOME=/userdata/system/.config`

## Config

Optional config file:

```text
~/.config/raofflineproxy/config.json
```

On KNULLI/Batocera-style systems, the effective config path is typically:

```text
/userdata/system/.config/raofflineproxy/config.json
```

Supported keys:

```json
{
  "proxy_host": "127.0.0.1",
  "proxy_port": 8080,
  "retroarch_cfg": "/home/user/.config/retroarch/retroarch.cfg",
  "upstream_host": "https://retroachievements.org"
}
```

Default detection order for `retroarch.cfg`:

1. `RAOFFLINEPROXY_RETROARCH_CFG` environment override
2. `/userdata/system/configs/retroarch/retroarchcustom.cfg`
3. `/userdata/system/configs/retroarch/retroarch.cfg`
4. `/userdata/system/.config/retroarch/retroarchcustom.cfg`
5. `/userdata/system/.config/retroarch/retroarch.cfg`
6. `/storage/.config/retroarch/retroarch.cfg`
7. `~/.config/retroarch/retroarch.cfg`

Saved patch state is stored in:

```text
~/.config/raofflineproxy/retroarch_patch_state.json
```

Service files are stored in:

```text
~/.config/raofflineproxy/proxy.sqlite3
~/.config/raofflineproxy/service.pid
~/.config/raofflineproxy/service.log
~/.config/raofflineproxy/service_status.json
~/.config/raofflineproxy/menu-sdl.log
~/.config/raofflineproxy/ui-state.txt
```

On KNULLI/Batocera-style systems, these files are normally stored under:

```text
/userdata/system/.config/raofflineproxy/
```

If Python includes `sqlite3`, cache and award data are stored in `proxy.sqlite3`.

On minimal Python builds without `sqlite3` support, such as some KNULLI images, the client automatically falls back to:

```text
~/.config/raofflineproxy/proxy.json
```

## Usage

From repo root:

```bash
python3 -m linux.raofflineproxy.main status
python3 -m linux.raofflineproxy.main start-proxy
python3 -m linux.raofflineproxy.main stop-proxy
python3 -m linux.raofflineproxy.main ui
```

Or from inside `linux/`:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main status
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy
PYTHONPATH=. python3 -m raofflineproxy.main stop-proxy
PYTHONPATH=. python3 -m raofflineproxy.main ui
```

You can also override the target cfg path directly:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy --retroarch-cfg /path/to/retroarch.cfg
```

## Status Output

`status` reports both config patch state and daemon state, including whether the service is running and its PID.

## RAOfflineProxy

The experimental `menu` command provides a single-entry fullscreen menu for KNULLI-style environments.

It is intended to allow controller-driven access to:

- Start proxy
- Stop proxy
- Enable autostart / Disable autostart
- Cached games
- Uninstall
- Exit Menu

The KNULLI Tools installer exposes this as `RAOfflineProxy`.

The current primary implementation is the SDL menu. The older `fbv` menu code remains in the repo as a fallback implementation, but it is no longer the default KNULLI path.

### Cached Games

The SDL menu now includes a `Cached games` flow with:

- `Add ROM`
- list of cached games derived from local `patch:*` entries
- `Clear cache`
- `Back`

`Add ROM` performs the same data preparation used by online game launch caching: it identifies the ROM, stores the game lookup response, fetches patch metadata, fetches softcore unlock state, and builds a local synthetic session response for offline startup.

Selecting a cached game opens a per-game action menu with:

- `Remove cache`
- `Back`

`Remove cache` deletes the selected game's:

- `patch:*`
- `unlocks:*`
- `startsession:*`

`Clear cache` removes all game-related cache entries while preserving cached login and User-Agent data.

### Autostart

The SDL root menu can expose:

- `Enable autostart`
- `Disable autostart`

This is implemented through the platform abstraction.

On KNULLI/Batocera-style systems, enabling autostart currently manages a block inside:

```text
/userdata/system/custom.sh
```

That startup block runs:

```text
/userdata/system/raofflineproxy/bin/raofflineproxy start-proxy
```

CLI equivalents are also available:

- `enable-autostart`
- `disable-autostart`
- `autostart-status`

### Uninstall Behavior

On KNULLI/Batocera-style installs, uninstall now performs a full cleanup:

- stops the proxy
- disables autostart
- removes the Tools entry
- removes the installed app bundle
- removes the persistent config/cache directory
- removes legacy root-home config/cache data from older builds when present

That means cached login data, local cache entries, queued awards, logs, preview images, and related RAOfflineProxy state are deleted during uninstall.

The `Add ROM` file browser currently supports manual caching for these ROM families:

- Game Boy / Game Boy Color / Game Boy Advance
- NES / FDS / SNES
- PC Engine
- Atari 7800 / Atari Lynx
- Super Cassette Vision
- Nintendo 64
- Nintendo DS
- PSP
- PSX

The browser resolves its initial ROM root through a small platform abstraction so Linux targets can provide their own RetroArch/ROM directory discovery logic later.

When patch metadata includes an image path, the selected cached game can show a preview image in the top-right of the SDL menu. Preview images are cached under:

```text
~/.config/raofflineproxy/game-previews/
```

## Current Limitations

- hardcore mode remains unsupported and hardcore awards are rejected
- the Linux version uses a local SQLite database instead of the Android Room schema
- award signing uses a local HMAC secret for tamper-evidence instead of the Android Keystore-backed ECDSA key
- the KNULLI/Batocera flow is still experimental and may require additional refinement across firmware versions
