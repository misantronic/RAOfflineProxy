# Anti-Tamper Hash Chain

## Overview

Every award that is queued offline is cryptographically signed and chained to the previous one. This provides **tamper evidence** — if the local database is modified after awards are queued, the chain will be detected as broken and the entire flush will be blocked.

This design is inspired by the [melonDS offline RetroAchievements implementation](https://github.com/SapphireRhodonite) (Feb 2026), adapted to the proxy context.

## Three-Layer Mechanism

| Layer | Field | Value |
|---|---|---|
| **Payload integrity** | `payloadHash` | `SHA-256("<achievementId>\|<queryString>\|<requestBody>\|<queuedAt>")` as hex |
| **Chain linkage** | `prevHash` | `payloadHash` of the previous award, or `"genesis"` for the first |
| **Device attestation** | `signature` | ECDSA P-256 / `SHA256withECDSA` over `"<payloadHash>:<prevHash>"`, Base64 (no-wrap) |

An additional `signedAt` timestamp (epoch ms) is stored alongside these fields.

## Signing at Queue Time

When an offline award is queued:

1. The previous award's `payloadHash` is looked up (or `"genesis"` if none)
2. The canonical payload is hashed with SHA-256: `"$achievementId|$queryString|$requestBody|$queuedAt"`
3. The string `"$payloadHash:$prevHash"` is signed with ECDSA P-256 using the device key
4. All four fields (`payloadHash`, `prevHash`, `signature`, `signedAt`) are stored on the award record

## Android Keystore Key

The signing key is:

- Stored in **Android Keystore** — it never leaves the device in plaintext
- Non-exportable, `PURPOSE_SIGN` only
- Algorithm: **ECDSA P-256**
- Key alias: `ra_proxy_award_key`
- No user authentication required for signing

The public key (Base64 DER-encoded) is attached to every flushed request as `ra_chain_pubkey` for potential future server-side verification.

## Chain Verification Before Flush

Before sending any awards to RA, the flusher walks all pending awards in queue order and verifies:

1. Each `payloadHash` is recomputed from the stored canonical payload — must match stored value
2. Each `prevHash` must equal the `payloadHash` of the preceding award (or `"genesis"` for the first)

If verification fails, the flush is **entirely blocked** — no awards are sent. A warning is shown in the UI with the index and reason for the broken link.

::: tip Legacy awards
Awards queued before the hash chain was introduced have an empty `payloadHash`. These are transparently skipped by chain verification (treated as pre-chain awards) and flushed normally.
:::

## Extra Fields on Flushed Requests

Every award POST body sent to RA includes four extra fields:

| Field | Value |
|---|---|
| `ra_chain_payload_hash` | Hex SHA-256 of the canonical payload |
| `ra_chain_prev_hash` | `payloadHash` of the prior award or `"genesis"` |
| `ra_chain_sig` | Base64 ECDSA signature |
| `ra_chain_pubkey` | Base64 DER public key |

RA's server currently ignores these fields. They are included for potential future server-side validation.

## Limitations

::: warning This is tamper-evidence, not tamper-prevention
A determined attacker with **root access on their own device** could modify the database and re-sign awards using the device key — since both the key and the data reside on the same device.

The hash chain prevents **external** forgery (a different device cannot forge awards) and detects **accidental** corruption. It does not prevent a motivated local attacker from tampering.

This matches the melonDS approach: tamper-*evidence*, not tamper-*prevention*.
:::

## Comparison with melonDS Implementation

| | melonDS (SapphireRhodonite) | RAOfflineProxy |
|---|---|---|
| Where signing happens | Inside the emulator, at moment of in-game unlock | Inside the proxy, at moment of queuing |
| What is proven | "This device triggered this achievement through gameplay" | "This queue was not modified on this device after recording" |
| Device key storage | Android Keystore | Android Keystore |
| Hash chain | Yes | Yes |
| Algorithm | ECDSA P-256 / SHA256withECDSA | ECDSA P-256 / SHA256withECDSA |
| Server-side changes required | No | No |
| Flush blocked on tamper | N/A | Yes — entire flush blocked |
