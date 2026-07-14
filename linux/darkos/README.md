# dArkOS Bundle

Portable bundle for [dArkOS](https://github.com/christianhaitian/dArkOS) ("Debian based ArkOS").

## Status

Community-contributed: a dArkOS user got RAOfflineProxy running by hand-patching the KNULLI bundle's install/uninstall/launcher scripts (`/userdata/roms` → `/roms`, `/userdata/system` → `/home/ark`). This directory formalizes that into a proper installer, and the shared `raofflineproxy/` Python source now recognizes `/home/ark` as an alternate platform root. **Not yet verified against real hardware by the maintainer** — please report issues.

## Differences from the KNULLI bundle

- Install root: `/home/ark/raofflineproxy` (KNULLI: `/userdata/system/raofflineproxy`)
- ROMs root: `/roms` (KNULLI: `/userdata/roms`); Tools entries live under `/roms/tools`, bind-mounted to `/opt/system/Tools`
- dArkOS is systemd-native and has no `custom.sh`-style boot hook, so autostart installs a systemd unit at `/etc/systemd/system/raofflineproxy-autostart.service` instead. This step needs root — if the installer is launched unprivileged (e.g. from the ES Tools menu, which runs as user `ark`), it skips the autostart unit and prints instructions to re-run with `sudo`. Everything else (proxy start/stop, caching, awards, uninstall) works without root.
- dArkOS ships `apt`, so pygame comes from the system package (`python3-pygame`) rather than a vendored runtime like the KNULLI/muOS bundles use. `install.sh` installs it automatically via `apt-get` when run as root; otherwise it prints `sudo apt-get install -y python3-pygame` and continues.
- No `batocera.conf`/`knulli.conf` equivalent was found in dArkOS, so only the RetroArch-side RA host patch applies; there's no known ES-side achievements-screen override to patch yet.
- In-app "Install Update" now fetches the `*DarkOS*.sh` release asset instead of the KNULLI one.

## Build

From repo root:

```bash
./linux/darkos/build_bundle.sh
```

This creates `linux/darkos/dist/RAOfflineProxy-DarkOS-v<VER>-Install.sh`.

## Install

Copy the installer to `/roms/tools` on the device and run it from the EmulationStation Tools menu, or run it directly over SSH (`sudo` recommended, for autostart support):

```bash
sudo bash "RAOfflineProxy-DarkOS-v<VER>-Install.sh"
```

## Uninstall

Use `Uninstall` inside the RAOfflineProxy menu, or run `/home/ark/raofflineproxy/bin/raofflineproxy-uninstall` directly.
