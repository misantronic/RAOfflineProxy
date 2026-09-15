# RAOfflineProxy — spruceOS

spruceOS bundle, derived from the Onion one. Support is **experimental**.

Both firmwares use the same `/mnt/SDCARD/App/<name>/` layout, so the armv7 bundle reuses
Onion's CPython runtime, its pygame + `Mini` SDL2 vendor libraries and its armv7
`libraproxy_rchash.so`. spruce also runs on aarch64 hardware, which Onion does not, so a
second bundle pairs its own CPython 3.11 with muOS's cp311 pygame and SDL2.

## What differs from Onion

| | Onion | spruce |
| --- | --- | --- |
| RetroArch config | `/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg` | `/mnt/SDCARD/RetroArch/platform/retroarch-<device>.cfg` |
| RA credentials | the RetroArch config | `/mnt/SDCARD/Saves/spruce/spruce-config.json` |
| Default proxy port | 8080 | 8099 |
| Autostart | `/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh` | block prepended to `/mnt/SDCARD/.tmp_update/updater` |
| Version gate | requires Onion v4.4.0+ | none |

spruce launches RetroArch with `--config` pointing at the per-device file
(`spruce/scripts/emu/lib/ra_functions.sh`), so its `.retroarch/retroarch.cfg` is never
read. `common.sh` resolves the device the same way spruce's own `helperFunctions.sh` does
and exports the matching path as `RAOFFLINEPROXY_RETROARCH_CFG`.

## Autostart

spruce has no drop-in boot directory: `.tmp_update/updater` is the entire boot entry
point, and it ends by dispatching into a per-device startup script that never returns.
`install_spruce_boot_hook()` therefore prepends a sentinel-guarded block straight after
the shebang — not appended, and deliberately not anchored on any device-specific line, so
it holds on every spruce device. The block backgrounds `autostart-launch.sh` and is
wrapped in `[ -x ]`, because this file is the only path to a bootable device.

The updater is destroyed by every spruce update (it is on the updater's own delete list,
while `App/RAOfflineProxy` is not), so `launch.sh` reinstalls the hook on each app launch
— the same self-repair pattern ROCKNIX needs. Verified on a Miyoo Mini Plus running
spruce 4.3.4: after a reboot the service came up on its own, bound its port, and the hook
survived.

No OS version gate: spruce 4.3.x ships RetroArch 1.22.2, whose achievements client
handles `cheevos_custom_host` correctly. That gate exists for Onion because OnionOS
v4.3.1-1 shipped an older RetroArch.

## Build

```
./linux/onion/fetch_runtime.sh   # once
./linux/onion/fetch_vendor.sh    # once
./linux/spruce/build_bundle.sh
```

`build_bundle.sh` takes an architecture and defaults to `armv7`:

```sh
./linux/spruce/fetch_runtime_arm64.sh      # once, for the arm64 target
./linux/spruce/build_bundle.sh armv7
./linux/spruce/build_bundle.sh arm64
```

Produces `linux/spruce/dist/RAOfflineProxy-Spruce-v<VER>.zip` and
`RAOfflineProxy-Spruce-arm64-v<VER>.zip`, extracted over the SD card root so the app lands
in `/mnt/SDCARD/App/RAOfflineProxy`.

## Hardware coverage

Each bundle carries its own runtime, native lib and SDL2, so the two are not
interchangeable. `detect_spruce_platform()` in `common.sh` is the authoritative list:

| Bundle | spruce targets |
| --- | --- |
| `armv7` | `MiyooMini` (Mini, Mini Plus, Mini Flip), `A30` |
| `arm64` | `Brick`, `BrickPro`, `SmartPro`, `SmartProS`, `Flip`, `Miniloong`, `RGB30`, `Pixel2`, `Zero28`, and the H700 Anbernic line (`AnbernicXX640480`, `AnbernicXX640480NoStick`, `AnbernicXX640480OneStick`, `AnbernicXX720480`, `AnbernicXX720480NoStick`, `AnbernicRG28XX`, `AnbernicRGCubeXX`) |

The platform name is not cosmetic: it selects `RetroArch/platform/retroarch-<name>.cfg`,
and spruce ships one config per panel and pad layout rather than one per SoC. The H700
line therefore cannot be collapsed to a single label, and its variant comes from
`BASEOS_TARGET` in `/etc/baseos-release`, exactly as `helperFunctions.sh` reads it. The
RK3566 boards need the same care in the other direction: `Flip`, `Miniloong` and `RGB30`
share a Cortex-A55 part id, so `/etc/os-release` and `/loong/loong_daemon` break the tie.

Only `MiyooMini` is verified (tested on a Mini Plus). The A30 shares the architecture so
the runtime should load, but the vendored SDL2 is steward-fu's Miyoo Mini build: its
`Mini` video driver does not exist there, so `common.sh` leaves `SDL_VIDEODRIVER` unset
and `menu_sdl` falls back to a plain fullscreen surface. Whether that build works on A30
hardware is untested.

On `arm64` the proxy is confirmed working on a `Brick`; the menu is not, because the
vendored manylinux SDL2 has not been matched to that panel. When the menu fails,
`launch.sh` probes the available SDL video drivers into `data/menu-sdl.log`.

A runtime that does not match the hardware is not silently ignored: `resolve_python_bin`
records why each candidate was rejected in `data/runtime-detect.log`, including the
device's `uname -m`. Without that, a wrong-architecture bundle falls through to spruce's
system `python3`, which has no vendored pygame, and the only visible symptom is a
`ModuleNotFoundError` far from the cause.

## Timezone

spruce's PyUI applies the chosen zone by exporting `TZ` into its own environment, so
anything it launches inherits it. The boot hook runs from `.tmp_update/updater` long
before PyUI exists, so an autostarted proxy would stamp every award timestamp in UTC.
`resolve_spruce_timezone()` reads the zone name out of `/mnt/SDCARD/Saves/*-system.json`
(globbed rather than mapped per device) and exports `TZ=":<zoneinfo>/<zone>"`, the same
absolute-path form spruce itself uses. It never overrides a `TZ` that is already set.

## Credentials

spruce stores the RetroAchievements username and password entered in its own settings in
`spruce-config.json`, and only copies them into the RetroArch config when a game launches.
Before the first launch the config's `cheevos_username` is still empty, so
`load_spruce_credentials()` reads that file directly — the same shape as the ROCKNIX
appendconfig case. spruce stores no token, only a password.

## Default port

spruce ships SFTPGo bound to `0.0.0.0:8080` (`spruce/bin/SFTPGo/sftpgo/sftpgo.json`) and
starts it whenever SFTPGo is enabled in Network Settings, so the usual 8080 default can
never bind there. The spruce default is 8099; `proxy_port` in `data/config.json` still
overrides it.

## Achievements mode

spruce rewrites `cheevos_enable`, `cheevos_hardcore_mode_enable`, `cheevos_username` and
`cheevos_password` into the device config on every game launch, from its own
RetroAchievements settings — after our patch has already run, so its mode decides whether
achievements are on at all. `cheevos_custom_host` is not in that list, which is why the
proxy redirect survives on its own.

`spruce_conf.py` therefore patches spruce's `modeToggle` to `Softcore` alongside the
config patching, and restores the previous value on stop. `Disabled` would switch
achievements off, `Hardcore` would enable a mode this app does not support, and `Manual`
leaves the config alone but never writes the account credentials into it.
