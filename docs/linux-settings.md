# Settings & Auto-start (Linux)

## KNULLI menu options

On KNULLI, the RAOfflineProxy menu currently includes:

- Start proxy
- Stop proxy
- Enable autostart / Disable autostart
- Cached Games
- Pending Awards
- Add ROM
- Clear cache
- Uninstall
- Exit Menu

## Auto-start

On KNULLI, autostart is currently implemented through the platform startup hook.

It uses:

- `/userdata/system/custom.sh`
- `/userdata/system/.config/raofflineproxy/` for persistent config and cache data

This keeps the same cached data visible to:

- the RAOfflineProxy menu
- manual launches
- autostarted proxy services after reboot

## Start / Stop proxy

Starting the proxy patches RetroArch, starts the background local proxy service, and keeps the required RetroAchievements settings active while the service is running.

Stopping the proxy stops the service and reverts the RetroArch config patch.

## Cache management

Linux keeps cache and pending-award data in RAOfflineProxy's own local storage.

When Python includes `sqlite3`, this data is stored in:

`~/.config/raofflineproxy/proxy.sqlite3`

On minimal Python builds without `sqlite3`, RAOfflineProxy falls back to:

`~/.config/raofflineproxy/proxy.json`

On KNULLI, **Clear cache** removes game-related cache entries while preserving cached login and User-Agent data.

## Uninstall on KNULLI

The KNULLI uninstall flow:

1. Stops the proxy
2. Disables autostart
3. Removes the Tools entry
4. Removes the installed bundle
5. Removes persistent RAOfflineProxy config and cache data

This clears cached games, cached login data, queued awards, and logs.
