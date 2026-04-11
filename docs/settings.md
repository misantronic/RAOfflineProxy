# Settings & Auto-start

## Settings Screen

Navigate to **Settings** in the drawer to access the following options.

### Auto-start on Boot

**Autostart RA Offline Proxy at startup** — when enabled, the proxy service starts automatically when the device boots.

::: warning Both conditions must be met
Auto-start only works when **both** of the following are true:
1. The "Autostart" checkbox is checked
2. RetroArch's config is currently patched (pointing at the proxy)

The checkbox alone is not sufficient. If the config is not patched, the service will not start on boot.
:::

### Clear Cache

Removes all cached game data from the local database — game data, ROM identifiers, unlock lists, and session data.

Your login credentials are **preserved** — clearing the cache does not log you out.

::: tip When to clear cache
Use **Clear Cache** if your cached game data is stale or if you want to free up storage. You will need to re-cache your games before playing offline.
:::

### Clear Database

Removes **all** data from the database — including cached game data **and all pending awards**.

::: danger This deletes pending awards
If you have unsynced offline unlocks in the Pending Awards queue, **Clear Database will permanently delete them**. Make sure you are online and all awards have been sent before using this option.
:::

## Proxy Toggle (Action Bar)

The action bar at the top of every screen has a **Start proxy / Stop proxy** button.

| State | Button label | Tooltip |
|---|---|---|
| Proxy stopped, config not patched | Start proxy (disabled) | — |
| Proxy stopped, config patched | Start proxy | — |
| Proxy running, online | Stop proxy | Proxy running — online |
| Proxy running, offline | Stop proxy | Proxy running — offline |

The button is disabled (greyed out) if RetroArch's config is not patched and the proxy is not already running.

## Persistent Notification

When the proxy is running, a persistent notification is shown:

- **Online**: "Online — Forwarding to RA"
- **Offline**: "Offline — Serving from cache"

Tapping the notification opens the app. The notification cannot be dismissed while the proxy is running.

## Background Refresh

While the proxy is running and the device is online, all cached games are automatically refreshed **every 60 minutes**. This keeps achievement lists and unlock counts up to date without any manual action.

During each refresh cycle, cached data older than **7 days** is automatically removed to free up storage. Login credentials are exempt — you will not be logged out by this process.
