# Anti-Tamper Hash Chain

## Overview

Every award that is queued offline is cryptographically signed and chained to the previous one. This provides **tamper evidence** — if the local data is modified after awards are queued, the chain will be detected as broken and the entire sync will be blocked.

This design is inspired by the [melonDS offline RetroAchievements implementation](https://github.com/SapphireRhodonite) (Feb 2026), adapted to the proxy context.

## Three-Layer Mechanism

Each queued award is protected by three layers:

| Layer | Purpose |
|---|---|
| **Payload integrity** | A hash of the award's content (achievement ID, request data, timestamp) ensures the data hasn't been modified |
| **Chain linkage** | Each award references the hash of the previous award, forming a chain. The first award in the chain references a special "genesis" value |
| **Device attestation** | A cryptographic signature using a key stored securely on your device proves the award was signed on this specific device |

A signing timestamp is also stored alongside these fields.

## Signing at Queue Time

When an offline award is queued:

1. The previous award's hash is looked up (or "genesis" if it's the first)
2. The award's content is hashed
3. The hash and chain reference are signed with the device's private key
4. All signing data is stored on the award record

If the same achievement is triggered again while it is already pending, the duplicate request is discarded instead of replacing the existing row. This preserves the chain linkage for later awards.

## Device Key

The signing key is:

- Stored securely on your device — it never leaves the device in plaintext
- Non-exportable and can only be used for signing
- The public key is attached to every synced request for potential future server-side verification

## Chain Verification Before Sync

Before sending any awards to RA, the app walks all pending awards in queue order and verifies:

1. Each hash matches the stored award data — ensuring nothing was modified
2. Each chain reference correctly links to the previous award
3. Each stored signature verifies against the device's keystore-backed public key

If verification fails, the sync is **entirely blocked** — no awards are sent. A warning is shown in the UI with the position and reason for the broken link.

::: tip Legacy awards
Awards queued before the hash chain was introduced are transparently skipped by chain verification and synced normally.
:::

## Extra Data on Synced Requests

Every award sent to RA includes the chain data (hash, chain reference, signature, and public key) as extra form-encoded fields. RA's server currently ignores these fields. They are included for potential future server-side validation.

## Limitations

::: warning This is tamper-evidence, not tamper-prevention
A determined attacker with **root access on their own device** could modify the data and re-sign awards using the device key — since both the key and the data reside on the same device.

The hash chain prevents **external** forgery (a different device cannot forge awards) and detects **accidental** corruption. It does not prevent a motivated local attacker from tampering.

This matches the melonDS approach: tamper-*evidence*, not tamper-*prevention*.
:::

## Comparison with melonDS Implementation

| | melonDS (SapphireRhodonite) | RAOfflineProxy |
|---|---|---|
| Where signing happens | Inside the emulator, at moment of in-game unlock | Inside the proxy, at moment of queuing |
| What is proven | "This device triggered this achievement through gameplay" | "This queue was not modified on this device after recording" |
| Hash chain | Yes | Yes |
| Server-side changes required | No | No |
| Sync blocked on tamper | N/A | Yes — entire sync blocked |
