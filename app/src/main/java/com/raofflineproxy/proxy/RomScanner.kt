package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.buildApiUrl
import com.raofflineproxy.proxy.hash.hashRomCandidates
import com.raofflineproxy.proxyHost
import com.raofflineproxy.proxyPort
import com.raofflineproxy.redactTokens
import com.raofflineproxy.throttleRetroAchievementsApiRequest
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.PENDING_AWARD_STATUS_PENDING
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.parseFormParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "RAProxy"
private const val HTTP_ERROR_BODY_LOG_LIMIT = 512
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_RETRY_AFTER_HEADER = "Retry-After"
private const val HTTP_GET_MAX_429_RETRIES = 4
private const val HTTP_GET_INITIAL_429_BACKOFF_MS = 2_000L
private const val HTTP_GET_MAX_429_BACKOFF_MS = 15_000L
private const val SCAN_CACHE_PIPELINE_LIMIT = 6

private val FALLBACK_USER_AGENT = "RetroArch/1.21.0 (Android ${Build.VERSION.RELEASE ?: "Unknown"})"

data class ScanResult(
    val matched: Int,
    val total: Int,
    val skipped: Int,
    val limitReached: Boolean = false
)

internal suspend fun loadCachedRomPaths(db: AppDatabase): MutableSet<String> =
    db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
        .mapNotNull { entry -> entry.sourceRomPath?.normalizeCachedRomPath() }
        .toMutableSet()

data class LoginCredentials(val user: String, val token: String)

data class PasswordCredentials(val user: String, val password: String)

internal enum class RefreshEndpoint {
    Patch,
    AchievementSets
}

internal data class CachedGameRefreshTarget(
    val gameId: Int,
    val user: String,
    val sourceRomPath: String? = null,
    val endpoint: RefreshEndpoint,
    val romHash: String? = null
)

internal enum class RefreshNotificationMode {
    Foreground,
    Background
}

internal sealed interface HttpGetResult {
    data class Success(val body: String) : HttpGetResult
    data class Failure(
        val kind: String,
        val statusCode: Int? = null,
        val reason: String? = null,
        val bodySnippet: String? = null,
        val exceptionMessage: String? = null
    ) : HttpGetResult {
        fun logMessage(action: String, url: String): String {
            val target = redactTokens(url)
            return when (kind) {
                "http" -> buildString {
                    append("$action request failed for $target")
                    if (statusCode != null) append(" (HTTP $statusCode")
                    if (!reason.isNullOrBlank()) append(" $reason")
                    if (statusCode != null) append(')')
                    if (!bodySnippet.isNullOrBlank()) append(" body=$bodySnippet")
                }

                else -> buildString {
                    append("$action request failed for $target")
                    if (!exceptionMessage.isNullOrBlank()) append(": $exceptionMessage")
                }
            }
        }

        fun userMessage(context: Context, action: String): String = when (kind) {
            "http" -> context.getString(
                R.string.request_failed_http,
                action,
                statusCode ?: 0,
                reason ?: context.getString(R.string.request_error_unknown_reason)
            )

            else -> context.getString(
                R.string.request_failed_network,
                action,
                exceptionMessage ?: context.getString(R.string.request_error_unknown_reason)
            )
        }
    }
}

suspend fun loadLoginCredentials(db: AppDatabase): LoginCredentials? {
    val entry = db.cacheDao().getByPrefix(CacheKeys.PREFIX_LOGIN) ?: return null
    return try {
        val json = JSONObject(entry.responseBody)
        val user = json.optString("User").takeIf { it.isNotEmpty() } ?: return null
        val token = json.optString("Token").takeIf { it.isNotEmpty() } ?: return null
        LoginCredentials(user, token)
    } catch (_: Exception) { null }
}

suspend fun loadUserAgent(db: AppDatabase): String =
    db.cacheDao().get(CacheKeys.USER_AGENT)?.responseBody?.takeIf { it.isNotEmpty() }
        ?: FALLBACK_USER_AGENT

fun cacheLoginCredentialsResponse(user: String, token: String): String =
    JSONObject().apply {
        put("Success", true)
        put("User", user)
        put("Token", token)
    }.toString()

suspend fun loginAndCacheToken(
    context: Context,
    db: AppDatabase,
    credentials: PasswordCredentials,
    userAgent: String
): LoginCredentials? {
    val url = buildApiUrl(
        RA_HOST,
        "login2",
        mapOf(
            "u" to credentials.user,
            "p" to credentials.password
        )
    )
    return when (val result = httpGet(url, proxyUserAgent(userAgent))) {
        is HttpGetResult.Success -> {
            val responseBody = result.body
            val json = JSONObject(responseBody)
            val user = json.optString("User").takeIf { it.isNotEmpty() } ?: credentials.user
            val token = json.optString("Token").takeIf { it.isNotEmpty() } ?: return null
            if (!json.optBoolean("Success", false)) return null

            db.cacheDao().upsert(CacheEntry(cacheKey = CacheKeys.login(user), responseBody = responseBody))
            val proxyBaseUrl = "http://${proxyHost()}:${proxyPort(context)}"
            rewriteImageUrls("login2", responseBody, proxyBaseUrl) { originalUrl, imagePath ->
                scheduleImageDownload(context, originalUrl, imagePath, userAgent)
            }
            LoginCredentials(user, token)
        }

        is HttpGetResult.Failure -> {
            Log.w(TAG, result.logMessage("login2", url))
            null
        }
    }
}

suspend fun refreshGamePatch(
    context: Context,
    gameId: Int,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    cacheImages: Boolean = true,
): String? {
    val url = buildApiUrl(
        RA_HOST,
        "patch",
        mapOf(
            "g" to gameId.toString(),
            "u" to creds.user,
            "t" to creds.token
        )
    )
    val responseBody = when (val result = httpGet(url, userAgent)) {
        is HttpGetResult.Success -> result.body
        is HttpGetResult.Failure -> {
            val logDetails = result.logMessage("patch", url)
            Log.e(TAG, "refreshGamePatch failed for gameId=$gameId: $logDetails")
            RequestFailureNotifier.report(result.userMessage(context, "patch"), logDetails)
            return null
        }
    }
    val json = runCatching { JSONObject(responseBody) }.getOrNull()
    if (json == null || !json.optBoolean("Success", false)) {
        Log.e(TAG, "refreshGamePatch returned invalid response for gameId=$gameId url=${redactTokens(url)}")
        RequestFailureNotifier.report(
            context.getString(R.string.request_failed_invalid_response, "patch"),
            "patch invalid response url=${redactTokens(url)}"
        )
        return null
    }
    val normalizedBody = normalizeCachedResponse("patch", "", "", responseBody)
    db.cacheDao().upsert(CacheEntry(
        cacheKey = CacheKeys.patch(gameId, creds.user),
        responseBody = normalizedBody
    ))
    Log.i(TAG, "refreshGamePatch: updated cache for gameId=$gameId")

    if (cacheImages) {
        val proxyBaseUrl = "http://${proxyHost()}:${proxyPort(context)}"
        rewriteImageUrls("patch", normalizedBody, proxyBaseUrl) { originalUrl, imagePath ->
            scheduleImageDownload(context, originalUrl, imagePath, userAgent, gameId)
        }
    }
    return normalizedBody
}

internal suspend fun loadCachedGameRefreshTargets(db: AppDatabase): List<CachedGameRefreshTarget> {
    val patchEntries = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
    val achievementSetEntries = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_ACHIEVEMENTSETS)
    val achievementSetsByGameAndUser = buildMap<Pair<Int, String>, String> {
        achievementSetEntries.forEach { entry ->
            val user = CacheKeys.parseUserFromAchievementSetsKey(entry.cacheKey) ?: return@forEach
            val hash = CacheKeys.parseAchievementSetsHash(entry.cacheKey) ?: return@forEach
            val gameId = runCatching {
                JSONObject(normalizeCachedResponse("achievementsets", "", "u=$user&m=$hash", entry.responseBody))
                    .getJSONObject("PatchData")
                    .optInt("ID")
            }.getOrDefault(0)
            if (gameId > 0) {
                putIfAbsent(gameId to user, hash)
            }
        }
    }

    return patchEntries.mapNotNull { entry ->
        val gameId = CacheKeys.parseGameIdFromPatchKey(entry.cacheKey) ?: return@mapNotNull null
        val user = CacheKeys.parseUserFromPatchKey(entry.cacheKey) ?: return@mapNotNull null
        val endpointHash = achievementSetsByGameAndUser[gameId to user]
        CachedGameRefreshTarget(
            gameId = gameId,
            user = user,
            sourceRomPath = entry.sourceRomPath,
            endpoint = if (endpointHash != null) RefreshEndpoint.AchievementSets else RefreshEndpoint.Patch,
            romHash = endpointHash
        )
    }
}

internal suspend fun refreshCachedGameOfflineBundle(
    context: Context,
    target: CachedGameRefreshTarget,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    notificationMode: RefreshNotificationMode,
    cacheImages: Boolean = true,
): Boolean {
    val action = if (target.endpoint == RefreshEndpoint.AchievementSets && !target.romHash.isNullOrBlank()) {
        "achievementsets"
    } else {
        "patch"
    }
    val requestParams = buildMap {
        put("u", creds.user)
        put("t", creds.token)
        if (action == "achievementsets") {
            put("m", target.romHash.orEmpty())
        } else {
            put("g", target.gameId.toString())
        }
    }
    val patchUrl = buildApiUrl(RA_HOST, action, requestParams)
    when (val result = httpGet(patchUrl, userAgent)) {
        is HttpGetResult.Success -> {
            val rawQuery = requestParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            val normalizedBody = normalizeCachedResponse(action, "", rawQuery, result.body)
            val normalizedJson = runCatching { JSONObject(normalizedBody) }.getOrNull()
            if (normalizedJson?.optBoolean("Success", false) != true) {
                reportRefreshFailure(
                    context = context,
                    action = action,
                    userMessage = context.getString(R.string.request_failed_invalid_response, action),
                    logDetails = "$action invalid response url=${redactTokens(patchUrl)}",
                    notificationMode = notificationMode
                )
                return false
            }

            // Write to DB first so the entry is visible in the UI immediately.
            if (action == "achievementsets") {
                val achievementSetsKey = CacheKeys.achievementSets(target.romHash.orEmpty(), creds.user)
                val rawBodyToCache = compactCachedRawResponse(action, result.body)
                db.cacheDao().upsert(
                    CacheEntry(
                        cacheKey = achievementSetsKey,
                        responseBody = rawBodyToCache
                    )
                )
            }
            db.cacheDao().upsert(
                CacheEntry(
                    cacheKey = CacheKeys.patch(target.gameId, creds.user),
                    responseBody = normalizedBody,
                    sourceRomPath = target.sourceRomPath
                )
            )

            if (cacheImages) {
                val proxyBaseUrl = "http://${proxyHost()}:${proxyPort(context)}"
                rewriteImageUrls(action, result.body, proxyBaseUrl) { originalUrl, imagePath ->
                    scheduleImageDownload(context, originalUrl, imagePath, userAgent, target.gameId)
                }
            }
        }
        is HttpGetResult.Failure -> {
            reportRefreshFailure(
                context = context,
                action = action,
                userMessage = result.userMessage(context, action),
                logDetails = result.logMessage(action, patchUrl),
                notificationMode = notificationMode
            )
            return false
        }
    }

    val unlocksOk = cacheUnlocks(context, target.gameId, creds, userAgent, db, notificationMode)
    cacheSession(target.gameId, creds, db)
    Log.i(TAG, "refreshCachedGameOfflineBundle complete for gameId=${target.gameId} endpoint=$action")
    return unlocksOk
}

private fun reportRefreshFailure(
    context: Context,
    action: String,
    userMessage: String,
    logDetails: String,
    notificationMode: RefreshNotificationMode
) {
    Log.e(TAG, "refresh failure action=$action: $logDetails")
    if (notificationMode == RefreshNotificationMode.Foreground) {
        RequestFailureNotifier.report(userMessage, logDetails)
    }
}

suspend fun scanRomFolder(
    context: Context,
    treeUri: Uri,
    credentials: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    singleFile: Boolean = false,
    onProgress: (current: Int, total: Int, fileName: String) -> Unit
): ScanResult {
    val cachedGameIds = loadCachedGameIds(db)
    val cachedRomPaths = loadCachedRomPaths(db)
    val files: List<DocumentFile> = if (singleFile) {
        val f = DocumentFile.fromSingleUri(context, treeUri)
        if (f != null && shouldScanFile(f)) listOf(f) else emptyList()
    } else {
        DocumentFile.fromTreeUri(context, treeUri)?.listFiles()
            ?.filter(::shouldScanFile)
            ?: emptyList()
    }
    val total = files.size
    var skipped = 0
    var matched = 0
    var limitReached = false
    val inFlight = ArrayDeque<kotlinx.coroutines.Deferred<Boolean>>()

    supervisorScope {
        for ((index, file) in files.withIndex()) {
            if (cachedGameIds.size + inFlight.size >= MAX_CACHED_GAMES) {
                skipped += total - index
                limitReached = true
                break
            }
            onProgress(index + 1, total, file.name ?: "")
            val sourceRomPath = resolveDocumentAbsolutePath(file)
            val normalizedPath = sourceRomPath?.normalizeCachedRomPath()
            if (normalizedPath != null && normalizedPath in cachedRomPaths) {
                skipped++
                continue
            }
            val candidates = hashRomCandidates(context, file)
            val resolved = resolveGameId(context, candidates, credentials, userAgent, db)
            if (resolved == null) {
                skipped++
                continue
            }
            val (hash, gameId) = resolved
            val gameIdString = gameId.toString()
            if (gameIdString in cachedGameIds) {
                skipped++
                continue
            }

            cachedGameIds.add(gameIdString)
            if (normalizedPath != null) {
                cachedRomPaths.add(normalizedPath)
            }

            inFlight += async {
                cacheGame(
                    context = context,
                    gameId = gameId,
                    creds = credentials,
                    userAgent = userAgent,
                    db = db,
                    romHash = hash,
                    sourceRomPath = sourceRomPath
                )
                true
            }

            if (inFlight.size >= SCAN_CACHE_PIPELINE_LIMIT) {
                if (inFlight.removeFirst().await()) {
                    matched++
                }
            }
        }

        inFlight.toList().awaitAll().forEach { completed ->
            if (completed) {
                matched++
            }
        }
    }

    return ScanResult(
        matched = matched,
        total = total,
        skipped = skipped,
        limitReached = limitReached
    )
}

internal suspend fun loadCachedGameIds(db: AppDatabase): MutableSet<String> =
    db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
        .mapNotNull { entry -> CacheKeys.parseGameIdStringFromPatchKey(entry.cacheKey) }
        .toMutableSet()

internal fun String.normalizeCachedRomPath(): String =
    replace('\\', '/')
        .trim()

private fun shouldScanFile(file: DocumentFile): Boolean {
    val name = file.name ?: return false
    return file.isFile
        && !name.startsWith(".")
        && !name.endsWith(".txt", ignoreCase = true)
        && !name.endsWith(".xml", ignoreCase = true)
}

/** Tries each candidate hash in order, returning the first that resolves to a
 * RetroAchievements game id along with that matching hash. One ROM can produce
 * several candidates (e.g. a .pbp yields both a PSP whole-file hash and a PS1
 * executable hash; a .chd yields one per possible console). */
internal suspend fun resolveGameId(
    context: Context,
    candidates: List<String>,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase
): Pair<String, Int>? {
    for (hash in candidates) {
        val gameId = fetchGameId(context, hash, creds, userAgent, db)
        if (gameId != null) return hash to gameId
    }
    return null
}

internal suspend fun fetchGameId(
    context: Context,
    hash: String,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase
): Int? =
    run {
        db.cacheDao().get(CacheKeys.gameId(hash))
            ?.responseBody
            ?.let { cachedBody ->
                val cachedGameId = runCatching { JSONObject(cachedBody).optInt("GameID", 0) }.getOrDefault(0)
                if (cachedGameId > 0) {
                    Log.i(TAG, "fetchGameId cache hit for hash=$hash gameId=$cachedGameId")
                    return@run cachedGameId
                }
            }

        val url = buildApiUrl(
            RA_HOST,
            "gameid",
            mapOf(
                "m" to hash,
                "u" to creds.user,
                "t" to creds.token
            )
        )
        when (val result = httpGet(url, userAgent)) {
            is HttpGetResult.Success -> {
                val gameId = runCatching { JSONObject(result.body).optInt("GameID", 0) }.getOrDefault(0)
                if (gameId > 0) {
                    db.cacheDao().upsert(
                        CacheEntry(
                            cacheKey = CacheKeys.gameId(hash),
                            responseBody = result.body
                        )
                    )
                    Log.i(TAG, "fetchGameId matched hash=$hash gameId=$gameId")
                    gameId
                } else {
                    Log.i(TAG, "fetchGameId no match for hash=$hash body=${result.body}")
                    null
                }
            }

            is HttpGetResult.Failure -> {
                val logDetails = result.logMessage("gameid", url)
                Log.e(TAG, "fetchGameId failed for hash=$hash: $logDetails")
                RequestFailureNotifier.report(result.userMessage(context, "gameid"), logDetails)
                null
            }
        }
    }

internal suspend fun cacheGame(
    context: Context,
    gameId: Int,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    romHash: String? = null,
    sourceRomPath: String? = null,
    cacheImages: Boolean = true,
) {
    refreshCachedGameOfflineBundle(
        context = context,
        target = CachedGameRefreshTarget(
            gameId = gameId,
            user = creds.user,
            sourceRomPath = sourceRomPath,
            endpoint = if (romHash != null) RefreshEndpoint.AchievementSets else RefreshEndpoint.Patch,
            romHash = romHash
        ),
        creds = creds,
        userAgent = userAgent,
        db = db,
        notificationMode = RefreshNotificationMode.Foreground,
        cacheImages = cacheImages,
    )
}


internal suspend fun cacheUnlocks(
    context: Context,
    gameId: Int,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    notificationMode: RefreshNotificationMode = RefreshNotificationMode.Foreground
): Boolean {
    val url = buildApiUrl(
        RA_HOST,
        "unlocks",
        mapOf(
            "g" to gameId.toString(),
            "h" to "0",
            "u" to creds.user,
            "t" to creds.token
        )
    )
    when (val result = httpGet(url, userAgent)) {
        is HttpGetResult.Success -> {
            val filteredBody = filterWarningAchievementFromUnlocksResponse(result.body)
            db.cacheDao().upsert(
                CacheEntry(
                    cacheKey = CacheKeys.unlocks(gameId, creds.user),
                    responseBody = filteredBody
                )
            )
            Log.i(TAG, "Cached unlocks for gameId=$gameId")
            return true
        }

        is HttpGetResult.Failure -> {
            val logDetails = result.logMessage("unlocks", url)
            reportRefreshFailure(
                context = context,
                action = "unlocks",
                userMessage = result.userMessage(context, "unlocks"),
                logDetails = logDetails,
                notificationMode = notificationMode
            )
            return false
        }
    }
}

internal suspend fun cacheSession(gameId: Int, creds: LoginCredentials, db: AppDatabase) {
    val serverNow = System.currentTimeMillis() / 1000
    val unlocks = buildUnlocksArray(db, gameId, creds.user, serverNow)
    val fakeStartSession = JSONObject().apply {
        put("Success", true)
        put("ServerNow", serverNow)
        put("HardcoreUnlocks", JSONArray())
        put("Unlocks", unlocks)
    }
    db.cacheDao().upsert(CacheEntry(
        cacheKey = CacheKeys.startSession(gameId, creds.user),
        responseBody = fakeStartSession.toString()
    ))
    Log.i(TAG, "Cached fake startsession for gameId=$gameId unlocks=${unlocks.length()}")
}

private suspend fun buildUnlocksArray(db: AppDatabase, gameId: Int, user: String, serverNow: Long): JSONArray {
    val cachedUnlockIds = runCatching {
        val body = db.cacheDao().get(CacheKeys.unlocks(gameId, user))?.responseBody ?: return@runCatching emptyList<Int>()
        val arr = JSONObject(body).optJSONArray("UserUnlocks") ?: return@runCatching emptyList<Int>()
        filterWarningAchievementIds((0 until arr.length()).map { arr.getInt(it) })
    }.getOrDefault(emptyList())

    val pendingAwards = runCatching {
        db.pendingAwardDao().getAllByStatus(PENDING_AWARD_STATUS_PENDING)
    }.getOrDefault(emptyList())

    if (pendingAwards.isEmpty()) {
        return JSONArray().also { result ->
            cachedUnlockIds.forEach { id ->
                result.put(JSONObject().apply {
                    put("ID", id)
                    put("When", serverNow)
                })
            }
        }
    }

    val achievementGameIds = buildAchievementGameIds(
        runCatching { db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH) }.getOrDefault(emptyList()),
        runCatching { db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_ACHIEVEMENTSETS) }.getOrDefault(emptyList()),
    )

    val unlockIds = mergeStartSessionUnlockIds(
        cachedUnlockIds = cachedUnlockIds,
        pendingAwards = pendingAwards,
        achievementGameIds = achievementGameIds,
        gameId = gameId,
        user = user
    )

    return JSONArray().also { result ->
        unlockIds.forEach { id ->
            result.put(JSONObject().apply {
                put("ID", id)
                put("When", serverNow)
            })
        }
    }
}

internal fun mergeStartSessionUnlockIds(
    cachedUnlockIds: List<Int>,
    pendingAwards: List<PendingAward>,
    achievementGameIds: Map<Int, Int>,
    gameId: Int,
    user: String
): List<Int> {
    val mergedIds = linkedSetOf<Int>()
    filterWarningAchievementIds(cachedUnlockIds).forEach(mergedIds::add)

    pendingAwards.asSequence()
        .filter { it.status == PENDING_AWARD_STATUS_PENDING }
        .filterNot(::isHardcoreAward)
        .filter { pendingAwardUser(it) == user }
        .filter { achievementGameIds[it.achievementId] == gameId }
        .map(PendingAward::achievementId)
        .forEach(mergedIds::add)

    return mergedIds.toList()
}

private fun pendingAwardUser(award: PendingAward): String? {
    val queryParams = parseFormParams(award.queryString.substringAfter('?', ""))
    return queryParams["u"] ?: parseFormParams(award.requestBody)["u"]
}

internal fun httpGet(url: String, userAgent: String): HttpGetResult {
    val action = apiActionFromUrl(url)

    repeat(HTTP_GET_MAX_429_RETRIES + 1) { attempt ->
        if (action != null) {
            throttleRetroAchievementsApiRequest("GET $action")
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
        }

        try {
            val statusCode = connection.responseCode
            val reason = connection.responseMessage
            val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (statusCode in 200..299) {
                return HttpGetResult.Success(body)
            }

            if (statusCode == HTTP_TOO_MANY_REQUESTS && attempt < HTTP_GET_MAX_429_RETRIES) {
                val retryAfterMillis = retryAfterMillis(connection, attempt)
                Log.w(TAG, "httpGet hit 429 for ${action ?: redactTokens(url)}; retrying in ${retryAfterMillis}ms (attempt ${attempt + 1}/$HTTP_GET_MAX_429_RETRIES)")
                Thread.sleep(retryAfterMillis)
            } else {
                return HttpGetResult.Failure(
                    kind = "http",
                    statusCode = statusCode,
                    reason = reason,
                    bodySnippet = body.take(HTTP_ERROR_BODY_LOG_LIMIT).ifBlank { null }
                )
            }
        } catch (e: IOException) {
            return HttpGetResult.Failure(
                kind = "network",
                exceptionMessage = e.message ?: e::class.java.simpleName
            )
        } finally {
            connection.disconnect()
        }
    }

    return HttpGetResult.Failure(
        kind = "http",
        statusCode = HTTP_TOO_MANY_REQUESTS,
        reason = "Too Many Requests"
    )
}

private fun apiActionFromUrl(url: String): String? =
    url.substringAfter("r=", "").substringBefore('&').takeIf { it.isNotEmpty() }

private fun retryAfterMillis(connection: HttpURLConnection, attempt: Int): Long {
    val headerValue = connection.getHeaderField(HTTP_RETRY_AFTER_HEADER)?.trim()
    val headerMillis = headerValue?.toLongOrNull()?.times(1000)
    if (headerMillis != null && headerMillis > 0) {
        return min(headerMillis, HTTP_GET_MAX_429_BACKOFF_MS)
    }

    val exponentialMillis = HTTP_GET_INITIAL_429_BACKOFF_MS shl attempt
    return min(exponentialMillis, HTTP_GET_MAX_429_BACKOFF_MS)
}
