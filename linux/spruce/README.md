# RAOfflineProxy — spruceOS

spruce drives the proxy itself: a toggle in **Settings → RetroAchievements → Offline
Achievements** starts and stops it alongside its other network services, and games are
picked for caching from the game list's options menu. The app ships as a background
service with no UI of its own.

## What differs from Onion

| | Onion | spruce |
| --- | --- | --- |
| RetroArch config | `/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg` | `/mnt/SDCARD/Saves/ra-configs/retroarch-<device>.cfg` |
| RA credentials | the RetroArch config | `/mnt/SDCARD/Saves/spruce/spruce-config.json` |
| Default proxy port | 8080 | 8099 |
| Python | bundled | spruce's, via `get_python_path()` |
| UI | pygame menu | spruce's own |
| Start/stop | autostart hook | spruce's network services |

`RetroArch/platform/` holds only `.cfg.bak` seeds since spruce 4.4.2; the live config is
in `Saves/ra-configs/`.

## The spruce contract

`common.sh` sources `/mnt/SDCARD/spruce/scripts/appEnv.sh` and uses what it exports:

| | |
| --- | --- |
| `SPRUCE_PLATFORM` | device name |
| `SPRUCE_RA_CONFIG` | live RetroArch config, seeded if absent |
| `SPRUCE_PYTHON` | CPython 3.10 |
| `CFW` | `SPRUCE` |
| `SSL_CERT_FILE` | spruce's CA bundle; its python ships none |

Nothing here re-implements spruce's device detection or config paths. `common.sh` refuses
to run if `appEnv.sh` is missing rather than guessing: only Flip, RGB30 and Miniloong can
be told apart by more than `/proc/cpuinfo`, and the Anbernic line has seven platforms
behind one part id.

spruce forces softcore in the RetroArch config for the launch whenever the toggle is on,
so this app does not touch `modeToggle`. PyUI holds `spruce-config.json` in memory and
rewrites it whole on any settings change, so an edit from outside is lost.

## Which spruce commands are used

`start-proxy`, `stop-proxy`, `cache-rom --path <rom>`. spruce sources `common.sh` in a
subshell for the environment, so resolving the interpreter stays this app's business.

## Build

```
./linux/spruce/build_bundle.sh
```

Produces `linux/spruce/dist/RAOfflineProxy-Spruce-v<VER>.zip`, extracted over the SD card
root so the app lands in `/mnt/SDCARD/App/RAOfflineProxy`.

No interpreter, no pygame and no SDL2: `menu_sdl` is the only module that imports pygame
and it does so inside `run_menu_sdl`, so the package runs without it. That leaves the
hashing lib as the only native piece, and both arches of it ship — one bundle covers every
spruce device, armv7 (`MiyooMini`, `A30`) and aarch64 alike.

## Hardware coverage

Verified on a Miyoo Flip: service starts and stops from the toggle, binds its port, and
the RetroArch host is patched and restored. Other devices are untested.

## Timezone

`resolve_spruce_timezone()` reads the zone from `/mnt/SDCARD/Saves/spruce/shared-system.json`
(or the older per-device `Saves/*-system.json`) and exports `TZ=":<zoneinfo>/<zone>"`, the
absolute-path form spruce uses. It never overrides a `TZ` that is already set.

## Credentials

spruce stores the RetroAchievements username and password from its own settings in
`spruce-config.json`, and only copies them into the RetroArch config when a game launches.
Before the first launch `cheevos_username` is still empty, so `load_spruce_credentials()`
reads that file directly. spruce stores no token, only a password.

## Default port

spruce ships SFTPGo bound to `0.0.0.0:8080`, so the usual default can never bind. The
spruce default is 8099; `proxy_port` in `data/config.json` still overrides it.
