# ROCKNIX Bundle

This directory contains a portable ROCKNIX bundle for the Linux `RAOfflineProxy` client.

## Current State

The alpha ROCKNIX flow has been verified end to end on real hardware (SM8550, aarch64, ROCKNIX `next`):

- Install patches the required RetroArch config
- RetroAchievements requests are intercepted locally by the proxy service
- the controller-driven **SDL menu runs fullscreen** under the sway compositor
- Start / Stop / Enable-autostart / Disable-autostart / Uninstall all work
- `retroarch.cfg` is correctly patched and reverted (`cheevos_custom_host`, `cheevos_hardcore_mode_enable`)

## What It Does

- installs the Python app under `/storage/.local/share/raofflineproxy` (`app/`, `bin/`, `lib/`)
- bundles a self-contained `pygame` (see below) alongside the app
- adds an EmulationStation **Tools** entry: `/storage/.config/modules/RAOfflineProxy.sh`

`RAOfflineProxy` is the primary interactive fullscreen UI (Start, Stop, Autostart, Cached Games, Pending Awards, Uninstall, Exit), identical to the KNULLI/muOS SDL menu.

## Emulator Support

| Emulator | Status | Notes |
|---|---|---|
| RetroArch | Supported | `cheevos_custom_host` patched in `retroarch.cfg` |
| PPSSPP | Supported | `AchievementsHost` patched in `ppsspp.ini` |
| AetherSX2 (PS2, default core) | **Not supported** | See below |
| flycast (standalone) | **Not supported** | See below |
| flycast (RetroArch core) | Supported | goes through `retroarch.cfg`, same as any other libretro core |
| DuckStation (standalone) | **Not supported** | See below |
| DuckStation (RetroArch core) | Supported | goes through `retroarch.cfg`, same as any other libretro core |

### Why AetherSX2 is unsupported

ROCKNIX's `ps2` system ships two selectable cores: `aethersx2-sa` (the default, and the only
one documented on rocknix.org) and `pcsx2-sa`. Only `pcsx2-sa` can be host-overridden.

`aethersx2-sa`'s binary has no reachable code path for a custom RetroAchievements host:

- No `Achievements/Host`-style config key is read anywhere in the binary (confirmed via
  `strings` on `/usr/share/aethersx2-sa/aethersx2` — no `"Using custom host"` log line exists,
  unlike the `pcsx2-sa` binary which has it).
- The RA request URL (`https://retroachievements.org/dorequest.php`) is a hardcoded literal.
  `cheevos_aethersx2.sh` never writes a `Host` key, consistent with the binary never reading one.
- This is an older PCSX2 fork predating upstream's host-override support in `Achievements.cpp`
  (`Host::GetBaseStringSettingValue("Achievements", "Host", "")` → `rc_client_set_host()`).

Unlike the Dolphin case (fixed upstream via
[ROCKNIX/distribution#3038](https://github.com/ROCKNIX/distribution/pull/3038), where the
binary already supported a custom host and only the launch script needed a 2-line fix),
AetherSX2's binary itself lacks the feature entirely. Supporting it would require porting the
host-override code from upstream PCSX2 into ROCKNIX's AetherSX2 fork and rebuilding the
binary — a real code change to the emulator, not a client-side or launch-script fix.

The `pcsx2-sa` alt-core's binary does support this, and its `cheevos_pcsx2.sh` does targeted
`sed` edits (not a full config regen), so it's a viable client-side integration target for a
future release.

### Why standalone flycast is unsupported

`/usr/bin/flycast`'s `[achievements]` section in `/storage/.config/flycast/emu.cfg` only has
`Enabled`, `HardcoreMode`, `UserName`, and `Token` keys — `cheevos_flycast.sh` writes exactly
those and nothing else. Confirmed via `strings` on the binary: no `Host`/`HostUrl`-style config
key is read anywhere, and every RA request URL
(`https://retroachievements.org/dorequest.php`, `http://media.retroachievements.org`, ...) is a
hardcoded literal reachable from `achievements.cpp`. Unlike PPSSPP or RetroArch, there's no
config hook to redirect at all — supporting it would mean porting host-override support into
ROCKNIX's flycast fork and rebuilding the binary, the same class of work as the AetherSX2 case
above.

flycast is also available as a RetroArch core (`flycast_libretro.so` /
`flycast2021_libretro.so`), which already works through the existing `retroarch.cfg` patching —
that's the supported path for offline Dreamcast achievements on ROCKNIX today.

### Why standalone DuckStation is unsupported

Unlike AetherSX2 and flycast, DuckStation's binary genuinely has full achievements support,
including what looks like a `Host` override — `/usr/bin/duckstation-sa` is an AppImage-style
self-extracting stub (an ELF wrapper with a compressed squashfs payload appended), and the real
`duckstation-qt` binary inside it is unstripped with debug info. It contains `Cheevos`, `Host`,
`Username`, `Token`, `ChallengeMode`, `LoginTimestamp`, `RichPresencePatch`, and a
`Using host: %s` log format string. On-device, `/var/log/exec.log` shows achievements working
live end to end (`RA Login successful`, `RA: game N loaded ... achievements 1 leaderboards 1`)
once a user logs in through DuckStation's own in-game menu.

The blocker is the automatic login path, not the achievements engine. ROCKNIX's own
`/usr/bin/start_duckstation.sh` unconditionally forces `Enabled = false` in the `[Cheevos]`
section before every launch, and has `/usr/bin/cheevos_duckstation.sh` (the script that would
inject the RA username/token from Emulation Station into DuckStation's ini, the same way
`cheevos_ppsspp.sh`/`cheevos_pcsx2.sh` do) commented out entirely, with the comment
`Disabled, not working. Seems like Duckstation changed the token encryption...`. Without that
injection working, RAOfflineProxy has no way to get the emulator logged in and pointed at the
proxy automatically — the user would have to manually re-enter their RA username/password
inside DuckStation's own menu on every session, which isn't a viable integration for this
project. Revisit this once ROCKNIX's launch script (or an upstream DuckStation change) fixes the
token injection.

DuckStation is also available as a RetroArch core (`duckstation_libretro.so`), which already
works through the existing `retroarch.cfg` patching — that's the supported path for offline
PS1 achievements on ROCKNIX today.

## Platform Specifics

Confirmed on-device (ROCKNIX `next`, `OS_NAME="ROCKNIX"` in `/etc/os-release`):

- `retroarch.cfg` lives at `/storage/.config/retroarch/retroarch.cfg`
- There is no `batocera.conf`/`knulli.conf` equivalent — only `retroarch.cfg` is patched
- Add-on apps install under `/storage/.local/share/` (Heroic, Steam, m8c, and this app)
- The **Tools** system runs `.sh` scripts from `/storage/.config/modules/` inside a `foot`
  terminal via `/usr/bin/foot %ROM%`. The launcher `source`s `/etc/profile`, calls
  `set_kill` (so the frontend kill-combo works), then execs the SDL menu. The menu requests
  SDL fullscreen itself; sway honors it (verified `fullscreen_mode=1`), so no `sway_fullscreen`
  call is needed.
- Autostart drops an executable script into `/storage/.config/autostart/`; ROCKNIX's
  `/usr/bin/autostart` runs every script there at boot (no separate registration step)

### pygame

ROCKNIX ships Python 3.13 but **no `pip` and no `pygame`**. The bundle vendors a self-contained
`pygame` 2.6.1 cp313 aarch64 manylinux wheel (its own SDL 2.28.4 in `pygame.libs`, which includes
the wayland video driver — no reliance on system SDL). This is verified working under sway on
Python 3.13.5.

The vendored payload is **not** checked in (see `.gitignore`). Populate it before building:

```bash
# from a cp313 aarch64 manylinux pygame wheel
cd linux/rocknix/vendor
unzip pygame-2.6.1-cp313-cp313-manylinux_2_17_aarch64.manylinux2014_aarch64.whl
# leaves vendor/pygame and vendor/pygame.libs in place
```

> Note: the vendored `.so` files are cp313-specific. If ROCKNIX moves to a different Python
> minor version, re-vendor a matching wheel.

## Build

From repo root (requires `vendor/pygame` + `vendor/pygame.libs`, see above):

```bash
./linux/rocknix/build_bundle.sh
```

This creates:

- `linux/rocknix/dist/RAOfflineProxy-Rocknix-v1.5.5-alpha1-Install.sh`

## Install On ROCKNIX

Copy the installer to the device and run it (over SSH, or a terminal on-device):

```bash
scp linux/rocknix/dist/RAOfflineProxy-Rocknix-v1.5.5-alpha1-Install.sh root@<device-ip>:/tmp/
ssh root@<device-ip> "chmod +x /tmp/RAOfflineProxy-Rocknix-v1.5.5-alpha1-Install.sh && /tmp/RAOfflineProxy-Rocknix-v1.5.5-alpha1-Install.sh"
```

The installer extracts to `/storage/.local/share/.raofflineproxy-rocknix-bundle`, installs the
live bundle to `/storage/.local/share/raofflineproxy`, adds the Tools entry, and removes itself.
Update gamelists so the new Tools entry appears.

When updating an existing install, the installer preserves prior proxy running state: if the
proxy was running, it is stopped before files are replaced and restarted afterward.

## Uninstall

Use `Uninstall` inside the menu, or run:

```bash
/storage/.local/share/raofflineproxy/bin/raofflineproxy-uninstall
```

This stops the proxy, reverts `retroarch.cfg`, removes the autostart hook, removes the Tools
entry, and removes the installed bundle and config/cache directory (`/storage/.config/raofflineproxy`).
