# Pending Awards

## Overview

When you earn a softcore achievement while offline, RAOfflineProxy intercepts the award request from RetroArch and:

1. Stores it in a local queue (the `pending_awards` database table)
2. Returns a synthetic success response to RetroArch so the unlock registers in-game immediately

When you come back online, the proxy automatically sends all queued awards to RetroAchievements.

## The Award Queue

Navigate to **Pending Awards** in the drawer to see all queued awards. For each award you can see:

- Game title and icon
- Achievement title, badge image, and points value
- Any error from the last flush attempt

## Automatic Flush

The proxy flushes the award queue automatically whenever connectivity is restored. You can see flush progress in a snackbar at the bottom of the **Pending Awards** screen.

Flush events:
- **Started** — flush has begun
- **Progress (N / Total)** — awards sent so far
- **Completed (N flushed of Total)** — flush finished

## Flush Logic

Awards are flushed in the order they were queued (oldest first).

| Outcome | Behaviour |
|---|---|
| **Success** | Award is deleted from the queue |
| **Auth error** (invalid token / 401 / 403) | `lastError` is set on the award, retry is stopped — you need to re-authenticate |
| **Network error** | `retryCount` is incremented, `lastError` is updated. After **5 failures** the award is no longer retried (stays in queue) |
| **Chain broken** | Entire flush is blocked — see [Anti-Tamper Hash Chain](./hash-chain) |

::: warning Hardcore awards
If a hardcore award (`h=1`) somehow ends up in the queue (from an older version), it is silently deleted during flush rather than sent to RA.
:::

## Authentication Errors

If you see a red error message on a pending award saying something like "Invalid token" or "Invalid credentials", your RetroAchievements session has expired. To fix this:

1. Open RetroArch
2. Go to **Settings → Achievements** and log in again
3. Return to RAOfflineProxy — the next flush attempt should succeed

## Synthetic Success Response

When an award is queued offline, RetroArch receives:

```json
{
  "Success": true,
  "Score": <your_cached_score>,
  "SoftcoreScore": 0,
  "AchievementID": 0,
  "Error": "queued_offline"
}
```

RetroArch treats this as a successful unlock and marks the achievement as earned in-session. The actual submission to RA happens when you reconnect.

## Duplicate Awards

Award entries use the achievement ID as a unique key (`UNIQUE` index in the database). If you earn the same achievement offline multiple times (e.g., after a save-state restore), only the most recent entry is kept — the previous one is replaced.
