# Pending Awards

## Overview

When you earn a softcore achievement while offline, RAOfflineProxy intercepts the award request from RetroArch and:

1. Stores it in a local queue
2. Returns a success response to RetroArch so the unlock registers in-game immediately

When you come back online, the proxy automatically sends all queued awards to RetroAchievements.

## The Award Queue

Navigate to **Pending Awards** in the drawer to see all queued awards. For each award you can see:

- Game title and icon
- Achievement title, badge image, and points value
- Any error from the last sync attempt

## Automatic Sync

The proxy sends queued awards automatically whenever connectivity is restored. You can see sync progress in a snackbar at the bottom of the **Pending Awards** screen.

Sync events:
- **Started** — sync has begun
- **Progress (N / Total)** — awards sent so far
- **Completed (N sent of Total)** — sync finished

## Sync Logic

Awards are sent in the order they were queued (oldest first).

| Outcome | Behaviour |
|---|---|
| **Success** | Award is removed from the queue |
| **Authentication error** (invalid token) | Error is shown on the award, no further retries — you need to re-authenticate |
| **Network error** | Retried up to **5 times**. After that, the award stays in the queue with an error message |
| **Chain broken** | Entire sync is blocked — see [Anti-Tamper Hash Chain](./hash-chain) |

::: warning Hardcore awards
If a hardcore award somehow ends up in the queue (from an older version), it is silently removed during sync rather than sent to RA.
:::

## Stale Award Filtering

Before syncing, the proxy checks each queued achievement against your cached game data. If an achievement has been retired or removed from RetroAchievements since you earned it, the award is flagged as stale and skipped during sync. Stale awards remain in the queue with an error message so you can see which ones were affected — they are not silently deleted.

## Authentication Errors

If you see a red error message on a pending award saying something like "Invalid token" or "Invalid credentials", your RetroAchievements session may need to be refreshed. To fix this:

1. Open RetroArch
2. Go to **Settings → Achievements** and log in again
3. Return to RAOfflineProxy — the next sync attempt should succeed

## What RetroArch Sees When You're Offline

When an award is queued offline, RetroArch receives a response that looks like a normal successful unlock. RetroArch marks the achievement as earned in-game immediately. The actual submission to RA happens when you reconnect.

## Duplicate Awards

If you earn the same achievement offline multiple times (e.g., after a save-state restore), only the most recent entry is kept — the previous one is replaced.
