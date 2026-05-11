# Pending Awards (Linux)

## Overview

When you earn a softcore achievement while offline, RAOfflineProxy intercepts the award request and:

1. Stores it in a local queue
2. Returns a success response so the unlock registers immediately in RetroArch

When you come back online, the proxy automatically sends queued awards to RetroAchievements.

## Queue behavior

Awards are sent in the order they were queued, oldest first.

When a queued award is flushed, RAOfflineProxy computes the elapsed time since it was queued and sends that as the `o` offset parameter. RetroAchievements currently caps that backdate window at 14 days, so RAOfflineProxy clamps `o` to a maximum of `1209600` seconds and recalculates the validation hash `v` using that clamped value.

| Outcome | Behaviour |
|---|---|
| **Success** | Award is removed from the queue |
| **Authentication error** | Error is recorded and you need to refresh your login |
| **Network error** | The award stays queued and will be retried later |
| **Chain broken** | Sync is blocked until the queue issue is resolved |

## Automatic sync

On Linux, queued awards are flushed automatically when connectivity returns.

On KNULLI, you can review pending awards directly from the RAOfflineProxy menu.

## Authentication

Linux uses token-first authentication:

- if `cheevos_token` exists in `retroarch.cfg`, RAOfflineProxy uses it directly
- if no token exists, `cheevos_username` and `cheevos_password` are used once to retrieve and cache a token

If your token becomes invalid, log in again in RetroArch and restart the proxy so the updated credentials are imported.

## Duplicate awards

If the same achievement is triggered again while it is already pending, the duplicate request is discarded. This preserves queue order and chain integrity.

## Hardcore awards

Hardcore mode is unsupported. Hardcore award requests are rejected and are not sent through the offline queue.
