# RAOfflineProxy — spruceOS

spruceOS bundle, derived from the Onion one. Both firmwares use the same
`/mnt/SDCARD/App/<name>/` layout and run on the same 32-bit ARM hardware, so this package
reuses Onion's CPython runtime, its pygame + `Mini` SDL2 vendor libraries and its armv7
`libraproxy_rchash.so`.

## What differs from Onion

| | Onion | spruce |
| --- | --- | --- |
| RetroArch config | `/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg` | `/mnt/SDCARD/RetroArch/platform/retroarch-<device>.cfg` |
| Autostart | `/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh` | not supported |
| Version gate | requires Onion v4.4.0+ | none |

spruce launches RetroArch with `--config` pointing at the per-device file
(`spruce/scripts/emu/lib/ra_functions.sh`), so its `.retroarch/retroarch.cfg` is never
read. `common.sh` resolves the device the same way spruce's own `helperFunctions.sh` does
and exports the matching path as `RAOFFLINEPROXY_RETROARCH_CFG`.

spruce's boot entry point (`.tmp_update/updater`) runs `spruce/scripts/runtime.sh`
directly and never sources a startup directory, so there is no drop-in boot hook. The
menu therefore hides the autostart entry and the proxy has to be started from the app
after each boot.

No OS version gate: spruce 4.3.x ships RetroArch 1.22.2, whose achievements client
handles `cheevos_custom_host` correctly. That gate exists for Onion because OnionOS
v4.3.1-1 shipped an older RetroArch.

## Build

```
./linux/onion/fetch_runtime.sh   # once
./linux/onion/fetch_vendor.sh    # once
./linux/spruce/build_bundle.sh
```

Produces `linux/spruce/dist/RAOfflineProxy-Spruce-v<VER>.zip`, extracted over the SD card
root so the app lands in `/mnt/SDCARD/App/RAOfflineProxy`.

## Hardware coverage

The bundled runtime and SDL2 are armv7 builds, so only spruce's `MiyooMini` device target
is expected to work. On other spruce devices the launcher leaves `SDL_VIDEODRIVER` unset
and `menu_sdl` falls back to a plain fullscreen surface; those are aarch64 boards and
would additionally need an aarch64 runtime, which this bundle does not ship.

## In-app requirement

spruce rewrites `cheevos_enable`, `cheevos_hardcore_mode_enable`, `cheevos_username` and
`cheevos_password` into the device config on every game launch, from its own
RetroAchievements settings. It leaves `cheevos_custom_host` alone, so the proxy patch
survives — but the user must have RetroAchievements set to **Softcore** in spruce's
settings, otherwise spruce forces `cheevos_enable = "false"` at launch.
