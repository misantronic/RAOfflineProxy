package com.raofflineproxy.usage

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.diagnostics.DeviceInfo
import com.raofflineproxy.diagnostics.deviceInfo
import com.raofflineproxy.hasValidatedInternet
import com.raofflineproxy.proxy.CacheQueue
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.sha256Hex
import com.raofflineproxy.sharedHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RAProxy/Usage"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val CLIENT_ID_SALT = "raofflineproxy-usage:"
// Bump when an existing field changes meaning, so the backend can tell old reports from new ones.
internal const val USAGE_SCHEMA_VERSION = 1

internal data class UsageGauges(
    val cachedGames: Int,
    val queuedGames: Int,
    val oldestQueuedAgeMs: Long?,
    val pendingAwards: Int
)

/** The username is only hashed here; the backend re-keys that hash with a secret that rotates
 *  every month, so stored IDs can't be linked back to a username or across months. */
internal fun usageClientId(username: String): String = sha256Hex(CLIENT_ID_SALT + username.trim().lowercase())

/** One report per UTC calendar day, matching the backend's day rows. A clock that jumped to
 *  another day also counts as a new day. */
internal fun isUsageReportDue(lastReportedAt: Long, now: Long): Boolean =
    lastReportedAt <= 0L || Math.floorDiv(lastReportedAt, DAY_MS) != Math.floorDiv(now, DAY_MS)

internal fun buildUsagePayload(
    clientId: String,
    info: DeviceInfo,
    buildType: String,
    consentVersion: Int,
    gauges: UsageGauges,
    counters: Map<String, Long>
): JSONObject = JSONObject()
    .put("schema_version", USAGE_SCHEMA_VERSION)
    .put("consent_version", consentVersion)
    .put("client_id", clientId)
    .put("platform", "android")
    .put("os", "Android")
    .put("os_version", info.osVersion)
    .put("device", info.device)
    .put("app_version", info.appVersion)
    .put("build", buildType)
    .put("emulators", JSONArray(info.enabledEmulators))
    .put(
        "gauges",
        JSONObject()
            .put("cached_games", countBucket(gauges.cachedGames))
            .put("queued_games", countBucket(gauges.queuedGames))
            .put("oldest_queued", ageBucket(gauges.oldestQueuedAgeMs))
            .put("pending_awards", countBucket(gauges.pendingAwards))
    )
    .put("counters", JSONObject().apply { counters.forEach { (key, value) -> put(key, value) } })

internal object UsageReporter {
    private val mutex = Mutex()

    /** Sends at most one report per UTC day, only with consent and a validated connection. Counters
     *  are only cleared once the backend accepted them. */
    suspend fun reportIfDue(context: Context, db: AppDatabase, now: Long = System.currentTimeMillis()) {
        if (BuildConfig.USAGE_STATS_URL.isEmpty()) return
        if (PrefsConstants.loadUsageStatsConsent(context) != true) return
        // Callers run this inside the service's refresh loop and viewModelScope, where an uncaught
        // exception would stop the loop or crash the app.
        try {
            report(context, db, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Usage report skipped: ${e.message}")
        }
    }

    private suspend fun report(context: Context, db: AppDatabase, now: Long) {
        mutex.withLock {
            if (!isUsageReportDue(UsageStats.lastReportedAt(context), now)) return
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            if (!hasValidatedInternet(connectivityManager)) return
            val credentials = loadLoginCredentials(db) ?: return
            val counters = UsageStats.snapshot(context).reportable()
            val payload = buildUsagePayload(
                clientId = usageClientId(credentials.user),
                info = deviceInfo(context),
                buildType = BuildConfig.BUILD_TYPE,
                consentVersion = PrefsConstants.USAGE_STATS_CONSENT_VERSION,
                gauges = loadGauges(db, now),
                counters = counters
            )
            if (send(payload)) {
                UsageStats.commitReport(context, counters, now)
                Log.i(TAG, "Usage report sent")
            }
        }
    }

    private suspend fun loadGauges(db: AppDatabase, now: Long): UsageGauges = UsageGauges(
        cachedGames = db.cacheDao().getAllSummariesByPrefix(CacheKeys.PREFIX_PATCH)
            .mapNotNullTo(HashSet()) { CacheKeys.parseGameIdStringFromPatchKey(it.cacheKey) }
            .size,
        queuedGames = CacheQueue.count(db),
        oldestQueuedAgeMs = CacheQueue.oldest(db)?.queuedAt?.let { (now - it).coerceAtLeast(0L) },
        pendingAwards = db.pendingAwardDao().getAllByStatus().size
    )

    private suspend fun send(payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            sharedHttpClient.newCall(
                Request.Builder()
                    .url(BuildConfig.USAGE_STATS_URL)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "Usage report rejected (HTTP ${response.code})")
                response.isSuccessful
            }
        }.onFailure { error ->
            Log.w(TAG, "Usage report failed: ${error.message}")
        }.getOrDefault(false)
    }
}
