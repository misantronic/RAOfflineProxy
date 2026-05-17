# Anti-Tamper Hash Chain (Linux)

## Overview

Every award that is queued offline is signed and chained to the previous one. This provides tamper evidence: if the local award data is modified after queueing, the chain will be detected as broken and sync will be blocked.

## How Linux differs from Android

The Linux implementation follows the same basic chain concept as Android, but it uses a local HMAC secret instead of the Android Keystore-backed signing key.

That means the goal is the same:

- Detect queue tampering
- Preserve queue order
- Block sync when integrity fails

## How it works

When an offline award is queued:

1. The previous award's chain value is loaded
2. The current award payload is hashed
3. A new chain value is produced
4. The signing data is stored with the queued award

Before sync, RAOfflineProxy walks the queue in order and verifies that the chain is still valid.

If verification fails, no queued awards are sent.

## Why it exists

The hash chain helps detect:

- Accidental corruption
- Broken queue ordering
- Manual edits to queued award data

## Limitation

This is tamper evidence, not tamper prevention.

Like the Android implementation, it helps detect modification of local queue data. It does not turn offline achievements into a hardcore-secure system.
