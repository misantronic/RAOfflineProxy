# Pending Awards (Android)

## Overview

When you earn a casual achievement while offline, RAOfflineProxy intercepts the award request from the emulator and:

1. Stores it in a local queue
2. Returns a success response to the emulator so the unlock registers in-game immediately

When you come back online, the proxy automatically sends all queued awards to RetroAchievements.

## The Award Queue

Navigate to **Pending Awards** in the drawer to see all queued awards. For each award you can see:

- Game title and icon
- Achievement title, badge image, and points value
- Any error from the last sync attempt

## Automatic Sync

The proxy sends queued awards automatically whenever connectivity is restored. You can see sync progress in a snackbar at the bottom of the **Pending Awards** screen.

Sync events:
- **Started**: sync has begun
- **Progress (N / Total)**: awards sent so far
- **Completed (N sent of Total)**: sync finished

## Sync Logic

Awards are sent in the order they were queued (oldest first).

When a queued award is flushed, the proxy computes the elapsed time since it was queued and sends that as the `o` offset parameter. RetroAchievements currently caps that backdate window at 14 days, so RAOfflineProxy clamps `o` to a maximum of `1209600` seconds and recomputes the validation hash `v` using the same clamped value.

| Outcome | Behaviour                                                                                 |
|---|-------------------------------------------------------------------------------------------|
| **Success** | Award is removed from the queue                                                           |
| **Authentication error** (invalid token) | Error is shown on the award, no further retries: you need to re-authenticate              |
| **Network error** | Retried up to **5 times**. After that, the award stays in the queue with an error message |
| **Chain broken** | Entire sync is blocked - see [Anti-Tamper Hash Chain](./hash-chain)                       |

## Stale Award Filtering

Before syncing, the proxy checks each queued achievement against your cached game data. If an achievement has been retired or removed from RetroAchievements since you earned it, the award is flagged as stale and skipped during sync. Stale awards remain in the queue with an error message so you can see which ones were affected - they are not silently deleted.

## Authentication Errors

If you see a red error message on a pending award saying something like "Invalid token" or "Invalid credentials", your RetroAchievements session may need to be refreshed. To fix this:

1. Open the emulator that holds your current RetroAchievements login
2. Go to its RetroAchievements settings, log in again, and save the settings
3. Return to RAOfflineProxy and restart the proxy so the refreshed token is imported from that emulator config
4. The next sync attempt should use the new token

## What the Emulator Sees When You're Offline

When an award is queued offline, the emulator receives a response that looks like a normal successful unlock. The achievement is marked as earned in-game immediately. The actual submission to RA happens when you reconnect.

## Duplicate Awards

If you earn the same achievement offline multiple times (e.g., after a save-state restore), the first pending entry is kept and later duplicates are discarded while that award is still pending.
