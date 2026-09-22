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

spruce has no drop-in boot directory: one script is the entire boot entry point, and it
ends by dispatching into a per-device startup script that never returns.

Which script that is depends on what boots the board, and `spruce_startup_script()` picks
it. Most hardware comes up through `.tmp_update/updater`. The Anbernic H700 line runs under
BaseOS, which execs `.system/h700/paks/MinUI.pak/launch.sh` and reaches
`.tmp_update/anbernic.sh`; the RGB30 comes up under MossySpruce through
`.tmp_update/rgb30.sh`. Neither of those reads `updater` at all, so a hook placed there is
installed, reported as enabled, and never runs.

The hook goes into every one of those files present on the card, not just the current
device's. One spruce card boots many devices, and with a single copy, moving a card from a
Miyoo Mini to an RG40XX left autostart dead there, while opening the app on the RG40XX
moved the hook and broke the Mini. Each file only runs on its own device family, so the
copies a device never executes are inert. Removing the hook strips all of them.
`install_spruce_boot_hook()` prepends a sentinel-guarded block straight after the
shebang — not appended, and deliberately not anchored on any device-specific line, so it
holds whichever of those three files it lands in. The block backgrounds `autostart-launch.sh` and is
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

On `arm64` the bundled SDL2 is the stock manylinux build, which speaks only x11, wayland,
offscreen and dummy. Boards with a framebuffer and no compositor therefore have no usable
video driver at all, and the menu renders to nothing while the proxy itself runs fine.

spruce solves this for the Anbernic H700 line by staging a mali-fbdev SDL2 next to PyUI
(`App/PyUI/dll-mali`, documented in that directory's `PROVENANCE.md`). Both it and the
bundled build are SDL 2.28.x, so `select_sdl_video_driver()` preloads spruce's copy for the
menu process and selects `SDL_VIDEODRIVER=mali`. The preload is deliberately not exported:
the proxy has no use for SDL, and it would otherwise follow every emulator the app
launches. `SDL_JOYSTICK_DISABLE_UDEV=1` goes with it, because these boards run neither
udev nor mdev and SDL's joystick layer blocks on udev during `SDL_Init`.

Which SDL2 gets preloaded comes from `PYSDL2_DLL_PATH` when spruce sets it: that is the
variable `App/PyUI/launch.sh` points at the SDL2 each device's own UI uses, so honouring it
covers devices this bundle has never been run on. It is not always a complete SDL2, on
TrimUI it names `spruce/brick/sdl2`, which carries only `SDL2_image` while the core comes
from the firmware, so the per-device paths remain the fallback and a directory with no core
library simply does not match. spruce exports it from PyUI rather than globally, so an
autostarted proxy never sees it, which is a second reason the fallback stays. MiyooMini
ignores it: the menu there is built against the vendored "Mini" SDL2 that ships with the
bundle.

A preloaded SDL2 also needs the directory it came from on `LD_LIBRARY_PATH`, which is what
`menu_library_path()` adds for the menu and the driver probe. Its own `NEEDED` libraries
live beside it and nowhere else: the mali build links `libsamplerate.so.0`, shipped only in
`dll-mali`. Without it the preload resolves only when spruce's own launcher happens to have
exported that directory first, so the app starts from the device but fails from a terminal
with `libsamplerate.so.0: cannot open shared object file`. Like the preload, the directory
is kept off the exported path, because `dll-mali` carries its own libpng, libtiff and webp
that would otherwise shadow the bundled ones for the proxy and anything it launches.

Verified on an RG40XX-H: `mali` yields a real 640x480 fullscreen surface.

The `Brick` is still open. The proxy is confirmed working there, the menu is not, and the
same shape of fix probably applies with `spruce/brick/sdl2` in place of `dll-mali`. When
the menu fails, `launch.sh` writes an SDL report to `data/menu-sdl.log`: it asks the SDL
that pygame actually loaded which drivers it was built with, then tries each one. A
hardcoded guess list was there before and reported every driver as unavailable on the
RG40XX-H, which hid the fact that the bundled SDL2 was simply the wrong build for the
board.

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
