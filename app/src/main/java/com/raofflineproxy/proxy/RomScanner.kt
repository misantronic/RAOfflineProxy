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
import com.raofflineproxy.proxy.hash.hashRom
import com.raofflineproxy.redactTokens
import com.raofflineproxy.throttleRetroAchievementsApiRequest
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxyUserAgent
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "RAProxy"
private const val HTTP_ERROR_BODY_LOG_LIMIT = 512

private val FALLBACK_USER_AGENT = "RetroArch/1.21.0 (Android ${Build.VERSION.RELEASE ?: "Unknown"})"

data class ScanResult(
    val matched: Int,
    val total: Int,
    val skipped: Int,
    val limitReached: Boolean = false
)

data class LoginCredentials(val user: String, val token: String)

data class PasswordCredentials(val user: String, val password: String)

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
    cacheImages: Boolean = true
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
    db.cacheDao().upsert(CacheEntry(
        cacheKey = CacheKeys.patch(gameId, creds.user),
        responseBody = responseBody
    ))
    if (cacheImages) {
        cachePatchImages(context, gameId, userAgent, responseBody)
    }
    Log.i(TAG, "refreshGamePatch: updated cache for gameId=$gameId")
    return responseBody
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
    val cachedGameIds = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
        .mapNotNull { entry -> CacheKeys.parseGameIdStringFromPatchKey(entry.cacheKey) }
        .toMutableSet()
    val files: List<DocumentFile> = if (singleFile) {
        val f = DocumentFile.fromSingleUri(context, treeUri)
        if (f != null && shouldScanFile(f)) listOf(f) else emptyList()
    } else {
        DocumentFile.fromTreeUri(context, treeUri)?.listFiles()
            ?.filter(::shouldScanFile)
            ?: emptyList()
    }
    val total = files.size
    var matched = 0
    var skipped = 0
    var limitReached = false

    for ((index, file) in files.withIndex()) {
        if (cachedGameIds.size >= MAX_CACHED_GAMES) {
            skipped += total - index
            limitReached = true
            break
        }
        onProgress(index + 1, total, file.name ?: "")
        val hash = hashRom(context, file)
        if (hash == null) { skipped++; continue }
        val gameId = fetchGameId(context, hash, credentials, userAgent, db)
        if (gameId == null) { skipped++; continue }
        cacheGame(context, gameId, credentials, userAgent, db)
        cachedGameIds.add(gameId.toString())
        matched++
        if (index < files.lastIndex) delay(500)
    }

    return ScanResult(matched, total, skipped, limitReached)
}

private fun shouldScanFile(file: DocumentFile): Boolean {
    val name = file.name ?: return false
    return file.isFile
        && !name.startsWith(".")
        && !name.endsWith(".txt", ignoreCase = true)
        && !name.endsWith(".xml", ignoreCase = true)
}

private suspend fun fetchGameId(
    context: Context,
    hash: String,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase
): Int? =
    run {
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
    cacheImages: Boolean = true
) {
    val patchUrl = buildApiUrl(
        RA_HOST,
        "patch",
        mapOf(
            "g" to gameId.toString(),
            "u" to creds.user,
            "t" to creds.token
        )
    )
    when (val result = httpGet(patchUrl, userAgent)) {
        is HttpGetResult.Success -> {
            db.cacheDao().upsert(
                CacheEntry(
                    cacheKey = CacheKeys.patch(gameId, creds.user),
                    responseBody = result.body
                )
            )
            if (cacheImages) {
                cachePatchImages(context, gameId, userAgent, result.body)
            }
        }
        is HttpGetResult.Failure -> {
            val logDetails = result.logMessage("patch", patchUrl)
            Log.e(TAG, "cacheGame patch refresh failed for gameId=$gameId: $logDetails")
            RequestFailureNotifier.report(result.userMessage(context, "patch"), logDetails)
        }
    }
    cacheUnlocks(context, gameId, creds, userAgent, db)
    cacheSession(gameId, creds, db)
    Log.i(TAG, "cacheGame complete for gameId=$gameId")
}

internal suspend fun cacheUnlocks(
    context: Context,
    gameId: Int,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase
) {
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
            db.cacheDao().upsert(
                CacheEntry(
                    cacheKey = CacheKeys.unlocks(gameId, creds.user),
                    responseBody = result.body
                )
            )
            Log.i(TAG, "Cached unlocks for gameId=$gameId")
        }

        is HttpGetResult.Failure -> {
            val logDetails = result.logMessage("unlocks", url)
            Log.e(TAG, "cacheUnlocks failed for gameId=$gameId: $logDetails")
            RequestFailureNotifier.report(result.userMessage(context, "unlocks"), logDetails)
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
    val unlockIds = runCatching {
        val body = db.cacheDao().get(CacheKeys.unlocks(gameId, user))?.responseBody ?: return@runCatching emptyList<Int>()
        val arr = JSONObject(body).optJSONArray("UserUnlocks") ?: return@runCatching emptyList<Int>()
        (0 until arr.length()).map { arr.getInt(it) }
    }.getOrDefault(emptyList())
    return JSONArray().also { result ->
        unlockIds.forEach { id ->
            result.put(JSONObject().apply {
                put("ID", id)
                put("When", serverNow)
            })
        }
    }
}

internal fun httpGet(url: String, userAgent: String): HttpGetResult {
    val action = apiActionFromUrl(url)
    if (action != null) {
        throttleRetroAchievementsApiRequest("GET $action")
    }

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept-Encoding", "identity")
    }

    return try {
        val statusCode = connection.responseCode
        val reason = connection.responseMessage
        val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (statusCode in 200..299) {
            HttpGetResult.Success(body)
        } else {
            HttpGetResult.Failure(
                kind = "http",
                statusCode = statusCode,
                reason = reason,
                bodySnippet = body.take(HTTP_ERROR_BODY_LOG_LIMIT).ifBlank { null }
            )
        }
    } catch (e: IOException) {
        HttpGetResult.Failure(
            kind = "network",
            exceptionMessage = e.message ?: e::class.java.simpleName
        )
    } finally {
        connection.disconnect()
    }
}

private fun apiActionFromUrl(url: String): String? =
    url.substringAfter("r=", "").substringBefore('&').takeIf { it.isNotEmpty() }
