# BananaOS Bundle

This directory builds a card-side BananaOS port for the RG DS and RG DS Plus.
It follows the GBA DS package layout and does not modify BananaOS's read-only
system partition.

## Build

The build reuses the aarch64 pygame vendor payload from the ROCKNIX bundle:

```bash
./linux/rocknix/fetch_vendor.sh
./linux/banana/build_bundle.sh
```

The ZIP is written to `linux/banana/dist/`.

## Install

Extract the ZIP into the BananaOS card's `roms/ports` directory. Start
`RAOfflineProxy` from the Ports shelf. It starts the proxy in the background
and opens the SDL menu.

The proxy stores its state under `/data/banana/raofflineproxy` and listens only
on `127.0.0.1:18080`. BananaOS does not assign an address to loopback by
default, so the launcher checks for `127.0.0.1/8` and only adds it when missing.

When the GBA DS App Store app is installed, proxy startup snapshots and updates
`/data/store/apps/gbads/gba-ds.cfg` with `ra_host = http://127.0.0.1:18080`.
Stopping the proxy restores the original file. The file is left untouched when
GBA DS is not installed.

## GBA DS

GBA DS uses its own embedded RetroAchievements client and does not inherit
RetroArch's custom host. Set this in its BananaOS config:

```ini
ra_host = http://127.0.0.1:18080
```

The BananaOS App Store keeps that config at
`/data/store/apps/gbads/gba-ds.cfg`. GBA DS supplies the BananaOS account and
its requests then pass through RAOfflineProxy.
