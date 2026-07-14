# Knulli Native Integration (dev prototype)

Prototype of the native Knulli integration discussed with the Knulli devs,
testable on a real device with a full revert path. It replaces the legacy
retroarch.cfg patching with launch-time config generation and gives the proxy
a toggle in the Knulli UI.

## What it installs

1. **User service** `/userdata/system/services/raofflineproxy`
   - Appears automatically in EmulationStation -> System Settings -> Services
     as an on/off toggle (persisted in `batocera.conf` `system.services`,
     started at boot by `S99userservices`).
   - Runs `raofflineproxy run-service` via `start-stop-daemon` - the proxy
     server only, no config patching.
   - Lives entirely in `/userdata`, so no rootfs change is needed for it.

2. **configgen hook** in `libretroConfig.py` (rootfs, persisted via the
   Knulli overlay mechanism)
   - At every game launch, probes `127.0.0.1:<proxy_port>` (port read from
     the app's `config.json`, default 8080).
   - If the proxy is listening and RetroAchievements are enabled for a
     supported core: sets `cheevos_custom_host`, keeps `cheevos_enable`
     "true" even when Knulli thinks it is offline, and forces hardcore off.
   - If the proxy is not running: clears `cheevos_custom_host`, restoring
     stock behaviour (including clearing any stale value left by the legacy
     patching mode).
   - Marker-based and idempotent; a pristine backup
     (`libretroConfig.py.raop-orig`) is kept next to the file.

The sideloaded app bundle (`/userdata/system/raofflineproxy`) is a
prerequisite and is left untouched; the pygame menu keeps working.

## Usage

```bash
# from this directory, device reachable over SSH (Knulli default: root/linux)
./deploy.sh root@<device-ip> install
./deploy.sh root@<device-ip> status
./deploy.sh root@<device-ip> uninstall     # full revert, keeps other overlay mods
./deploy.sh root@<device-ip> purge         # revert + delete the whole rootfs overlay file
```

Install automatically stops legacy mode first (`stop-proxy`,
`disable-autostart`) so retroarch.cfg is reverted before native mode takes
over. Uninstall restores `libretroConfig.py` from the backup, removes the
service, and re-persists the overlay - after that the device behaves exactly
as before, and the legacy pygame-menu Start/Stop mode can be used again.
Switching back and forth is safe in both directions.

`purge` additionally deletes `/boot/boot/overlay`. Only use it if
RAOfflineProxy is the only rootfs customization on the device.

## EmulationStation integration (dev)

The knulli ES fork checkout at `~/src/knulli-emulationstation` (branch
`raofflineproxy-integration`, uncommitted) adds:

- **Offline icon**: games whose achievements are cached by the proxy show
  trophy+download (``) instead of the plain trophy in gamelists.
  ES reads the id list the proxy exports to
  `/userdata/system/.config/raofflineproxy/cached_game_ids.txt` (kept in sync
  by the Python app on every cache mutation).
- **Game options menu**: "CACHE ACHIEVEMENTS FOR OFFLINE PLAY" /
  "REMOVE OFFLINE ACHIEVEMENT DATA" per game (runs `cache-rom` /
  `remove-cached-game` via the proxy CLI).
- **Gamelist options menu**: "CACHE ACHIEVEMENTS FOR OFFLINE PLAY (N)" for
  all displayed games (runs the new `cache-roms --paths-file` batch CLI with
  live progress).

All entries only appear when the proxy launcher exists on the device, so the
patch is inert on stock installs.

Build (in `~/src/knulli-linux`, dockerized; `knulli.mk` mounts the ES
checkout and `output/h700/local.mk` sets
`KNULLI_EMULATIONSTATION_OVERRIDE_SRCDIR`):

```bash
make h700-pkg PKG=knulli-emulationstation
```

Deploy/revert the built binary on the device:

```bash
./deploy-es.sh root@<device-ip> install   # backs up /usr/bin/emulationstation.orig
./deploy-es.sh root@<device-ip> revert
```

## Testing checklist

- ES -> System Settings -> Services shows "raofflineproxy"; toggle starts and
  stops the proxy (`deploy.sh ... status` to confirm).
- With the service on: launch a RA-enabled game, check the proxy log
  (`/userdata/system/.config/raofflineproxy/service.log`) for intercepted
  requests; `retroarch.cfg` gets `cheevos_custom_host = "127.0.0.1:<port>"`
  written by configgen at launch.
- With the service off: launch a game, `cheevos_custom_host` is empty and
  RA traffic goes direct.
- Offline with the service on: game launches with achievements active from
  cache; casual unlocks are queued.
- Reboot with the service enabled: proxy is running afterwards.
