# RAOfflineProxy Linux

Python-based Linux support for `RAOfflineProxy` lives in this directory.

## Status

This Linux implementation is currently **alpha**.

The core Linux flow already supports:

- forwarding RetroAchievements traffic while online
- serving cached game data while offline
- queueing casual achievements while offline
- flushing queued achievements after reconnect
- caching games from normal RetroArch launches
- manual ROM adding for supported systems

It is not considered a stable public target yet.

`start-proxy` does two things:

- patches `retroarch.cfg`
- starts a background local proxy service

The config patch forces these RetroArch settings while active:

- `cheevos_enable = "true"`
- `cheevos_custom_host = "<proxy_host>:<proxy_port>"`
- `cheevos_hardcore_mode_enable = "false"`

While the Linux proxy service is running, it also re-enforces those values periodically in case the host OS or frontend rewrites `retroarch.cfg`.

The background service:

- intercepts RetroAchievements API requests on the configured local port
- caches successful `/dorequest.php` responses except live upstream `startsession`
- serves cached responses while offline where possible
- queues casual award requests while offline or when upstream is unreachable
- flushes queued awards when connectivity returns

Authentication is token-first:

- if `cheevos_token` exists in `retroarch.cfg`, RAOfflineProxy uses it directly
- if no token exists, `cheevos_username` and `cheevos_password` are used once with `login2` to retrieve and cache a token
- `cheevos_password` is never used as the token for normal API requests

Game data cache behavior intentionally mirrors the Android client:

- launching a game online through the proxy and manually adding a ROM produce compatible local cache data
- both paths persist `gameid:<hash>`, `patch:<gameId>:<user>`, and `unlocks:<gameId>:<user>:0`
- upstream `startsession` responses are not cached
- offline `startsession` is synthesized locally from cached unlocks and stored as `startsession:<gameId>:<user>:0`

The reconnect award flush also mirrors the Android client more closely:

- backdated awards include the `o` parameter clamped to 14 days
- the award validation hash `v` is recalculated during replay
- replay requests use the original RetroArch User-Agent with a Linux-specific proxy suffix
- tamper-chain metadata is attached to queued award replays

`stop-proxy` stops the service first, then reverts the RetroArch config patch.

## Layout

```text
linux/
  raofflineproxy/
    main.py
    config.py
    retroarch_cfg.py
    state.py
```

## Install

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
raofflineproxy menu
```

## Config

Optional config file:

```text
~/.config/raofflineproxy/config.json
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

On KNULLI/Batocera, `retroarchcustom.cfg` is generated at the first libretro
launch, so none of candidates 2-5 exist on a freshly flashed device. Detection
then keeps candidate 2 as the path, and starting the proxy skips the missing
file instead of failing: `knulli.conf`/`batocera.conf` carries the host override
on its own, and configgen rebuilds the cfg from it at every launch.

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
```

If Python includes `sqlite3`, cache and award data are stored in `proxy.sqlite3`.

On minimal Python builds without `sqlite3` support, the client automatically falls back to:

```text
~/.config/raofflineproxy/proxy.json
```

## Usage

From repo root:

```bash
python3 -m linux.raofflineproxy.main status
python3 -m linux.raofflineproxy.main start-proxy
python3 -m linux.raofflineproxy.main stop-proxy
python3 -m linux.raofflineproxy.main menu
```

Or from inside `linux/`:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main status
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy
PYTHONPATH=. python3 -m raofflineproxy.main stop-proxy
PYTHONPATH=. python3 -m raofflineproxy.main menu
```

You can also override the target cfg path directly:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy --retroarch-cfg /path/to/retroarch.cfg
```

## Status Output

`status` reports both config patch state and daemon state, including whether the service is running and its PID.

## UI

The `menu` command provides a fullscreen controller-driven menu for Linux targets.

The menu currently supports:

- Start proxy
- Stop proxy
- Enable autostart / Disable autostart
- Cached games
- Pending awards
- Uninstall
- Exit Menu

### Cached Games

The menu includes a `Cached games` flow with:

- `Add ROM`
- list of cached games derived from local `patch:*` entries
- `Clear cache`
- `Back`

`Add ROM` identifies the ROM, stores the game lookup response, fetches patch metadata, fetches casual unlock state, and builds a local synthetic session response for offline startup.

Selecting a cached game opens a per-game action menu with:

- `Remove cache`
- unlocked achievement titles
- `Back`

`Remove cache` deletes the selected game's:

- `patch:*`
- `unlocks:*`
- `startsession:*`

`Clear cache` removes all game-related cache entries while preserving cached login and User-Agent data.

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

When patch metadata includes an image path, the selected cached game can show a preview image in the menu.

When unlock badge data is cached, the selected unlocked achievement can also show its badge beside the game preview.

## Current Limitations

- hardcore mode remains unsupported and hardcore awards are rejected
- the Linux version uses its own local storage layer instead of the Android Room schema, with SQLite when available and JSON as a fallback
- award signing uses a local HMAC secret for tamper-evidence instead of the Android Keystore-backed ECDSA key
- the Linux flow is still alpha-quality and may require additional refinement across devices and environments
