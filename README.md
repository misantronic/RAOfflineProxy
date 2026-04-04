# RAOfflineProxy

An Android app that acts as a local HTTP proxy between **RetroArch** and the **RetroAchievements** (RA) API. It intercepts RetroArch's achievement-related HTTP traffic, caches game and achievement data locally for offline play, queues softcore award submissions when offline, and flushes them to RA when connectivity is restored.

**Hardcore mode is not supported.** All `h=1` requests are rejected with HTTP 403.

---

## Features

- **Transparent caching** — patch data, unlocks, game IDs, and session data are cached in a local Room database on first online access
- **Offline award queuing** — softcore achievement unlocks made while offline are queued and replayed automatically when the device reconnects
- **ROM scanning** — scan a folder or add individual ROM files; the app hashes each file (MD5), looks up the game ID via RA, and pre-caches all required data for offline play
- **RetroArch cfg patcher** — automatically writes `cheevos_custom_host = "127.0.0.1:8080"` into `retroarch.cfg` via a four-tier access strategy (SAF tree → direct file → SAF grant prompt → staging copy)
- **Anti-tamper hash chain** — each queued award is cryptographically chained and signed with a non-exportable device key; the full chain is verified before any flush
- **Auto-start on boot** — optionally starts the proxy service on device boot (requires cfg to be patched)
- **Hourly background refresh** — cached game data is refreshed in the background every hour while online

---

## Architecture

```
RetroArch (rcheevos)
    │  HTTP → 127.0.0.1:8080
    ▼
ProxyServer (ServerSocket, CachedThreadPool)
    │
    ├─[online + cacheable]──► OkHttp → retroachievements.org → upsert api_cache
    ├─[offline + cacheable]──► CacheDao.get() → cached JSON
    ├─[award + softcore + online]──► OkHttp → RA  (queue on failure)
    ├─[award + softcore + offline]──► PendingAwardDao.insert() → synthetic success
    ├─[award + h=1]──────────► HTTP 403 immediately
    └─[hardcore non-award]───► bypass cache, forward if online, 503 if offline

ProxyService (foreground service)
    ├── Owns ProxyServer + AwardFlusher
    ├── ConnectivityManager.NetworkCallback
    │     onAvailable → AwardFlusher.flush() + updateNotification()
    │     onLost      → updateNotification()
    └── periodicRefreshLoop() every 60 min → cacheGame() for all cached gameIds
```

### Components

| Component | File | Purpose |
|---|---|---|
| `ProxyServer` | `proxy/ProxyServer.kt` | Raw `ServerSocket` on port 8080; manual HTTP parsing; routes requests, signs queued awards |
| `AwardFlusher` | `proxy/AwardFlusher.kt` | Replays pending awards to RA; verifies hash chain before flush; emits `FlushEvent` via process-level `SharedFlow` |
| `AwardKeyManager` | `proxy/AwardKeyManager.kt` | Manages a non-exportable ECDSA P-256 key in Android Keystore; signs and exposes the public key for chain attestation |
| `RomScanner` | `proxy/RomScanner.kt` | MD5-hashes ROM files, fetches game IDs via local proxy, pre-caches patch/unlocks/session data |
| `ProxyService` | `service/ProxyService.kt` | Foreground `dataSync` service owning `ProxyServer` + `AwardFlusher`; handles network callbacks and hourly refresh |
| `BootReceiver` | `service/BootReceiver.kt` | Starts proxy on boot if `autostart_proxy` pref is set **and** cfg is patched |
| `RetroArchCfgPatcher` | `ui/RetroArchCfgPatcher.kt` | Four-tier cfg patcher: SAF tree → direct file → SAF grant prompt → staging copy |
| `MainViewModel` | `ui/MainViewModel.kt` | Single `MainUiState`; drives all UI actions and observes DB + flush events |
| `MainActivity` | `ui/MainActivity.kt` | Single activity; drawer navigation; action bar proxy toggle |

---

## Data Layer

### Room Database

- **File**: `raofflineproxy.db`
- **Version**: 6
- **Migration strategy**: `fallbackToDestructiveMigration()` — schema changes wipe all data

### Tables

**`api_cache`** (`CacheEntry`)

| Column | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `cacheKey` | `String` | Unique index |
| `responseBody` | `String` | Raw JSON from RA API |
| `cachedAt` | `Long` | Updated on every upsert (epoch ms) |
| `firstCachedAt` | `Long` | Set only on insert (epoch ms) |

**`pending_awards`** (`PendingAward`)

| Column | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `achievementId` | `Int` | Unique index — upsert by achievement replaces previous entry |
| `queryString` | `String` | Original request path |
| `requestBody` | `String` | Original POST form body |
| `userAgent` | `String` | Forwarded verbatim on replay |
| `queuedAt` | `Long` | Epoch ms |
| `retryCount` | `Int` | Incremented on `NetworkError`; flush stops retrying at 5 |
| `lastError` | `String?` | Set on flush failure |
| `payloadHash` | `String` | SHA-256 of canonical payload (chain integrity) |
| `prevHash` | `String` | `payloadHash` of previous award, or `"genesis"` |
| `signature` | `String` | ECDSA P-256 / `SHA256withECDSA` over `"$payloadHash:$prevHash"` |
| `signedAt` | `Long` | Epoch ms of signing time |

### Cache Key Scheme

All cache key construction goes through `data/CacheKeys.kt`. Never hardcode key strings.

| Key | Pattern |
|---|---|
| User agent | `ua::last` |
| Login | `login2::<user>` |
| Game ID | `gameid:<hash>` |
| Patch data | `patch:<gameId>:<user>` |
| Unlocks | `unlocks:<gameId>:<user>:0` |
| Start session | `startsession:<gameId>:<user>:0` |

The trailing `:0` is the softcore flag. Hardcore variants (`:1`) are never cached.

---

## Anti-Tamper Hash Chain

Each queued award is signed at queue time using a non-exportable ECDSA P-256 key from Android Keystore. Awards are chained so that tampering with or reordering them is detectable.

| Layer | Field | Value |
|---|---|---|
| Payload integrity | `payloadHash` | `SHA-256("$achievementId\|$queryString\|$requestBody\|$queuedAt")` |
| Chain linkage | `prevHash` | `payloadHash` of previous award, or `"genesis"` |
| Device attestation | `signature` | ECDSA over `"$payloadHash:$prevHash"`, Base64 no-wrap |

`AwardFlusher.verifyChain()` walks all pending awards in queue order and recomputes each link before any flush begins. A broken chain emits `FlushEvent.ChainBroken` and blocks the entire flush. Chain fields are also forwarded as extra POST fields (`ra_chain_*`) on every flushed request.

This provides tamper-*evidence*, not tamper-*prevention* — a determined attacker with root access on their own device can manipulate the local DB and re-sign. It prevents external forgery and detects accidental or casual manipulation.

---

## RetroArch Setup

The app patches `retroarch.cfg` to point RetroArch's achievement host at the local proxy. It tries four access methods in order:

1. **SAF tree URI** (if previously granted) — reads and writes via `ContentResolver`
2. **Direct file write** — if the file exists and is writable
3. **SAF grant prompt** — requests folder access from the user (Android ≤ 12)
4. **Staging copy** — copies cfg to `/sdcard/RAOfflineProxy/retroarch.cfg`, patches it, and attempts to copy back; if copy-back fails, shows the path for manual action

The patcher sets:
```
cheevos_custom_host = "127.0.0.1:8080"
cheevos_hardcore_mode_enable = "false"
```

### Manual fallback (adb, no root required)

```bash
adb shell "sed -i 's/cheevos_custom_host = .*/cheevos_custom_host = \"127.0.0.1:8080\"/' \
  /data/user/0/com.retroarch.aarch64/files/retroarch.cfg"
```

---

## Build

**Requirements**: Android Studio, Android SDK API 34, JDK 21 (bundled with Android Studio)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

- **applicationId**: `com.raofflineproxy`
- **compileSdk / targetSdk**: 34
- **minSdk**: 26 (Android 8.0)
- **Language**: Kotlin 1.9.23, JVM target 17
- **Build system**: Gradle 9.2.1, AGP 8.4.1, KSP 1.9.23-1.0.19

Release builds use R8 minification (`isMinifyEnabled = true`). All versions are centralized in `gradle/libs.versions.toml`.

---

## Constants

Never hardcode these — use the constants defined in `NetworkConstants.kt` and `data/CacheKeys.kt`.

```kotlin
const val RA_HOST     = "https://retroachievements.org"
const val PROXY_PORT  = 8080
const val PROXY_HOST  = "127.0.0.1"
const val PROXY_BASE  = "http://127.0.0.1:8080"
const val PROXY_VALUE = "127.0.0.1:8080"
```

---

## Known Limitations

- **Hardcore mode is not supported** and will never be — all `h=1` requests are rejected
- `Achievement.kt` and `item_achievement.xml` exist but no per-game achievement list is shown in the UI
- Deleting a cached game only removes `patch:*` entries; associated `unlocks:*` and `startsession:*` entries are left in the database
- `CacheDao.evictOlderThan()` is implemented but never called — old cache entries are not automatically evicted
- `PendingAwardDao.observeCount()` is implemented but not used anywhere in the UI
