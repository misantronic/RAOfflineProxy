# RAOfflineProxy — Project AGENTS.md

## Purpose

RAOfflineProxy is an Android app that acts as a local HTTP proxy between **RetroArch** and the **RetroAchievements** (RA) API (`retroachievements.org`). It intercepts RetroArch's API calls, caches game/achievement data locally for offline play, queues softcore achievement awards when offline, and flushes them to RA when connectivity is restored.

**Hardcore mode is explicitly unsupported throughout the entire codebase.** All code paths that detect `h=1` either reject, delete, or bypass the request. Do not add hardcore support.

---

## Build

- **applicationId**: `com.raofflineproxy`
- **compileSdk / targetSdk**: 34 — **minSdk**: 26 (Android 8.0)
- **Language**: Kotlin 1.9.23, JVM target 17
- **Build system**: Gradle 9.2.1, AGP 8.4.1, KSP 1.9.23-1.0.19
- **Build feature**: `viewBinding = true`
- **Daemon JVM**: JetBrains JDK 21
- **Build command**: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew assembleDebug`
- **Release builds**: R8 minification enabled (`isMinifyEnabled = true`) with `proguard-android-optimize.txt` + project `proguard-rules.pro`
- **Proguard rules**: keeps all `com.raofflineproxy.data.**` classes and all annotations
- **Version catalog**: `gradle/libs.versions.toml` — all versions centralized there
- Room uses `fallbackToDestructiveMigration()` — schema changes wipe all data

### Dependencies (exact versions from version catalog)

| Library | Version | Purpose |
|---|---|---|
| Room (runtime + ktx + compiler via KSP) | 2.6.1 | Local SQLite database |
| OkHttp | 4.12.0 | Outbound HTTP to RA servers |
| Kotlin Coroutines Android | 1.8.1 | Async operations |
| Coil | 2.7.0 | Image loading (game icons, badges) |
| Material Components | 1.12.0 | UI theme and components |
| DocumentFile | 1.0.1 | SAF tree file navigation |
| RecyclerView | 1.3.2 | List UIs |
| Core KTX | 1.13.1 | Kotlin extensions |
| AppCompat | 1.7.0 | Backward compatibility |
| Lifecycle ViewModel KTX | 2.8.3 | ViewModel with coroutines |
| Lifecycle Runtime KTX | 2.8.3 | Lifecycle-aware coroutines |
| Activity KTX | 1.9.1 | Activity result APIs |
| Fragment KTX | 1.8.2 | Fragment result APIs |

---

## Anti-Tamper Hash Chain

Each pending award is cryptographically chained to the previous one at queue time and signed with a non-exportable device key from Android Keystore. On flush, the full chain is verified before any award is sent; a broken chain blocks the flush entirely and surfaces a warning in the UI. Chain metadata is attached to every flushed request as extra POST fields for potential future server-side verification by RA.

This mirrors the approach taken in the melonDS offline RetroAchievements implementation (SapphireRhodonite, Feb 2026), adapted to the proxy context: instead of signing at the moment of in-game unlock, we sign at the moment the award is queued by the proxy.

### Three-layer mechanism

| Layer | Field | Value |
|---|---|---|
| Payload integrity | `payloadHash` | `SHA-256("$achievementId\|$queryString\|$requestBody\|$queuedAt")` as hex |
| Chain linkage | `prevHash` | `payloadHash` of the previous award, or `"genesis"` for the first |
| Device attestation | `signature` | ECDSA P-256 / `SHA256withECDSA` over `"$payloadHash:$prevHash"`, Base64 (no-wrap) |

An additional `signedAt` timestamp (epoch ms) is stored alongside these fields.

### Key files

**`proxy/AwardKeyManager.kt`** — `object` that manages a single non-exportable ECDSA P-256 key pair in Android Keystore:
- Alias: `"ra_proxy_award_key"`
- Generated lazily on first use with `PURPOSE_SIGN` only, `setUserAuthenticationRequired(false)`
- `sign(data: ByteArray): ByteArray` — signs with `SHA256withECDSA`
- `getPublicKeyBase64(): String` — Base64 DER-encoded public key, embedded in flushed requests

**`data/PendingAward.kt`** — four new fields added to the entity (all default to `""` / `0L` so legacy rows queued before this feature are treated as pre-chain and skipped by verification):
```kotlin
val payloadHash: String = ""
val prevHash: String = ""
val signature: String = ""
val signedAt: Long = 0L
```

**`proxy/ProxyServer.kt` — `queueAward()`** — signing happens inside the existing `scope.launch(Dispatchers.IO)` fire-and-forget block:
1. `getLatest()?.payloadHash ?: "genesis"` → `prevHash`
2. `SHA-256(canonicalPayload)` → `payloadHash`
3. `AwardKeyManager.sign("$payloadHash:$prevHash".toByteArray())` → `signature`
4. All four fields stored on the upserted `PendingAward`

**`proxy/AwardFlusher.kt`** — two additions:

- `verifyChain(awards)` — walks awards in `queuedAt ASC` order (same as flush order), recomputes each `payloadHash` from the canonical payload string, and checks each `prevHash` link. Awards with empty `payloadHash` (legacy, pre-chain) are skipped transparently. Returns `Valid` or `Broken(index, reason)`.
- `flush()` — runs `verifyChain()` **before** emitting `Started`. A `Broken` result emits `FlushEvent.ChainBroken` and returns immediately without flushing a single award.
- `buildRequestBody()` — appends the four chain fields to every outbound POST body:
  - `ra_chain_payload_hash`
  - `ra_chain_prev_hash`
  - `ra_chain_sig`
  - `ra_chain_pubkey`

### FlushEvent additions

```kotlin
data class ChainBroken(val index: Int, val reason: String) : FlushEvent
```

Handled in `MainViewModel` — sets `flushInProgress = false`, surfaces reason via `flushProgress` (string `R.string.flush_chain_broken`).

### Database version

`AppDatabase` was bumped from version **5 → 6** for the four new `PendingAward` columns. `fallbackToDestructiveMigration()` wipes all data on upgrade — this is a one-time cost.

### Comparison to melonDS implementation

| | melonDS (SapphireRhodonite) | RAOfflineProxy |
|---|---|---|
| Where signing happens | Inside the emulator, at moment of in-game unlock | Inside the proxy, at moment of queuing |
| What is proven | "This device triggered this achievement through gameplay" | "This queue was not modified on this device after recording" |
| Device key | Android Keystore / TPM (cross-platform) | Android Keystore |
| Hash chain | Yes | Yes |
| ECDSA P-256 signature | Yes (`SHA256withECDSA`) | Yes (`SHA256withECDSA`) |
| Server-side changes required | No | No |
| Flush blocked on tamper | N/A (emulator-native) | Yes — entire flush blocked |

The proxy approach is one layer weaker: a determined attacker with root access on their own device could manipulate the DB and re-sign. The device key prevents external forgery but not local manipulation, since the key lives on the same device as the data. This matches the melonDS model — it is tamper-*evidence*, not tamper-*prevention*.

---

## Android Manifest

### Permissions

| Permission | Scope |
|---|---|
| `INTERNET` | All |
| `ACCESS_NETWORK_STATE` | All |
| `FOREGROUND_SERVICE` | All |
| `FOREGROUND_SERVICE_DATA_SYNC` | All |
| `POST_NOTIFICATIONS` | All |
| `RECEIVE_BOOT_COMPLETED` | All |
| `READ_EXTERNAL_STORAGE` | maxSdkVersion=32 |
| `WRITE_EXTERNAL_STORAGE` | maxSdkVersion=29 |
| `MANAGE_EXTERNAL_STORAGE` | All (Android 13+ scoped storage fallback) |

### Application config

- `usesCleartextTraffic = true` — required for local loopback proxy on HTTP
- `allowBackup = true`
- Theme: `Theme.RAOfflineProxy` (DayNight.DarkActionBar)

### Components

| Type | Class | Notes |
|---|---|---|
| Activity | `.ui.MainActivity` | exported=true, MAIN/LAUNCHER |
| Service | `.service.ProxyService` | exported=false, foregroundServiceType=`dataSync` |
| Receiver | `.service.BootReceiver` | exported=true, BOOT_COMPLETED |

---

## Constants — never hardcode these

### `NetworkConstants.kt`

Top-level constants (no class, package root `com.raofflineproxy`):

```kotlin
const val RA_HOST     = "https://retroachievements.org"
const val PROXY_PORT  = 8080
const val PROXY_HOST  = "127.0.0.1"
const val PROXY_BASE  = "http://127.0.0.1:8080"
const val PROXY_VALUE = "127.0.0.1:8080"   // written into retroarch.cfg
```

### `data/CacheKeys.kt`

All cache key construction goes through this `object`. Never hardcode key strings.

| Builder / Constant | Pattern |
|---|---|
| `USER_AGENT` | `"ua::last"` |
| `PREFIX_LOGIN` | `"login2::"` |
| `PREFIX_PATCH` | `"patch:"` |
| `PREFIX_UNLOCKS` | `"unlocks:"` |
| `PREFIX_STARTSESSION` | `"startsession:"` |
| `PREFIX_GAMEID` | `"gameid:"` |
| `login(user)` | `"login2::$user"` |
| `gameId(hash)` | `"gameid:$hash"` |
| `patch(gameId, user)` | `"patch:$gameId:$user"` |
| `patchPrefix(gameId)` | `"patch:$gameId:"` |
| `unlocks(gameId, user)` | `"unlocks:$gameId:$user:0"` |
| `startSession(gameId, user)` | `"startsession:$gameId:$user:0"` |

The `patch` and `unlocks` and `startSession` builders each have two overloads: one accepting `Int gameId`, one accepting `String gameId`.

The trailing `:0` in `unlocks` and `startSession` keys is the softcore flag. Hardcore variants (`:1`) are never cached.

---

## Architecture Overview

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

MainViewModel
    ├── Observes pendingAwardDao.observe() → PendingAwardUi list
    ├── Observes cacheDao.observePatchEntries() → CachedGame list
    ├── Observes AwardFlusher.events → flush progress state
    ├── Monitors ConnectivityManager.NetworkCallback → isOnline state
    └── Drives all UI actions: patch/revert cfg, scan roms, add rom, refresh, clear cache/db

BootReceiver
    └── BOOT_COMPLETED → checkIsPatched() → ProxyService.start() (only if cfg is patched)
```

---

## Package Structure

```
com.raofflineproxy/
├── NetworkConstants.kt              — top-level constants (RA_HOST, PROXY_PORT, etc.)
├── data/
│   ├── Achievement.kt               — transient model (not persisted; no adapter yet)
│   ├── AppDatabase.kt               — Room singleton, version 5
│   ├── CacheDao.kt                  — @Dao interface for api_cache table
│   ├── CacheEntry.kt                — @Entity: api_cache table
│   ├── CacheKeys.kt                 — object with all cache key builders
│   ├── CachedGame.kt                — transient UI model derived from patch entries
│   ├── PendingAward.kt              — @Entity: pending_awards table
│   ├── PendingAwardDao.kt           — @Dao interface for pending_awards table
│   └── PendingAwardUi.kt            — transient UI model resolved from PendingAward
├── proxy/
│   ├── AwardFlusher.kt              — replays pending awards to RA when online
│   ├── ProxyServer.kt               — raw ServerSocket HTTP proxy
│   └── RomScanner.kt                — MD5 hashing, gameid lookup, cacheGame()
├── service/
│   ├── BootReceiver.kt              — BOOT_COMPLETED receiver
│   └── ProxyService.kt              — foreground service owning proxy + flusher
└── ui/
    ├── CachedGamesAdapter.kt        — ListAdapter<CachedGame>
    ├── CachedGamesFragment.kt       — fragment with ROM scan/add/refresh/clear
    ├── CachedGamesHeaderAdapter.kt  — single-item header with action buttons
    ├── HomeFragment.kt              — landing page with setup/token hints
    ├── MainActivity.kt              — single activity, drawer navigation
    ├── MainViewModel.kt             — AndroidViewModel with MainUiState
    ├── PendingAwardsAdapter.kt      — ListAdapter<PendingAwardUi>
    ├── PendingAwardsFragment.kt     — fragment showing queued awards
    ├── PendingAwardsHeaderAdapter.kt — single-item header with empty-state text
    ├── RetroArchCfgPatcher.kt       — top-level functions only, no class
    ├── RetroArchSetupFragment.kt    — fragment for patching/reverting retroarch.cfg
    └── SettingsFragment.kt          — autostart checkbox, clear cache/database buttons
```

---

## Data Layer

### Room Database (`AppDatabase.kt`)

- **DB name**: `"raofflineproxy.db"`
- **Version**: 5
- **Entities**: `CacheEntry`, `PendingAward`
- **DAOs**: `CacheDao`, `PendingAwardDao`
- **Migration strategy**: `fallbackToDestructiveMigration()` — all data is wiped on schema version bump
- **Singleton**: double-checked locking in `companion object`
- **Schema export**: disabled (`exportSchema = false`)

### Room Entities

**`CacheEntry`** (table: `api_cache`)

| Column | Type | Notes |
|---|---|---|
| `id` | `Long` | auto-generated PK |
| `cacheKey` | `String` | unique index |
| `responseBody` | `String` | raw JSON from RA API |
| `cachedAt` | `Long` | updated on every upsert (epoch ms) |
| `firstCachedAt` | `Long` | set only on insert (epoch ms) |

**`PendingAward`** (table: `pending_awards`)

| Column | Type | Notes |
|---|---|---|
| `id` | `Long` | auto-generated PK |
| `achievementId` | `Int` | unique index — upserts by achievement replace previous entries |
| `queryString` | `String` | original request path (e.g. `/dorequest.php?r=awardachievement&...`) |
| `requestBody` | `String` | original POST form body |
| `userAgent` | `String` | forwarded verbatim on replay |
| `queuedAt` | `Long` | epoch ms |
| `retryCount` | `Int` | default 0 |
| `lastError` | `String?` | null until a flush attempt fails |

### CacheDao — Notable Behaviours

| Method | Behaviour |
|---|---|
| `get(key)` | SELECT exact cacheKey, LIMIT 1 |
| `insertIgnore(entry)` | INSERT OR IGNORE — no-op if key exists |
| `updateBody(cacheKey, body, cachedAt)` | UPDATE responseBody + cachedAt by cacheKey |
| `upsert(entry)` | `insertIgnore` then `updateBody` — atomic two-step upsert pattern |
| `evictOlderThan(before)` | DELETE where cachedAt < before, **exempts** `login2::%` and `ua::last` — login data must survive indefinitely |
| `observePatchEntries()` | `Flow<List<CacheEntry>>` of all `patch:*` rows, ORDER BY firstCachedAt DESC |
| `getByPrefix(prefix)` | SELECT first entry where cacheKey LIKE prefix%, LIMIT 1 |
| `getAllByPrefix(prefix)` | SELECT all entries where cacheKey LIKE prefix% |
| `deleteByKeyPrefix(prefix)` | DELETE all entries where cacheKey LIKE prefix% |

### PendingAwardDao — Notable Behaviours

| Method | Behaviour |
|---|---|
| `observe()` | `Flow<List<PendingAward>>` ORDER BY queuedAt ASC |
| `getAll()` | suspend, same query as observe() |
| `getLatest()` | ORDER BY queuedAt DESC, LIMIT 1 |
| `upsert(award)` | INSERT with `OnConflictStrategy.REPLACE` (replaces by achievementId unique index) |
| `delete(award)` | standard Room @Delete |
| `update(award)` | standard Room @Update |
| `observeCount()` | `Flow<Int>` of `SELECT COUNT(*)` |

### Transient UI Models (not Room entities)

**`CachedGame`** — derived from `patch:*` cache entries in `MainViewModel`:
- `gameId`, `title`, `user`, `cachedAt`, `imageIconUrl`, `unlockedCount`, `totalAchievements`

**`PendingAwardUi`** — resolved from `PendingAward` by searching all `patch:*` entries for matching achievement:
- `gameTitle`, `gameIconUrl`, `achievementTitle`, `points`, `badgeUrl`, `hardcore`, `lastError`

**`Achievement`** — placeholder model (not yet wired to any adapter or UI):
- `id`, `title`, `description`, `points`, `badgeUrl`, `unlocked`, `unlockedHardcore`

---

## Proxy Server (`ProxyServer.kt`)

Raw `ServerSocket` on port 8080. Each connection handled by a `CachedThreadPool` thread. HTTP parsed manually (no HTTP library for the inbound leg). OkHttp only used for the outbound upstream leg.

### File-level Constants

```kotlin
private val AWARD_ACTIONS = setOf("awardachievement", "submitlbentry")
private val FAKE_OFFLINE_SUCCESS_ACTIONS = setOf("ping")
private val CACHEABLE_ACTIONS = setOf("patch", "gameid", "achievements", "hashlibrary", "login2", "unlocks", "startsession")
private val SKIP_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding")
```

### Constructor

```kotlin
class ProxyServer(
    db: AppDatabase,
    scope: CoroutineScope,
    isOnline: () -> Boolean
)
```

- `executor`: `Executors.newCachedThreadPool()` — unbounded thread pool for connections
- `httpClient`: `OkHttpClient.Builder().build()` — default config, no custom timeouts
- `running`: `@Volatile var`, publicly readable, privately settable

### Request Lifecycle

1. `start()` — creates `ServerSocket(PROXY_PORT)`, launches `acceptLoop()` on executor
2. `acceptLoop()` — infinite loop calling `ss.accept()`, dispatching each socket to `handleConnection()` on executor
3. `handleConnection(socket)` — uses `socket.use {}`:
   - Parses HTTP request line (method + path)
   - Reads headers into `Map<String, String>` (keys lowercased)
   - Reads body based on Content-Length
   - Calls `processRequest()` to get raw HTTP response string
   - Writes response via PrintWriter
4. `stop()` — sets `running = false`, closes serverSocket

### Request Routing (`processRequest`)

On every request, the user-agent header is cached to `ua::last` via coroutine launch.

| Condition | Action |
|---|---|
| action in `AWARD_ACTIONS` (`awardachievement`, `submitlbentry`) | `handleAwardRequest()` |
| `h=1` (any non-award action) | Bypass cache; forward to RA via OkHttp if online, 503 if offline |
| action in `FAKE_OFFLINE_SUCCESS_ACTIONS` (`ping`) + offline | Return `{"Success":true}` |
| online | `handleOnlineRequest()` — forward + cache |
| offline | `handleOfflineRequest()` — serve from cache or 503 |

### Award Handling (`handleAwardRequest`)

- `h=1` → HTTP 403, body `{"Success":false,"Error":"hardcore_not_supported"}`
- Online + upstream returns success → return upstream response directly
- Online + upstream fails, or offline → `queueAward()` + return synthetic success JSON:
  ```json
  {"Success":true,"Score":<cached_score>,"SoftcoreScore":0,"AchievementID":0,"Error":"queued_offline"}
  ```

`queueAward()` extracts the `a` parameter (achievement ID) and upserts a `PendingAward`.

`fetchCachedScore()` looks up the user's `login2::$user` cache entry and extracts the `Score` field from the JSON. Uses `CountDownLatch(1)` with 3s timeout.

### Online Request Handling (`handleOnlineRequest`)

1. Forwards request to RA via `forwardToRA()`
2. Validates response is JSON with `Success` field
3. If valid + action in `CACHEABLE_ACTIONS` → upserts response into `api_cache`
4. If action is `patch` → also triggers `cacheUnlocks()` for that game (extracts `g`, `u`, `t` params)
5. If upstream returns non-JSON or error → forwards as-is (non-cacheable) or returns 503

### Offline Request Handling (`handleOfflineRequest`)

- Non-cacheable action → 503
- Uses `CountDownLatch(1)` with 3-second timeout to bridge blocking socket thread and coroutine DB access. **This is intentional — do not replace with `runBlocking`.**
- Cache lookup: tries exact key first, then prefix fallback (`getByPrefix("$key:")`)
- Cache hit → returns cached JSON (200)
- Cache miss for `gameid` action → special 200 response: `{"Success":false,"Error":"Game not cached...","GameID":0}`
- Cache miss otherwise → 503

### Header Forwarding (`forwardToRA`)

All request headers are forwarded to RA except those in `SKIP_HEADERS`. This is critical because RA's server 403s requests without the `rcheevos` User-Agent.

POST bodies are sent with `Content-Type: application/x-www-form-urlencoded`.

### Cache Key Construction (`cacheKey`)

```
action="gameid" → "gameid:$hash"
no hardcore     → "$action:$gameId:$user"
with hardcore   → "$action:$gameId:$user:$hardcore"  (hardcore keys are never actually cached)
```

The `g` parameter is checked first, then `i` parameter as fallback for gameId.

### Parameter Extraction (`extractParam`, `extractAction`)

Both methods check URL query parameters first (via OkHttp's `HttpUrl.queryParameter()`), then fall back to parsing form body (`&`-separated, URL-decoded values). The action is always the `r` parameter.

### Response Building

- `httpOk(body)` — raw `HTTP/1.1 200 OK` with `Content-Type: application/json`, `Content-Length`, `Connection: close`
- `httpError(code, message)` — raw `HTTP/1.1 $code $message` with JSON body `{"Success":false,"Error":"$message"}`
- `httpGameIdCacheMiss()` — 200 OK with `{"Success":false,"Error":"Game not cached. Launch this game while online first.","GameID":0}`

---

## Award Flusher (`AwardFlusher.kt`)

### Event Bus

`companion object` holds a process-level `MutableSharedFlow<FlushEvent>(extraBufferCapacity = 8)` exposed as `SharedFlow`. This decouples `MainViewModel` from `ProxyService` — the ViewModel can observe flush progress without a direct reference.

```kotlin
sealed interface FlushEvent {
    data object Started
    data class Progress(val current: Int, val total: Int)
    data class Completed(val flushed: Int, val total: Int)
}
```

### Internal Result Type

```kotlin
private sealed interface FlushResult {
    data object Success
    data class AuthError(val message: String)
    data class NetworkError(val message: String)
}
```

### Flush Logic

- `flush()` runs on `Dispatchers.IO`
- Gets all pending awards from DB (ordered by queuedAt ASC)
- Emits `Started`, then for each award:
  1. **Hardcore check** (`isHardcoreAward`) — if `h=1` in queryString or requestBody, silently deletes + counts as flushed (stale cleanup)
  2. **`sendAward()`** → POSTs to `$RA_HOST${award.queryString}` with `award.requestBody` and `award.userAgent`
  3. On `Success` → deletes award from DB
  4. On `AuthError` → updates `lastError` on award, **does not retry** (keywords: `"Invalid"`, `"token"`, `"credentials"`, `"user"` in error string, or HTTP 401/403)
  5. On `NetworkError` → increments `retryCount`, updates `lastError`. If `retryCount >= MAX_RETRIES (5)`, stops retrying (award remains in DB)
- Emits `Completed(flushed, total)`

### Flush Triggers

- `ProxyService.networkCallback.onAvailable()` — connectivity restored
- `ProxyService.onStartCommand()` — if device is online at service start

---

## ROM Scanner (`RomScanner.kt`)

Top-level functions, no class. Package: `com.raofflineproxy.proxy`.

### Data Types

```kotlin
data class ScanResult(val matched: Int, val total: Int, val skipped: Int)
data class LoginCredentials(val user: String, val token: String)
```

### Public Functions

| Function | Signature | Purpose |
|---|---|---|
| `loadLoginCredentials(db)` | `suspend` → `LoginCredentials?` | Reads first `login2::*` cache entry, parses `User` + `Token` from JSON |
| `loadUserAgent(db)` | `suspend` → `String` | Reads `ua::last` from cache, falls back to `"rcheevos/11.4.0"` |
| `scanRomFolder(context, treeUri, creds, userAgent, db, singleFile, onProgress)` | `suspend` → `ScanResult` | Walks SAF tree or single file, MD5-hashes each ROM, looks up gameId, caches game data |

### Scan Flow

1. If `singleFile=true`: wraps URI as `DocumentFile.fromSingleUri`
2. Otherwise: `DocumentFile.fromTreeUri(treeUri).listFiles()`, filters out dot-files and `.txt` files
3. For each file: `md5File()` → `fetchGameId()` → `cacheGame()`
4. 500ms delay between files to avoid hammering the server

### Internal Functions

| Function | Visibility | Purpose |
|---|---|---|
| `md5File(context, uri)` | private | Reads file via `ContentResolver.openInputStream()`, computes MD5 hex digest (8KB buffer) |
| `fetchGameId(hash, creds, userAgent)` | private | GETs `$PROXY_BASE/dorequest.php?r=gameid&m=$hash&u=...&t=...` **via the local proxy** (not direct to RA), parses `GameID` from JSON. Returns null if GameID <= 0 |
| `cacheGame(gameId, creds, userAgent, db)` | `internal suspend` | 1. GETs `patch` via local proxy (caches as side effect) 2. Calls `cacheUnlocks()` 3. Calls `cacheSession()` |
| `cacheUnlocks(gameId, creds, userAgent)` | `internal` | GETs `unlocks` with `h=0` via local proxy |
| `cacheSession(gameId, creds, db)` | `internal suspend` | Builds fake startsession JSON from cached unlocks, upserts directly into DB |
| `buildUnlocksArray(db, gameId, user, serverNow)` | `private suspend` | Reads `unlocks:$gameId:$user:0` from cache, extracts `UserUnlocks` array IDs, wraps each in `{ID, When}` |
| `get(url, userAgent)` | private | Basic `HttpURLConnection` GET with 10s connect/read timeouts, `User-Agent` and `Accept-Encoding: identity` headers |

### Synthesized startsession Response

**Never call the live `startsession` endpoint.** Always synthesize it from cached unlocks:

```json
{
  "Success": true,
  "ServerNow": <epoch_seconds>,
  "HardcoreUnlocks": [],
  "Unlocks": [
    { "ID": <id>, "When": <epoch_seconds> },
    ...
  ]
}
```

`HardcoreUnlocks` is always `[]`. `When` values all use the current timestamp.

### Important: fetchGameId routes through local proxy

`fetchGameId()` calls `$PROXY_BASE/dorequest.php?r=gameid&...`, which routes through the local `ProxyServer`, not directly to `retroachievements.org`. This means the gameid response gets cached in `api_cache` as a side effect.

---

## RetroArch CFG Patcher (`RetroArchCfgPatcher.kt`)

Top-level functions only (no class). Package: `com.raofflineproxy.ui`.

### Source Candidates (direct file paths tried in order)

```
/sdcard/Android/data/com.retroarch.aarch64/files/retroarch.cfg
/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
/sdcard/Android/data/com.retroarch/files/retroarch.cfg
/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg
/sdcard/RetroArch/retroarch.cfg
/storage/emulated/0/RetroArch/retroarch.cfg
```

### SAF Path Segments (tried in order for tree URI navigation)

```
["com.retroarch.aarch64", "files", "retroarch.cfg"]
["com.retroarch",         "files", "retroarch.cfg"]
["files",                 "retroarch.cfg"]
["retroarch.cfg"]
```

These handle varying SAF grant root depths (Android/data/, package dir, files/ dir, root).

### Staging Directory

- Work directory: `/sdcard/RAOfflineProxy`
- Work cfg: `/sdcard/RAOfflineProxy/retroarch.cfg`

### `PatchResult` Data Class

```kotlin
data class PatchResult(
    val success: Boolean,
    val message: String,
    val needsSafGrant: Boolean = false,
    val copyBackPath: String? = null
)
```

### Four-tier Access Strategy (tried in order)

1. **SAF tree URI** (if granted) — `patchViaSaf()` iterates `SAF_CFG_PATHS`, reads + patches + writes via ContentResolver
2. **Direct `File` write** (if file exists and is writable) — `writeViaFile()` reads + patches + writes directly
3. **Prompt for SAF grant** — returns `PatchResult(needsSafGrant = true)` for Android <= S_V2 (API 32) when file found but not writable, or tree URI not granted
4. **Staging** — `stagingPatch()` copies cfg to `/sdcard/RAOfflineProxy/retroarch.cfg`, patches there, attempts copy back. If copy-back fails, returns `copyBackPath` for manual user action

### Content Transforms

**`buildPatchedContent(content)`** — regex replaces (or appends if not present):
- `cheevos_custom_host = "127.0.0.1:8080"`
- `cheevos_hardcore_mode_enable = "false"`

**`buildRevertedContent(content)`** — regex replaces:
- `cheevos_custom_host = ""`

Both use `Regex` with `RegexOption.MULTILINE` to match full lines and preserve leading whitespace.

**`isPatchedContent(content)`** — checks if `cheevos_custom_host` value equals `"127.0.0.1:8080"` (exact match with escaped proxy value).

**`checkIsPatched(context, treeUri)`** — tries SAF paths first, then direct file paths. Returns boolean.

### Revert Functions

Mirror the patch functions exactly: `revertRetroArchCfg()`, `revertViaSaf()`, `revertViaFile()`, `stagingRevert()`.

---

## Proxy Service (`ProxyService.kt`)

Foreground service that owns `ProxyServer` + `AwardFlusher`.

### Constants

```kotlin
private const val CHANNEL_ID = "proxy_service"
private const val NOTIFICATION_ID = 1
private const val REFRESH_INTERVAL_MS = 3_600_000  // 1 hour
```

### Lifecycle

| Lifecycle Method | Behaviour |
|---|---|
| `onCreate()` | Initializes `db`, `awardFlusher`, `proxyServer(db, serviceScope) { isOnline }`, `connectivityManager` |
| `onStartCommand()` | Creates notification channel, starts foreground, checks initial online state, registers network callback, starts proxy, flushes awards if online, launches periodic refresh loop. Returns `START_STICKY` |
| `onDestroy()` | Stops proxy, unregisters network callback, cancels coroutine scope |
| `onBind()` | Returns null (unbound service) |

### Network Callback

- `onAvailable` → sets `isOnline = true`, launches `awardFlusher.flush()`, updates notification
- `onLost` → sets `isOnline = false`, updates notification

### Periodic Refresh Loop

Infinite loop: delays 1 hour, then if online:
1. Loads credentials and user agent from cache
2. Gets all `patch:*` cache keys, extracts distinct gameIds
3. Calls `cacheGame()` for each gameId (refreshes patch, unlocks, startsession data)

### Notifications

- Channel: `IMPORTANCE_LOW` (no sound/vibration)
- Online: title "Online", text "Forwarding to RA"
- Offline: title "Offline", text "Serving from cache"
- Small icon: `ic_proxy`
- Tap opens `MainActivity`
- Always `ongoing = true`

### Companion Object

```kotlin
fun start(context: Context) = context.startForegroundService(...)
fun stop(context: Context) = context.stopService(...)
```

---

## Boot Receiver (`BootReceiver.kt`)

- Guards on `ACTION_BOOT_COMPLETED`
- Reads `"ra_proxy_prefs"` SharedPreferences for `"autostart_proxy"` boolean
- Reads `"saf_tree_uri"` string, parses to Uri
- Calls `checkIsPatched(context, treeUri)` — **both the autostart pref AND cfg patched check must pass**
- If patched → `ProxyService.start(context)`

---

## UI State (`MainViewModel.kt`)

### AuthState Enum

```kotlin
enum class AuthState { Unknown, Valid, Invalid }
```

### MainUiState

Single data class — all state in one immutable snapshot. All mutations use `_state.value = _state.value.copy(...)`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `proxyRunning` | `Boolean` | `false` | Service is started |
| `isOnline` | `Boolean` | `false` | Network available |
| `authState` | `AuthState` | `Unknown` | Token validity |
| `autostartProxy` | `Boolean` | `false` | Pref value |
| `pendingAwards` | `List<PendingAwardUi>` | `emptyList()` | Resolved UI models |
| `cachedGames` | `List<CachedGame>` | `emptyList()` | Derived from patch cache entries |
| `cfgPatchMessage` | `String?` | `null` | Transient snackbar text |
| `cfgPatchSuccess` | `Boolean?` | `null` | Patch outcome |
| `needsSafGrant` | `Boolean` | `false` | Show "Grant Folder Access" button |
| `cfgCopyBackPath` | `String?` | `null` | Manual copy-back instruction |
| `cfgIsPatched` | `Boolean?` | `null` | null = not yet checked |
| `cfgHardcoreWasEnabled` | `Boolean` | `false` | Reserved for future use |
| `scanInProgress` | `Boolean` | `false` | ROM scan running |
| `scanProgress` | `String?` | `null` | Scan status / result message |
| `flushInProgress` | `Boolean` | `false` | Award flush running |
| `flushProgress` | `String?` | `null` | Flush status / result message |
| `clearCacheMessage` | `String?` | `null` | Transient clear-cache confirmation |
| `clearDatabaseMessage` | `String?` | `null` | Transient clear-database confirmation |

### ViewModel Initialization (init block)

Runs in order:
1. Checks initial online state via `connectivityManager.activeNetwork`
2. Registers `NetworkCallback` for `NET_CAPABILITY_INTERNET`
3. Calls `checkCfgPatched()` with saved SAF URI from prefs
4. Loads autostart pref into state
5. Calls `validateToken()`
6. Launches collector for `AwardFlusher.events` → updates `flushInProgress` + `flushProgress`
7. Launches collector for `pendingAwardDao.observe()` → maps each `PendingAward` to `PendingAwardUi` via `resolvePendingAward()`
8. Launches collector for `cacheDao.observePatchEntries()` → maps each patch `CacheEntry` to `CachedGame` (parses PatchData JSON for title, ImageIcon, Achievements count; looks up matching unlocks entry for unlock count)

### Key Methods

| Method | Behaviour |
|---|---|
| `clearTransientMessages()` | Nulls `scanProgress`, `cfgPatchMessage`, `cfgPatchSuccess`, `cfgCopyBackPath`, `clearCacheMessage`, `clearDatabaseMessage`, resets `needsSafGrant` |
| `validateToken()` | Loads credentials from cache; if offline, trusts cache (sets Valid); if online, makes a live `patch` request **directly to RA** (not via proxy) to verify token. No cached games → trusts cache |
| `onProxyStarted()` / `onProxyStopped()` | Sets `proxyRunning` state |
| `checkCfgPatched(treeUri?)` | Calls `checkIsPatched()` on IO dispatcher |
| `patchCfg(treeUri?)` | Calls `patchRetroArchCfg()`, updates state with result including `cfgIsPatched = true` on success |
| `revertCfg(treeUri?)` | Calls `revertRetroArchCfg()`, updates state with result including `cfgIsPatched = false` on success |
| `addRom(fileUris)` | Loads credentials, iterates URIs, calls `scanRomFolder()` for each (singleFile=true), reports progress via `scanProgress` |
| `scanRoms(treeUri)` | Clears gameid cache first, then calls `scanRomFolder()` for tree, reports progress |
| `deleteCachedGame(game)` | Deletes `patch:$gameId:*` entries only (leaves unlocks/startsession behind — known gap) |
| `refreshGames()` | Iterates cached games (reversed order = oldest first), calls `cacheGame()` for each, reports progress |
| `clearCache()` | Deletes all `patch:*`, `gameid:*`, `unlocks:*`, `startsession:*` cache entries |
| `clearDatabase()` | Deletes ALL cache entries (`deleteByKeyPrefix("")`), deletes all pending awards |
| `setAutostartProxy(enabled)` | Persists to SharedPreferences |

### Resolving PendingAward to PendingAwardUi

`resolvePendingAward()` concatenates `queryString + "&" + requestBody`, splits on `&`, URL-decodes values. Extracts `a` (achievement ID) and `h` (hardcore flag). Then searches all `patch:*` cache entries for an achievement with matching ID to extract game title, game icon, achievement title, points, and badge URL.

Badge URL format: `https://i.retroachievements.org/Badge/$badgeName.png`
Game icon URL format: `$RA_HOST$imageIcon` (where `imageIcon` is like `/Images/012345.png`)

### Token Validation (`validateToken`)

1. Loads credentials from cache
2. No credentials → sets `AuthState.Invalid`
3. Offline → trusts cache, sets `AuthState.Valid`
4. Online but no cached games → trusts cache, sets `AuthState.Valid`
5. Online with cached games → makes a live `patch` request to `$RA_HOST/dorequest.php?r=patch&g=$gameId&u=...&t=...` with 10s timeouts and stored user agent. Checks `Success` field in JSON response

### SharedPreferences

- **Name**: `"ra_proxy_prefs"` (`Context.MODE_PRIVATE`)
- **Keys**:
  - `"autostart_proxy"` → `Boolean`, default `false`
  - `"saf_tree_uri"` → `String` (persisted SAF tree URI), accessed in `loadSafUri()` and written in `RetroArchSetupFragment`

### Dual State Flows for Cached Games

The ViewModel maintains two parallel state flows for cached games:
- `_state.cachedGames` — part of `MainUiState`, consumed by fragments via `state.collect{}`
- `_cachedGames` / `cachedGames` — standalone `StateFlow<List<CachedGame>>`, consumed directly by `CachedGamesFragment` for the 300ms-delayed initial population

Both are updated simultaneously in the patch entries collector.

---

## Navigation

Manual fragment replacement via `supportFragmentManager.beginTransaction().replace()`. No Jetpack Navigation component. No back stack.

| Drawer Item | Fragment | Action Bar Title |
|---|---|---|
| Home | `HomeFragment` | App name |
| RetroArch Setup | `RetroArchSetupFragment` | "RetroArch Setup" |
| Cached Games | `CachedGamesFragment` | "Cached Games" |
| Pending Awards | `PendingAwardsFragment` | "Pending Awards" |
| Settings | `SettingsFragment` | "Settings" |

### Drawer

- Two groups: `group_main` (Home, Setup, Cached Games, Pending Awards) and `group_bottom` (Settings, marked as secondary/checkable)
- Cached Games and Pending Awards items have `actionLayout="@layout/nav_item_count"` for badge text
- `updateNavBadge()` sets badge text to `"(N)"` or empty string

### Action Bar

Custom proxy toggle button (`action_proxy_button.xml`) in action bar:
- Label: "Start proxy" / "Stop proxy"
- Tooltip: "Proxy running — online" / "Proxy running — offline" / "Start proxy"
- Enabled/disabled based on `proxyRunning || cfgIsPatched == true`
- Alpha: 1.0 when enabled, 0.38 when disabled

### Fragment Lifecycle

`showFragment()` calls `viewModel.clearTransientMessages()` on every navigation to prevent stale snackbar messages.

All fragments share a single `MainViewModel` via `activityViewModels()`.

---

## Fragment Details

### HomeFragment

- Shows setup hint + "Go to RetroArch Setup" button if `cfgIsPatched == false`
- Shows token warning (orange text) if patched but `authState == Invalid`
- `onResume()` calls `viewModel.validateToken()` to re-check on every return to home

### RetroArchSetupFragment

- Uses custom `OpenAndroidDataTree` contract (extends `ActivityResultContract<Unit, Uri?>`) that opens `ACTION_OPEN_DOCUMENT_TREE` with initial URI pointing to `content://com.android.externalstorage.documents/document/primary:Android/data`
- On folder grant: takes persistable read+write URI permissions, saves to prefs, calls `viewModel.patchCfg(treeUri)`
- Buttons: Patch (visible when not patched), Revert (visible when patched, disabled if proxy running), Grant Folder Access (visible when `needsSafGrant`)
- Snackbar behaviour: copy-back instructions shown as `LENGTH_INDEFINITE` with "OK" dismiss action; errors as `LENGTH_INDEFINITE` with "OK"; success as `LENGTH_LONG`

### CachedGamesFragment

- `romFolderPickerLauncher` — `OpenDocumentTree`, takes read permission, calls `viewModel.scanRoms(uri)`
- `addRomLauncher` — `OpenMultipleDocuments` with `*/*` MIME, takes read permission for each URI, calls `viewModel.addRom(uris)`
- RecyclerView with `ConcatAdapter(headerAdapter, gamesAdapter)` + `DividerItemDecoration` + disabled change animations
- Collects `viewModel.cachedGames` with 300ms initial delay (avoids jank during drawer close animation)
- Header state: scan/add/refresh enabled only when `proxyRunning && isOnline && !scanInProgress`
- Snackbar: in-progress = `LENGTH_INDEFINITE` (text updated in-place), completion = `LENGTH_LONG`

### PendingAwardsFragment

- RecyclerView with `ConcatAdapter(headerAdapter, awardsAdapter)` + `DividerItemDecoration` + disabled change animations
- Header shows "No pending awards." when list empty
- Snackbar for flush progress: in-progress = `LENGTH_INDEFINITE`, completion = `LENGTH_LONG`

### SettingsFragment

- CheckBox for autostart (label formatted with app name: "Autostart RA Offline Proxy at startup")
- "Clear Cache" button → `viewModel.clearCache()`
- "Clear Database" button → `viewModel.clearDatabase()`
- Snackbar for clearCacheMessage and clearDatabaseMessage (LENGTH_LONG, calls `clearTransientMessages()` after display)

---

## Adapters

### CachedGamesAdapter

`ListAdapter<CachedGame, ViewHolder>` with `ItemCachedGameBinding`.

- Binds: game icon (Coil with `crossfade(true)`), title (bold 14sp), meta text (`"X / Y unlocked · date"` via `SimpleDateFormat("MMM d, HH:mm")`), delete button
- DiffUtil: items same by `gameId`, contents same by `==`
- `onDelete` callback passed in constructor

### CachedGamesHeaderAdapter

Single-item `RecyclerView.Adapter` (always `getItemCount() = 1`).

`HeaderState` data class:
- `scanEnabled`, `refreshEnabled`, `clearEnabled`, `showNoCachedGames`, `showScanHint`

4 MaterialButton icons (refresh, add ROM, scan folder, clear all) + 2 TextViews (scan hint, no cached games).

### PendingAwardsAdapter

`ListAdapter<PendingAwardUi, ViewHolder>` with `ItemPendingAwardBinding`.

- Binds: game icon (Coil), badge (Coil), game title (12sp), achievement title (14sp bold, with "(Hardcore)" suffix if `hardcore == true`), points (`"Xpts"`), lastError (11sp red, visible when non-null)
- DiffUtil: items same by `achievementTitle + gameTitle`, contents same by `==`

### PendingAwardsHeaderAdapter

Single-item adapter. Toggles visibility of "No pending awards." text based on `showEmpty` boolean.

Includes `<view_hardcore_warning>` layout.

---

## UI Patterns

- **Snackbar** for all transient messages. Never use `TextView` for status messages.
  - In-progress: `LENGTH_INDEFINITE`, update text in place via `sb.setText(msg); sb.show()`
  - Completion: `LENGTH_LONG`
  - Errors / instructions requiring user action: `LENGTH_INDEFINITE` with "OK" dismiss action
- **`ConcatAdapter`** used in `CachedGamesFragment` and `PendingAwardsFragment` to combine a single-item header adapter with the list adapter
- **Coil** for all remote image loading with `crossfade(true)`
- `CachedGamesFragment` defers list population by 300ms via `delay(300)` to avoid jank during drawer close animation
- **ViewBinding** is enabled but fragments use `findViewById` directly; only adapters and `MainActivity` use generated binding classes

---

## Theming and Resources

### Theme (`Theme.RAOfflineProxy`)

- Parent: `Theme.MaterialComponents.DayNight.DarkActionBar`
- `colorPrimary`: `#FF2C97FA` (blue)
- `colorPrimaryVariant`: `#FF1A7DD4`
- `colorOnPrimary`: white
- Custom bold action bar title via `TextAppearance.RAOfflineProxy.ActionBar.Title`
- Snackbar text size: 12sp

### Colors

| Name | Value | Usage |
|---|---|---|
| `primary` | `#FF2C97FA` | App primary blue |
| `primary_dark` | `#FF1A7DD4` | Status bar / primary variant |
| `white` | `#FFFFFFFF` | On-primary color |
| `pending_awards_color` | `#FFB300` | Amber (defined but not referenced in code) |
| `error_red` | `#D32F2F` | Error states |
| `success_green` | `#388E3C` | Success states |
| `ic_launcher_background` | `#FF1A1A2E` | Dark navy launcher background |

### Drawables

All vector drawables are 24dp with viewport 24x24 and tint `?attr/colorOnSurface` (except `ic_proxy` which uses `?attr/colorControlNormal`):
- `ic_proxy` — transfer arrows icon (notification + action bar)
- `ic_delete` — trash can (delete cached game)
- `ic_delete_sweep` — sweep/batch delete (clear all cache)
- `ic_refresh` — circular refresh arrow (refresh games)
- `ic_folder` — folder (scan ROM folder)
- `ic_add` — plus (add ROM)
- `ic_launcher_foreground` — 108dp adaptive icon: blue "R" (`#2196F3`) + orange "A" (`#FF6D00`)
- `logo.png` — binary PNG logo in `drawable-nodpi/`

### Launcher Icons

All 5 density buckets (mdpi through xxxhdpi) contain identical adaptive icon XML referencing `@color/ic_launcher_background` (background) and `@drawable/ic_launcher_foreground` (foreground).

### String Resources

70+ string resources covering all UI text. All user-visible strings go through `strings.xml` — no hardcoded strings in Kotlin code except for internal JSON responses and log tags.

Format strings use `%s`, `%d`, `%1$d/%2$d` positional placeholders. Fragment text is loaded via `getString(R.string.xxx)` or the ViewModel helper `str(R.string.xxx, ...)`.

---

## Autostart Constraint

Starting the proxy on boot or app launch is only allowed if **both** conditions are met:
1. `autostart_proxy` preference is `true`
2. `checkIsPatched()` returns `true`

The autostart pref alone is not sufficient.

- **`BootReceiver`**: reads SAF URI from prefs, calls `checkIsPatched()` before `ProxyService.start()`
- **`MainActivity`**: waits for `state.first { it.cfgIsPatched != null }` (async SAF check) before conditionally starting the proxy
- **Proxy button**: disabled (alpha=0.38) when `cfgIsPatched != true` and proxy is not already running

---

## Reusable Layout Component

`res/layout/view_hardcore_warning.xml` — an orange-background `TextView` (id `tv_hardcore_warning`) warning that hardcore mode is not supported. Background color: `#33FF6600` (translucent orange), text color: `@android:color/holo_orange_light`.

Included via `<include>` in:
- `fragment_retro_arch_setup.xml`
- `item_pending_awards_header.xml`

---

## Design Decisions and Conventions

### No Dependency Injection

All dependencies are manually wired. `AppDatabase` is a singleton via companion object. `ProxyServer` is created directly in `ProxyService.onCreate()`.

### CountDownLatch Bridging

`ProxyServer` uses `CountDownLatch(1)` with 3-second timeout to bridge blocking socket threads and coroutine DB access in `handleOfflineRequest()` and `fetchCachedScore()`. This is intentional — do not replace with `runBlocking`.

### Hardcore Rejection at Multiple Levels

1. **ProxyServer** — HTTP 403 for `h=1` award requests; bypass cache for non-award hardcore requests
2. **AwardFlusher** — silently deletes stale hardcore awards during flush
3. **RetroArchCfgPatcher** — forces `cheevos_hardcore_mode_enable = "false"` when patching

### Process-level Event Bus

`AwardFlusher.companion object` holds a `MutableSharedFlow<FlushEvent>` so any component in the process (including `MainViewModel`) can observe flush events without direct coupling to `ProxyService`.

### HTTP Protocol Handling

The proxy server implements raw HTTP/1.1 parsing and response building with manual string construction. Inbound parsing is minimal (request line + headers + body via Content-Length). Responses always include `Connection: close` — no keep-alive support.

### SAF Navigation Pattern

`DocumentFile.findFile()` calls are folded over path segment lists to handle varying SAF grant root depths. This allows the same code to work regardless of which directory level the user granted access to.

---

## Known Gaps / Future Work

- `Achievement.kt` and `item_achievement.xml` exist but no `AchievementAdapter` is wired — per-game achievement list is not displayed in the UI
- `deleteCachedGame()` only deletes `patch:gameId:*` entries; `unlocks:` and `startsession:` entries for that game are left behind
- `cfgHardcoreWasEnabled` is declared in `MainUiState` but not populated (reserved)
- `pending_awards_color` (`#FFB300`) is defined in `colors.xml` but not referenced in code or layouts
- `PendingAwardDao.observeCount()` is defined but not called anywhere
- `PendingAwardDao.getLatest()` is defined but not called anywhere
- `CacheDao.evictOlderThan()` is defined but not called from any scheduled cleanup — old cache entries are never automatically evicted
