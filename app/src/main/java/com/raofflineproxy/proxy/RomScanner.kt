package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.PROXY_BASE
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.buildApiUrl
import com.raofflineproxy.toHexString
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val FALLBACK_USER_AGENT = "rcheevos/11.4.0"

data class ScanResult(
    val matched: Int,
    val total: Int,
    val skipped: Int
)

data class LoginCredentials(val user: String, val token: String)

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
    val responseBody = try {
        httpGet(url, userAgent)
    } catch (e: Exception) {
        Log.e("RAProxy", "refreshGamePatch failed for gameId=$gameId: ${e.message}")
        return null
    }
    val json = runCatching { JSONObject(responseBody) }.getOrNull()
    if (json == null || !json.optBoolean("Success", false)) {
        Log.e("RAProxy", "refreshGamePatch returned invalid response for gameId=$gameId")
        return null
    }
    db.cacheDao().upsert(CacheEntry(
        cacheKey = CacheKeys.patch(gameId, creds.user),
        responseBody = responseBody
    ))
    Log.i("RAProxy", "refreshGamePatch: updated cache for gameId=$gameId")
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
        if (f != null && f.isFile) listOf(f) else emptyList()
    } else {
        DocumentFile.fromTreeUri(context, treeUri)?.listFiles()
            ?.filter { it.isFile && it.name?.let { n -> !n.startsWith(".") && !n.endsWith(".txt", ignoreCase = true) } == true }
            ?: emptyList()
    }
    val total = files.size
    var matched = 0
    var skipped = 0

    for ((index, file) in files.withIndex()) {
        onProgress(index + 1, total, file.name ?: "")
        val hash = md5File(context, file.uri)
        if (hash == null) { skipped++; continue }
        val gameId = fetchGameId(hash, credentials, userAgent)
        if (gameId == null) { skipped++; continue }
        cacheGame(gameId, credentials, userAgent, db)
        matched++
        if (index < files.lastIndex) delay(500)
    }

    return ScanResult(matched, total, skipped)
}

private fun md5File(context: Context, uri: Uri): String? =
    try {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        }
        digest.digest().toHexString()
    } catch (_: Exception) { null }

private fun fetchGameId(hash: String, creds: LoginCredentials, userAgent: String): Int? =
    try {
        val url = buildApiUrl(
            PROXY_BASE,
            "gameid",
            mapOf(
                "m" to hash,
                "u" to creds.user,
                "t" to creds.token
            )
        )
        val body = httpGet(url, userAgent)
        val gameId = JSONObject(body).optInt("GameID", 0)
        if (gameId > 0) gameId else null
    } catch (_: Exception) { null }

internal suspend fun cacheGame(gameId: Int, creds: LoginCredentials, userAgent: String, db: AppDatabase) {
    try {
        httpGet(
            buildApiUrl(
                PROXY_BASE,
                "patch",
                mapOf(
                    "g" to gameId.toString(),
                    "u" to creds.user,
                    "t" to creds.token
                )
            ),
            userAgent
        )
    } catch (_: Exception) { }
    cacheUnlocks(gameId, creds, userAgent)
    cacheSession(gameId, creds, db)
    Log.i("RAProxy", "cacheGame complete for gameId=$gameId")
}

internal fun cacheUnlocks(gameId: Int, creds: LoginCredentials, userAgent: String) {
    try {
        httpGet(
            buildApiUrl(
                PROXY_BASE,
                "unlocks",
                mapOf(
                    "g" to gameId.toString(),
                    "h" to "0",
                    "u" to creds.user,
                    "t" to creds.token
                )
            ),
            userAgent
        )
    } catch (_: Exception) { }
    Log.i("RAProxy", "Cached unlocks for gameId=$gameId")
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
    Log.i("RAProxy", "Cached fake startsession for gameId=$gameId unlocks=${unlocks.length()}")
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

internal fun httpGet(url: String, userAgent: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setRequestProperty("Accept-Encoding", "identity")
    return connection.inputStream.bufferedReader().use { it.readText() }
}
