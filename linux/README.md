# RAOfflineProxy Linux

Early Python-based Linux support for `RAOfflineProxy` lives in this directory.

This first version only handles the RetroArch config patch lifecycle:

- `start-proxy` writes `cheevos_custom_host` to the configured local proxy address
- `start-proxy` forces `cheevos_hardcore_mode_enable = "false"`
- `stop-proxy` restores the previous custom host when a saved patch state exists
- `stop-proxy` restores hardcore mode only when it was enabled before patching

It does not start a Python proxy server yet.

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
  "retroarch_cfg": "/home/user/.config/retroarch/retroarch.cfg"
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

## Usage

From repo root:

```bash
python3 -m linux.raofflineproxy.main status
python3 -m linux.raofflineproxy.main start-proxy
python3 -m linux.raofflineproxy.main stop-proxy
```

Or from inside `linux/`:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main status
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy
PYTHONPATH=. python3 -m raofflineproxy.main stop-proxy
```

You can also override the target cfg path directly:

```bash
PYTHONPATH=. python3 -m raofflineproxy.main start-proxy --retroarch-cfg /path/to/retroarch.cfg
```
