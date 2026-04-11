# Settings & Auto-start

## Settings Screen

Navigate to **Settings** in the drawer to access the following options.

### Auto-start on Boot

**Autostart RA Offline Proxy at startup** — when enabled, the proxy service starts automatically when the device boots.

::: warning Both conditions must be met
Auto-start only works when **both** of the following are true:
1. The "Autostart" checkbox is checked
2. `retroarch.cfg` is currently patched (i.e. `cheevos_custom_host = "127.0.0.1:8080"`)

The checkbox alone is not sufficient. If the cfg is not patched, the boot receiver will not start the service.
:::

### Clear Cache

Removes all cached game data from the local database:
- `patch:*` entries (game / achievement data)
- `gameid:*` entries (ROM hash → Game ID mappings)
- `unlocks:*` entries (unlock lists)
- `startsession:*` entries (synthesized session data)

Login credentials (`login2:*`) and the last user-agent (`ua::last`) are **preserved** — clearing the cache does not log you out.

::: tip When to clear cache
Use **Clear Cache** if your cached game data is stale or if you want to free up storage. You will need to re-cache your games before playing offline.
:::

### Clear Database

Removes **all** data from the database — including cached game data **and all pending awards**.

::: danger This deletes pending awards
If you have unsynced offline unlocks in the Pending Awards queue, **Clear Database will permanently delete them**. Make sure you are online and all awards have been flushed before using this option.
:::

## Proxy Toggle (Action Bar)

The action bar at the top of every screen has a **Start proxy / Stop proxy** button.

| State | Button label | Tooltip |
|---|---|---|
| Proxy stopped, cfg not patched | Start proxy (disabled) | — |
| Proxy stopped, cfg patched | Start proxy | — |
| Proxy running, online | Stop proxy | Proxy running — online |
| Proxy running, offline | Stop proxy | Proxy running — offline |

The button is disabled (greyed out) if `retroarch.cfg` is not patched and the proxy is not already running.

## Persistent Foreground Service

When the proxy is running, a persistent foreground notification is shown:

- **Online**: "Online — Forwarding to RA"
- **Offline**: "Offline — Serving from cache"

Tapping the notification opens the app. The notification cannot be dismissed while the proxy is running (it is `ongoing = true`).

## Background Refresh

While the proxy is running and the device is online, all cached games are automatically refreshed **every 60 minutes**. This keeps achievement lists and unlock counts up to date without any manual action.

During each refresh cycle, cache entries older than **7 days** are automatically evicted to free up storage. Login credentials are exempt from eviction — you will not be logged out by this process.
