# KNULLI Bundle

This directory contains a portable KNULLI bundle for the early Linux `RAOfflineProxy` client.

## What It Does

- installs the Python app under `/userdata/system/raofflineproxy/app`
- installs small launcher scripts under `/userdata/system/raofflineproxy/bin`
- adds EmulationStation Tools entries for:
  - `RAOfflineProxy Start`
  - `RAOfflineProxy Stop`
  - `RAOfflineProxy Status`

Each Tools action writes a summary file to `/userdata/system/raofflineproxy/ui-state.txt` and also tries to trigger a frontend notification if KNULLI exposes a compatible helper.

This bundle now patches RetroArch config and launches the background Linux proxy service.

## Build

From repo root:

```bash
./linux/knulli/build_bundle.sh
```

This creates:

- `linux/knulli/dist/raofflineproxy-knulli-bundle.tar.gz`

## Install On KNULLI

```bash
scp /Users/dschkalee/src/RAOfflineProxy/linux/knulli/dist/raofflineproxy-knulli-bundle.tar.gz root@knulli:/userdata/system/
ssh root@knulli
cd /userdata/system
rm -rf raofflineproxy-knulli-bundle
tar -xzf raofflineproxy-knulli-bundle.tar.gz --no-same-owner
cd raofflineproxy-knulli-bundle
./install.sh
```

After install, restart EmulationStation to refresh the Tools list.

## Uninstall

```bash
cd /userdata/system/raofflineproxy-knulli-bundle
./uninstall.sh
```
