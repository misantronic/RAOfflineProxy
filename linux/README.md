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
- caches successful `patch`, `gameid`, `achievements`, `hashlibrary`, `login2`, and `unlocks` responses
- bypasses upstream requests while online
- serves cached responses while offline where possible
- queues softcore award requests while offline or when upstream is unreachable
- flushes queued awards when connectivity returns

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
path/to/RAOfflineProxy/linux/knulli/dist/raofflineproxy-knulli-bundle.tar.gz
```

Recommended install flow:

```bash
cp "path/to/RAOfflineProxy/linux/knulli/dist/RAOfflineProxy Install.sh" /path/to/mounted/knulli/share/roms/tools/
```

Then run `RAOfflineProxy Install` from EmulationStation Tools.

The installer removes itself after a successful install and creates this Tools entry:

- `RAOfflineProxy Menu`

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

## RAOfflineProxy Menu

The experimental `menu` command provides a single-entry fullscreen menu for KNULLI-style environments.

It is intended to allow controller-driven access to:

- Start proxy
- Stop proxy
- Uninstall
- Exit

The KNULLI Tools installer exposes this as `RAOfflineProxy Menu`.

## Current Limitations

- hardcore mode remains unsupported and hardcore awards are rejected
- the Linux version uses a local SQLite database instead of the Android Room schema
- award signing uses a local HMAC secret for tamper-evidence instead of the Android Keystore-backed ECDSA key
- the KNULLI/Batocera flow is still experimental and may require additional refinement across firmware versions
