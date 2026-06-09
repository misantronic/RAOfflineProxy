# muOS Target

`linux/muos/` contains the muOS-specific launcher, packaging scripts, and bundled runtime assets for the Linux client.

## Current Layout

- `build_bundle.sh`: builds a deployable `dist/RAOfflineProxy/` payload and packages it as a `.muxapp`
- `../build_rchash.sh`: cross-compiles the aarch64 `libraproxy_rchash.so` (rcheevos rc_hash + libchdr) used for ROM/disc hashing
- `launch.sh`: starts the bundled app with the muOS runtime environment
- `mux_launch.sh`: muOS Applications entrypoint
- `mux_lang.ini`: muOS app metadata
- `vendor/pygame` and `vendor/pygame.libs`: bundled `pygame` runtime for stock muOS Python
- `native/libraproxy_rchash.so` (build output): bundled to `lib/libraproxy_rchash.so` in the payload

## Runtime Assumptions

- muOS uses stock `/usr/bin/python`
- the active RetroArch config is `/opt/muos/share/info/config/retroarch.cfg`
- muOS RetroArch also uses `/opt/muos/share/info/config/retroarch.cheevos.cfg` via `--appendconfig`
- app payload lives under `/run/muos/storage/application/RAOfflineProxy`
- visible Applications entries live under `/opt/muos/share/application`
- autostart/init scripts live under `/run/muos/storage/init`

## Updates

- the SDL menu checks GitHub Releases for the `*muos*.muxapp` asset (platform `muos`)
- when a newer version is found, "Update Available" is shown in the menu
- installing downloads the `.muxapp`, extracts it in place over
  `/run/muos/storage/application/RAOfflineProxy`, always preserves `data/`
  (config, database, queued awards, secrets), then relaunches the menu
- the GitHub release asset name must contain `muos` and end in `.muxapp` to be detected

## Notes

- `pygame` is bundled because stock muOS does not ship it in site-packages
- the current target preserves the SDL menu UI rather than switching to a shell-only frontend
- start/stop patching must update both `retroarch.cfg` and `retroarch.cheevos.cfg`
