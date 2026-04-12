# Privacy Policy

**Last updated: April 12, 2026**

RAOfflineProxy ("the app") is a local proxy tool for Android that enables offline RetroAchievements support with RetroArch. This privacy policy explains what data is handled by the app, how it is stored, and when it is transmitted.

## Summary

- The app does **not** collect, store, or transmit any data to its developer.
- The app does **not** contain ads, analytics, or crash reporting SDKs.
- All data handled by the app stays on your device or is sent directly to [RetroAchievements.org](https://retroachievements.org) on your behalf — the same requests RetroArch would have made itself.

---

## What data is handled

### RetroAchievements credentials (username and API token)

When RetroArch logs in to RetroAchievements through the proxy, the app caches the login response — which includes your RA username and API token — in a local SQLite database on your device. This cache is used to authenticate requests when your device is offline.

Your credentials are **never sent to the app developer**. They are only ever forwarded to `retroachievements.org`, exactly as RetroArch would do directly.

### Game and achievement data

The app caches game patch data (achievement lists, point values, badge names) and your unlock state in the local database. This is what allows achievements to display correctly when you are offline.

### Pending (offline) award queue

When you unlock an achievement while offline, the app queues the award in the local database and sends it to RetroAchievements when your connection is restored. Each queued award is signed with a device-local cryptographic key (stored in Android Keystore) and chained to the previous award for tamper evidence. The public key is included in the flushed request; the private key never leaves Android Keystore.

### ROM file hashes

When you scan a ROM file or folder, the app computes an MD5 hash of each file and sends that hash to RetroAchievements to look up the corresponding game. ROM file contents are never stored or transmitted — only the MD5 hash.

### RetroArch user-agent string

The app caches the `User-Agent` header sent by RetroArch (e.g. `rcheevos/11.4.0`) and appends its own identifier (`RAOfflineProxy/<version>`) when forwarding requests to RetroAchievements so the server can distinguish proxied traffic.

### Game icon images

Game badge and icon images are downloaded from RetroAchievements and stored in local app storage for display in the UI. They are never transmitted elsewhere.

---

## Data storage

All data is stored locally on your device in:

- A SQLite database (`raofflineproxy.db`) managed by Android Room
- App-internal file storage for cached images

Both are excluded from Android Auto-Backup and device-to-device transfer (as declared in `backup_rules.xml` and `data_extraction_rules.xml`). Your credentials will not appear in cloud backups.

---

## Data transmission

The app transmits data only to `retroachievements.org` over HTTPS. This includes:

| Data | When |
|---|---|
| Username + API token | On every proxied API request |
| Achievement award | When flushing the offline queue after reconnecting |
| ROM MD5 hash | When scanning ROM files |
| ECDSA public key | Attached to flushed award requests for chain verification |
| Seconds-since-unlock offset | Attached to flushed award requests |

No data is sent to any other server. The developer does not receive any data.

---

## Data deletion

You can delete all locally stored data at any time:

- **Clear Cache** (Settings screen) — removes cached game and achievement data. Does not remove credentials or pending awards.
- **Clear Database** (Settings screen) — removes all locally stored data including credentials, cached game data, and pending awards.

Deleting the app also removes all app data.

---

## Third-party services

The app communicates exclusively with [RetroAchievements.org](https://retroachievements.org). Their privacy policy governs how they handle data you send to their servers: [https://retroachievements.org/terms](https://retroachievements.org/terms).

---

## Children

The app does not knowingly collect data from children. It is a utility for interacting with RetroAchievements, which has its own terms of service.

---

## Changes to this policy

If this policy changes materially, the updated version will be published at this URL with a new "Last updated" date.

---

## Contact

For questions about this privacy policy, open an issue or discussion on the [GitHub repository](https://github.com/misantronic/RAOfflineProxy).
