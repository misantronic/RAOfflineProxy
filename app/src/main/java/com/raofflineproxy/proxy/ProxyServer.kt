package com.raofflineproxy.proxy

import android.util.Log
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.extractFormParam
import com.raofflineproxy.proxyHost
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.redactFormBody
import com.raofflineproxy.redactTokens
import com.raofflineproxy.sha256Hex
import com.raofflineproxy.sharedHttpClient
import com.raofflineproxy.throttleRetroAchievementsApiRequest
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.PendingAward
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "RAProxy"
private const val MAX_WORKER_THREADS = 8
private const val SOCKET_TIMEOUT_MS = 30_000
private const val MAX_REQUEST_BODY_BYTES = 1_048_576 // 1 MiB — rcheevos requests are small
private const val DB_OPERATION_TIMEOUT_SECONDS = 3L
private val SUCCESS_TRUE_REGEX = Regex("\"Success\"\\s*:\\s*true(?=\\s*[,}])")

// These requests mutate state on the RA server — do not serve from cache offline
private val AWARD_ACTIONS = setOf("awardachievement", "submitlbentry")

// Offline: return a canned success response instead of hitting the server
private val FAKE_OFFLINE_SUCCESS_ACTIONS = setOf("ping")

// These requests are safe to cache and serve offline
private val CACHEABLE_ACTIONS = setOf("patch", "gameid", "achievements", "hashlibrary", "login2", "unlocks")

// Headers OkHttp manages itself — never forward these
private val SKIP_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding")

internal sealed interface ParsedRequestLineResult {
    data class Valid(val method: String, val path: String) : ParsedRequestLineResult
    data class Invalid(val statusCode: Int, val message: String) : ParsedRequestLineResult
}

internal sealed interface UpstreamResult {
    data class Success(val statusCode: Int, val message: String, val body: String) : UpstreamResult
    data class HttpError(val statusCode: Int, val message: String, val body: String) : UpstreamResult
    data class NetworkError(val message: String) : UpstreamResult
}

internal sealed interface QueueAwardResult {
    data object Queued : QueueAwardResult
    data class Error(val message: String) : QueueAwardResult
}

data class GameActivity(
    val gameId: String,
    val action: String?,
    val gameTitle: String?
)

class ProxyServer(
    private val context: android.content.Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val port: Int,
    private val isOnline: () -> Boolean,
    private val onGameActivity: (GameActivity) -> Unit = {}
) {
    @Volatile private var executor = newExecutor()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastCachedUserAgent: String? = null
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        running = true
        if (executor.isShutdown || executor.isTerminated) {
            executor = newExecutor()
        }
        val bindHost = proxyHost()
        serverSocket = ServerSocket(port, 50, InetAddress.getByName(bindHost))
        executor.execute { acceptLoop() }
        Log.i(TAG, "Proxy started on $bindHost:$port")
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
        executor.shutdownNow()
        Log.i(TAG, "Proxy stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            try {
                val socket = ss.accept()
                executor.execute { handleConnection(socket) }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Accept error: ${e.message}")
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                if (!socket.inetAddress.isLoopbackAddress) {
                    Log.w(TAG, "Rejected non-loopback connection from ${socket.inetAddress}")
                    return
                }
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                    line = reader.readLine()
                }

                val parsedRequestLine = parseRequestLine(requestLine)
                if (parsedRequestLine is ParsedRequestLineResult.Invalid) {
                    writer.print(httpError(parsedRequestLine.statusCode, parsedRequestLine.message))
                    writer.flush()
                    return
                }
                parsedRequestLine as ParsedRequestLineResult.Valid
                val method = parsedRequestLine.method
                val path = parsedRequestLine.path

                val transferEncoding = headers["transfer-encoding"]
                val transferEncodingError = validateTransferEncoding(transferEncoding)
                if (transferEncodingError != null) {
                    writer.print(httpError(transferEncodingError.first, transferEncodingError.second))
                    writer.flush()
                    return
                }

                val contentLengthHeader = headers["content-length"]
                val parsedContentLength = parseContentLength(contentLengthHeader)
                if (parsedContentLength == null) {
                    writer.print(httpError(400, "bad content length"))
                    writer.flush()
                    return
                }
                val contentLength = parsedContentLength
                if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                    val response = httpError(413, "request body too large")
                    writer.print(response)
                    writer.flush()
                    return
                }
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                val bodyReadError = validateBodyRead(contentLength, totalRead)
                if (bodyReadError != null) {
                    writer.print(httpError(bodyReadError.first, bodyReadError.second))
                    writer.flush()
                    return
                }
                val rawBody = String(bodyChars, 0, totalRead)

                val response = processRequest(method, path, rawBody, headers)
                writer.print(response)
                writer.flush()
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Connection timed out: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Connection handling error: ${e.message}")
            }
        }
    }

    private fun processRequest(method: String, path: String, rawBody: String, headers: Map<String, String>): String {
        val action = extractAction(path, rawBody)
        if (action == "ping") {
            Log.d(TAG, "Request: $method action=ping online=${isOnline()}")
        } else {
            Log.i(TAG, "Request: $method ${redactTokens(path)} body=${redactFormBody(rawBody)} action=$action online=${isOnline()}")
        }
        extractGameActivity(path, rawBody, action)?.let(onGameActivity)

        val userAgent = headers["user-agent"]
        if (!userAgent.isNullOrEmpty() && userAgent != lastCachedUserAgent) {
            lastCachedUserAgent = userAgent
            scope.launch(Dispatchers.IO) {
                db.cacheDao().upsert(CacheEntry(cacheKey = CacheKeys.USER_AGENT, responseBody = userAgent))
            }
        }

        return when {
            action in AWARD_ACTIONS -> handleAwardRequest(path, rawBody, headers)
            isHardcoreRequest(path, rawBody) -> {
                Log.i(TAG, "Hardcore request — bypassing cache, forwarding directly")
                val upstream = forwardToRA(method, path, rawBody, headers)
                if (upstream != null) httpOk(upstream) else httpError(503, "upstream unavailable")
            }
            action in FAKE_OFFLINE_SUCCESS_ACTIONS && !isOnline() -> {
                Log.i(TAG, "Fake success offline: action=$action")
                httpOk("""{"Success":true}""")
            }
            action == "startsession" && !isOnline() -> handleStartSessionRequest(path, rawBody)
            isOnline() -> handleOnlineRequest(method, path, rawBody, action, headers)
            else -> handleOfflineRequest(path, rawBody, action)
        }
    }

    private fun isHardcoreRequest(path: String, rawBody: String): Boolean =
        proxyIsHardcoreRequest(path, rawBody)

    private fun handleAwardRequest(path: String, rawBody: String, headers: Map<String, String>): String {
        if (isHardcoreRequest(path, rawBody)) {
            Log.w(TAG, "Rejecting hardcore award — hardcore mode is not supported by this proxy")
            return httpError(403, "hardcore_not_supported")
        }

        if (isOnline()) {
            return when (val upstream = forwardToRAResult("POST", path, rawBody, headers)) {
                is UpstreamResult.Success -> httpResponse(upstream.statusCode, upstream.message, upstream.body)
                is UpstreamResult.HttpError -> {
                    Log.w(TAG, "Award request rejected by upstream: ${upstream.statusCode} ${upstream.message}")
                    httpResponse(upstream.statusCode, upstream.message, upstream.body)
                }
                is UpstreamResult.NetworkError -> {
                    Log.w(TAG, "Award request will be queued due to upstream network failure: ${upstream.message}")
                    queueOfflineAward(path, rawBody, headers)
                }
            }
        }

        return queueOfflineAward(path, rawBody, headers)
    }

    private fun handleStartSessionRequest(path: String, rawBody: String): String {
        val gameId = extractParam("g", path, rawBody)?.toIntOrNull()
        val user = extractParam("u", path, rawBody)
        if (gameId == null || user.isNullOrEmpty()) {
            return httpError(400, "bad request")
        }

        var cached: CacheEntry? = null
        val latch = CountDownLatch(1)
        scope.launch(Dispatchers.IO) {
            runCatching {
                cacheSession(gameId, LoginCredentials(user, ""), db)
                cached = db.cacheDao().get(CacheKeys.startSession(gameId, user))
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)

        return if (cached != null) {
            Log.i(TAG, "Served synthetic startsession for gameId=$gameId user=$user")
            httpOk(cached!!.responseBody)
        } else {
            Log.e(TAG, "Failed to synthesize startsession for gameId=$gameId user=$user")
            httpError(503, "no cached response")
        }
    }

    private fun queueOfflineAward(path: String, rawBody: String, headers: Map<String, String>): String {
        when (val result = queueAward(path, rawBody, headers)) {
            QueueAwardResult.Queued -> Unit
            is QueueAwardResult.Error -> {
                Log.e(TAG, "Award queueing failed: ${result.message}")
                return httpError(500, "award_queue_failed")
            }
        }

        val score = fetchCachedScore(path, rawBody)
        return httpOk("""{"Success":true,"Score":$score,"SoftcoreScore":0,"AchievementID":0,"Error":"queued_offline"}""")
    }

    private fun handleOnlineRequest(method: String, path: String, rawBody: String, action: String?, headers: Map<String, String>): String {
        val upstream = forwardToRA(method, path, rawBody, headers)
        val shouldCache = upstream != null && shouldCacheResponse(upstream)
        if (shouldCache && action in CACHEABLE_ACTIONS) {
            val key = cacheKey(path, rawBody)
            val userAgent = headers["user-agent"] ?: ""
            scope.launch(Dispatchers.IO) {
                db.cacheDao().upsert(CacheEntry(cacheKey = key, responseBody = upstream))
                Log.i(TAG, "Cached: $key")
                if (action == "patch") {
                    val gameId = extractParam("g", path, rawBody)?.toIntOrNull()
                    val user = extractParam("u", path, rawBody)
                    val token = extractParam("t", path, rawBody)
                    if (gameId != null && user != null && token != null) {
                        cacheUnlocks(context, gameId, LoginCredentials(user, token), userAgent, db)
                    }
                }
            }
            return httpOk(upstream)
        }
        if (upstream != null) {
            Log.i(TAG, "Forwarded (not cached) action=$action")
            return httpOk(upstream)
        }
        return httpError(503, "upstream unavailable")
    }

    private fun handleOfflineRequest(path: String, rawBody: String, action: String?): String {
        if (action !in CACHEABLE_ACTIONS) {
            return httpError(503, "offline")
        }
        var cached: CacheEntry? = null
        val key = cacheKey(path, rawBody)
        val latch = CountDownLatch(1)
        scope.launch(Dispatchers.IO) {
            cached = db.cacheDao().get(key)
                ?: db.cacheDao().getByPrefix("$key:")
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return if (cached != null) {
            Log.i(TAG, "Cache HIT: $key (${cached!!.responseBody.length} bytes)")
            httpOk(cached!!.responseBody)
        } else {
            Log.e(TAG, "Cache MISS: $key")
            if (action == "gameid") {
                httpGameIdCacheMiss()
            } else {
                httpError(503, "no cached response")
            }
        }
    }

    private fun forwardToRA(method: String, path: String, rawBody: String, headers: Map<String, String>): String? =
        when (val result = forwardToRAResult(method, path, rawBody, headers)) {
            is UpstreamResult.Success -> result.body
            is UpstreamResult.HttpError -> {
                Log.w(TAG, "Upstream returned ${result.statusCode} for ${redactTokens(path)}")
                null
            }
            is UpstreamResult.NetworkError -> {
                Log.e(TAG, "Upstream request failed: ${result.message}")
                null
            }
        }

    private fun forwardToRAResult(method: String, path: String, rawBody: String, headers: Map<String, String>): UpstreamResult {
        return try {
            val action = extractAction(path, rawBody) ?: context.getString(R.string.request_action_unknown)
            val url = "$RA_HOST$path"
            val builder = Request.Builder().url(url)

            headers.forEach { (k, v) ->
                if (k !in SKIP_HEADERS) {
                    val headerValue = if (k == "user-agent") proxyUserAgent(v) else v
                    builder.header(k, headerValue)
                }
            }

            val request = if (method == "POST") {
                builder.post(rawBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
            } else {
                builder.get().build()
            }

            Log.d(TAG, "→ RA $method ${redactTokens(url)} (${request.headers.size} headers)")
            if (method == "POST") Log.d(TAG, "→ RA POST body: ${redactFormBody(rawBody)}")

            throttleRetroAchievementsApiRequest("$method ${action.lowercase()}")
            sharedHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                Log.d(TAG, "← RA ${resp.code} for ${redactTokens(path)} (${body.length} bytes)")
                val message = sanitizeHttpReasonPhrase(resp.message, resp.code)

                if (resp.isSuccessful) {
                    UpstreamResult.Success(resp.code, message, body)
                } else {
                    val logDetails = buildString {
                        append("$action request failed url=${redactTokens(url)}")
                        append(" http=${resp.code} $message")
                        if (body.isNotBlank()) append(" body=${body.take(512)}")
                    }
                    RequestFailureNotifier.report(
                        context.getString(R.string.request_failed_http, action, resp.code, message),
                        logDetails
                    )
                    UpstreamResult.HttpError(resp.code, message, body)
                }
            }
        } catch (e: Exception) {
            val action = extractAction(path, rawBody) ?: context.getString(R.string.request_action_unknown)
            val errorMessage = e.message ?: context.getString(R.string.request_error_unknown_reason)
            val url = "$RA_HOST$path"
            RequestFailureNotifier.report(
                context.getString(R.string.request_failed_network, action, errorMessage),
                "$action request failed url=${redactTokens(url)} error=$errorMessage"
            )
            UpstreamResult.NetworkError(errorMessage)
        }
    }

    private fun queueAward(path: String, rawBody: String, headers: Map<String, String>): QueueAwardResult {
        val achievementId = extractParam("a", path, rawBody)?.toIntOrNull() ?: 0
        if (achievementId > 0) {
            val latch = CountDownLatch(1)
            var alreadyQueued = false
            scope.launch(Dispatchers.IO) {
                alreadyQueued = db.pendingAwardDao().existsByAchievementId(achievementId)
                latch.countDown()
            }
            latch.await(DB_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (alreadyQueued) {
                Log.i(TAG, "Award already queued: achievementId=$achievementId, skipping duplicate")
                return QueueAwardResult.Queued
            }
        }

        val signedAward = buildPendingAward(
            path = path,
            rawBody = rawBody,
            headers = headers,
            loadPrevHash = {
                val latch = CountDownLatch(1)
                var prevHash = "genesis"
                scope.launch(Dispatchers.IO) {
                    prevHash = db.pendingAwardDao().getLatest()?.payloadHash ?: "genesis"
                    latch.countDown()
                }
                latch.await(3, TimeUnit.SECONDS)
                prevHash
            },
            signBytes = AwardKeyManager::sign
        )
            ?: run {
                Log.e(TAG, "Award signing failed")
                return QueueAwardResult.Error("signing_failed")
            }

        return when (val result = awaitPendingAwardWrite(scope, signedAward, db.pendingAwardDao()::upsert)) {
            QueueAwardResult.Queued -> {
                Log.i(TAG, "Award queued and signed: achievementId=${signedAward.achievementId}")
                QueueAwardResult.Queued
            }
            is QueueAwardResult.Error -> {
                Log.e(TAG, "Award queue write failed: ${result.message}")
                result
            }
        }
    }

    private fun fetchCachedScore(path: String, rawBody: String): Int {
        val user = extractParam("u", path, rawBody) ?: return 0
        val key = CacheKeys.login(user)
        var score = 0
        val latch = CountDownLatch(1)
        scope.launch(Dispatchers.IO) {
            try {
                val cached = db.cacheDao().get(key)
                if (cached != null) {
                    score = JSONObject(cached.responseBody).optInt("Score", 0)
                }
            } catch (e: JSONException) {
                Log.w(TAG, "Failed to parse cached score: ${e.message}")
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return score
    }

    private fun extractAction(path: String, body: String): String? =
        proxyExtractAction(path, body)

    private fun cacheKey(path: String, body: String): String =
        proxyCacheKey(path, body)

    private fun extractParam(param: String, path: String, body: String): String? =
        proxyExtractParam(param, path, body)

    private fun httpOk(body: String): String = proxyHttpOk(body)

    private fun httpGameIdCacheMiss(): String = proxyHttpGameIdCacheMiss()

    private fun httpError(code: Int, message: String): String = proxyHttpError(code, message)

    private fun httpResponse(code: Int, message: String, body: String): String =
        proxyHttpResponse(code, message, body)

    private fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        2, MAX_WORKER_THREADS,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue()
    )
}

internal fun proxyIsHardcoreRequest(path: String, rawBody: String): Boolean =
    proxyExtractParam("h", path, rawBody) == "1"

internal fun parseRequestLine(requestLine: String): ParsedRequestLineResult {
    val parts = requestLine.trim().split(Regex("\\s+"), limit = 3)
    if (parts.size < 2) {
        return ParsedRequestLineResult.Invalid(400, "bad request")
    }

    val method = parts[0].uppercase()
    val path = parts[1]
    if (method != "GET" && method != "POST") {
        return ParsedRequestLineResult.Invalid(405, "method not allowed")
    }
    if (!path.startsWith('/')) {
        return ParsedRequestLineResult.Invalid(400, "bad request")
    }

    return ParsedRequestLineResult.Valid(method, path)
}

internal fun validateTransferEncoding(transferEncoding: String?): Pair<Int, String>? =
    if (!transferEncoding.isNullOrBlank() && !transferEncoding.equals("identity", ignoreCase = true)) {
        501 to "transfer encoding not supported"
    } else {
        null
    }

internal fun parseContentLength(contentLengthHeader: String?): Int? =
    if (contentLengthHeader == null) 0 else contentLengthHeader.toIntOrNull()

internal fun validateBodyRead(expectedLength: Int, actualLength: Int): Pair<Int, String>? =
    if (actualLength != expectedLength) {
        400 to "incomplete request body"
    } else {
        null
    }

internal fun proxyExtractAction(path: String, body: String): String? {
    val fromPath = "http://x$path".toHttpUrlOrNull()?.queryParameter("r")
    if (fromPath != null) return fromPath
    return extractFormParam(body, "r")
}

internal fun proxyCacheKey(path: String, body: String): String {
    val action = proxyExtractAction(path, body) ?: "unknown"
    val gameId = proxyExtractParam("g", path, body) ?: proxyExtractParam("i", path, body) ?: ""
    val hash = proxyExtractParam("m", path, body) ?: ""
    val user = proxyExtractParam("u", path, body) ?: ""
    val hardcore = proxyExtractParam("h", path, body) ?: ""
    return when (action) {
        "gameid" -> "$action:$hash"
        "startsession" -> CacheKeys.startSession(gameId, user)
        else -> if (hardcore.isNotEmpty()) "$action:$gameId:$user:$hardcore" else "$action:$gameId:$user"
    }
}

internal fun proxyExtractParam(param: String, path: String, body: String): String? {
    val fromPath = "http://x$path".toHttpUrlOrNull()?.queryParameter(param)
    if (fromPath != null) return fromPath
    return extractFormParam(body, param)
}

internal fun extractGameActivity(path: String, body: String, action: String? = proxyExtractAction(path, body)): GameActivity? {
    val gameId = proxyExtractParam("g", path, body)
        ?: proxyExtractParam("i", path, body)
        ?: return null

    return GameActivity(
        gameId = gameId,
        action = action,
        gameTitle = null
    )
}

internal fun buildPendingAward(
    path: String,
    rawBody: String,
    headers: Map<String, String>,
    loadPrevHash: () -> String,
    signBytes: (ByteArray) -> ByteArray,
    queuedAt: Long = System.currentTimeMillis()
): PendingAward? {
    val userAgent = headers["user-agent"] ?: ""
    val achievementId = proxyExtractParam("a", path, rawBody)?.toIntOrNull() ?: 0
    val prevHash = loadPrevHash()
    val canonicalPayload = "$achievementId|$path|$rawBody|$queuedAt"
    val payloadHash = sha256Hex(canonicalPayload)
    val signInput = "$payloadHash:$prevHash"
    val signature = runCatching {
        Base64.getEncoder().encodeToString(signBytes(signInput.toByteArray(Charsets.UTF_8)))
    }.getOrElse {
        return null
    }
    val signedAt = System.currentTimeMillis()

    return PendingAward(
        achievementId = achievementId,
        queryString = path,
        requestBody = rawBody,
        userAgent = userAgent,
        queuedAt = queuedAt,
        payloadHash = payloadHash,
        prevHash = prevHash,
        signature = signature,
        signedAt = signedAt
    )
}

internal fun awaitPendingAwardWrite(
    scope: CoroutineScope,
    award: PendingAward,
    upsertAward: suspend (PendingAward) -> Unit,
    timeoutSeconds: Long = DB_OPERATION_TIMEOUT_SECONDS
): QueueAwardResult {
    val latch = CountDownLatch(1)
    var writeError: String? = null

    scope.launch(Dispatchers.IO) {
        runCatching {
            upsertAward(award)
        }.onFailure {
            writeError = it.message ?: "db_write_failed"
        }
        latch.countDown()
    }

    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        return QueueAwardResult.Error("db_write_timeout")
    }
    if (writeError != null) {
        return QueueAwardResult.Error("db_write_failed")
    }

    return QueueAwardResult.Queued
}

internal fun shouldQueueAward(result: UpstreamResult): Boolean = result is UpstreamResult.NetworkError

internal fun shouldCacheResponse(responseBody: String): Boolean =
    SUCCESS_TRUE_REGEX.containsMatchIn(responseBody)

internal fun sanitizeHttpReasonPhrase(message: String?, code: Int): String {
    val sanitized = message
        ?.replace("\r", " ")
        ?.replace("\n", " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()

    if (!sanitized.isNullOrEmpty()) return sanitized

    return when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Response"
    }
}

internal fun proxyHttpResponse(code: Int, message: String, body: String): String {
    val safeMessage = sanitizeHttpReasonPhrase(message, code)
    return "HTTP/1.1 $code $safeMessage\r\n" +
           "Content-Type: application/json\r\n" +
           "Content-Length: ${body.toByteArray().size}\r\n" +
           "Connection: close\r\n\r\n" +
           body
}

internal fun proxyHttpOk(body: String): String =
    proxyHttpResponse(200, "OK", body)

internal fun proxyHttpGameIdCacheMiss(): String =
    proxyHttpOk("""{"Success":false,"Error":"Game not cached. Launch this game while online first.","GameID":0}""")

internal fun proxyHttpError(code: Int, message: String): String {
    val body = """{"Success":false,"Error":"$message"}"""
    return proxyHttpResponse(code, message, body)
}
