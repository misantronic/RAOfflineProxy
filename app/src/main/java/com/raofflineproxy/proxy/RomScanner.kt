package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.buildApiUrl
import com.raofflineproxy.proxyBase
import com.raofflineproxy.redactTokens
import com.raofflineproxy.throttleRetroAchievementsApiRequest
import com.raofflineproxy.toHexString
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val FALLBACK_USER_AGENT = "rcheevos/11.4.0"
private const val TAG = "RAProxy"
private const val HTTP_ERROR_BODY_LOG_LIMIT = 512
private val NES_HEADER_MAGIC = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A)
private val FDS_HEADER_MAGIC = byteArrayOf('F'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 0x1A)

data class ScanResult(
    val matched: Int,
    val total: Int,
    val skipped: Int
)

data class LoginCredentials(val user: String, val token: String)

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

suspend fun refreshGamePatch(
    context: Context,
    gameId: Int,
    creds: LoginCredentials,
    userAgent: String,
    db: AppDatabase
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
    cachePatchImages(context, gameId, userAgent, responseBody)
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

    for ((index, file) in files.withIndex()) {
        onProgress(index + 1, total, file.name ?: "")
        val hash = md5File(context, file.uri)
        if (hash == null) { skipped++; continue }
        val gameId = fetchGameId(context, hash, credentials, userAgent)
        if (gameId == null) { skipped++; continue }
        cacheGame(context, gameId, credentials, userAgent, db)
        matched++
        if (index < files.lastIndex) delay(500)
    }

    return ScanResult(matched, total, skipped)
}

private fun shouldScanFile(file: DocumentFile): Boolean {
    val name = file.name ?: return false
    return file.isFile
        && !name.startsWith(".")
        && !name.endsWith(".txt", ignoreCase = true)
        && !name.endsWith(".xml", ignoreCase = true)
}

private fun md5File(context: Context, uri: Uri): String? =
    try {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(16)
            var headerBytesRead = 0
            while (headerBytesRead < header.size) {
                val read = stream.read(header, headerBytesRead, header.size - headerBytesRead)
                if (read <= 0) break
                headerBytesRead += read
            }

            val headerBytesToSkip = retroAchievementsHeaderBytesToSkip(header, headerBytesRead)
            if (headerBytesRead > headerBytesToSkip) {
                digest.update(header, headerBytesToSkip, headerBytesRead - headerBytesToSkip)
            }

            val buffer = ByteArray(8192)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        }
        digest.digest().toHexString()
    } catch (_: Exception) { null }

internal fun retroAchievementsHeaderBytesToSkip(header: ByteArray, bytesRead: Int): Int {
    if (bytesRead < 4) return 0
    return when {
        header.startsWithMagic(bytesRead, NES_HEADER_MAGIC) -> minOf(16, bytesRead)
        header.startsWithMagic(bytesRead, FDS_HEADER_MAGIC) -> minOf(16, bytesRead)
        else -> 0
    }
}

private fun ByteArray.startsWithMagic(bytesRead: Int, magic: ByteArray): Boolean {
    if (bytesRead < magic.size) return false
    for (index in magic.indices) {
        if (this[index] != magic[index]) return false
    }
    return true
}

private fun fetchGameId(context: Context, hash: String, creds: LoginCredentials, userAgent: String): Int? =
    run {
        val url = buildApiUrl(
            proxyBase(context),
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

internal suspend fun cacheGame(context: Context, gameId: Int, creds: LoginCredentials, userAgent: String, db: AppDatabase) {
    val patchUrl = buildApiUrl(
        proxyBase(context),
        "patch",
        mapOf(
            "g" to gameId.toString(),
            "u" to creds.user,
            "t" to creds.token
        )
    )
    when (val result = httpGet(patchUrl, userAgent)) {
        is HttpGetResult.Success -> cachePatchImages(context, gameId, userAgent, result.body)
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
        proxyBase(context),
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
    if (url.startsWith("$RA_HOST/dorequest.php")) {
        val action = url.substringAfter("r=", "request").substringBefore('&')
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
