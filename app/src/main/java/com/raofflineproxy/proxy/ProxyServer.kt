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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
private val NORMALIZED_PATCH_ID_REGEX = Regex(""""ID"\s*:\s*(\d+)""")
private val NORMALIZED_PATCH_MARKER_REGEX = Regex("""^patch:(\d+)$""")

// These requests mutate state on the RA server — do not serve from cache offline
private val AWARD_ACTIONS = setOf("awardachievement", "submitlbentry")

// Offline: return a canned success response instead of hitting the server
private val FAKE_OFFLINE_SUCCESS_ACTIONS = setOf("ping", "postactivity")

// These requests are safe to cache and serve offline
private val CACHEABLE_ACTIONS = setOf("patch", "achievementsets", "gameid", "achievements", "hashlibrary", "login2", "unlocks")

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

private sealed interface ProxyResponse {
    data class Json(val code: Int, val message: String, val body: String) : ProxyResponse
    data class Bytes(val body: ByteArray) : ProxyResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Bytes

            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            return body.contentHashCode()
        }
    }
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
                val output = socket.getOutputStream()

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
                    writeTextResponse(output, httpError(parsedRequestLine.statusCode, parsedRequestLine.message))
                    return
                }
                parsedRequestLine as ParsedRequestLineResult.Valid
                val method = parsedRequestLine.method
                val path = parsedRequestLine.path

                val transferEncoding = headers["transfer-encoding"]
                val transferEncodingError = validateTransferEncoding(transferEncoding)
                if (transferEncodingError != null) {
                    writeTextResponse(output, httpError(transferEncodingError.first, transferEncodingError.second))
                    return
                }

                val rawBody = if (isChunkedTransferEncoding(transferEncoding)) {
                    val chunkedBody = readChunkedBody(reader)
                    if (chunkedBody == null) {
                        Log.w(TAG, "Failed to read chunked request body")
                        writeTextResponse(output, httpError(400, "bad chunked body"))
                        return
                    }
                    chunkedBody
                } else {
                    val contentLengthHeader = headers["content-length"]
                    val parsedContentLength = parseContentLength(contentLengthHeader)
                    if (parsedContentLength == null) {
                        Log.w(TAG, "Invalid content-length header: $contentLengthHeader")
                        writeTextResponse(output, httpError(400, "bad content length"))
                        return
                    }
                    val contentLength = parsedContentLength
                    if (contentLength !in 0..MAX_REQUEST_BODY_BYTES) {
                        Log.w(TAG, "Rejected request body length: $contentLength")
                        writeTextResponse(output, httpError(413, "request body too large"))
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
                        Log.w(TAG, "Body read mismatch: expected=$contentLength actual=$totalRead")
                        writeTextResponse(output, httpError(bodyReadError.first, bodyReadError.second))
                        return
                    }
                    String(bodyChars, 0, totalRead)
                }

                val response = processRequest(method, path, rawBody, headers)
                writeResponse(output, response)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Connection timed out: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Connection handling error: ${e.message}")
            }
        }
    }

    private fun processRequest(method: String, path: String, rawBody: String, headers: Map<String, String>): ProxyResponse {
        if (isStaticAssetRequest(path)) {
            val cachedAsset = resolveCachedStaticAsset(context, path)
            if (cachedAsset != null) {
                Log.d(TAG, "Static asset served from cache: ${redactTokens(path)}")
                return ProxyResponse.Bytes(httpFile(cachedAsset))
            }
            Log.d(TAG, "Static asset skipped: ${redactTokens(path)}")
            return ProxyResponse.Bytes(httpNoContent().toHttpBytes())
        }

        return processApiRequest(method, path, rawBody, headers)
    }

    private fun processApiRequest(method: String, path: String, rawBody: String, headers: Map<String, String>): ProxyResponse {

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
                if (upstream != null) okJson(upstream) else errorJson(503, "upstream unavailable")
            }
            action in FAKE_OFFLINE_SUCCESS_ACTIONS && !isOnline() -> {
                Log.i(TAG, "Fake success offline: action=$action")
                okJson("""{"Success":true}""")
            }
            action == "startsession" && !isOnline() -> handleStartSessionRequest(path, rawBody)
            isOnline() -> handleOnlineRequest(method, path, rawBody, action, headers)
            else -> handleOfflineRequest(path, rawBody, action)
        }
    }

    private fun isHardcoreRequest(path: String, rawBody: String): Boolean =
        proxyIsHardcoreRequest(path, rawBody)

    private fun handleAwardRequest(path: String, rawBody: String, headers: Map<String, String>): ProxyResponse {
        if (isHardcoreRequest(path, rawBody)) {
            Log.w(TAG, "Rejecting hardcore award — hardcore mode is not supported by this proxy")
            return errorJson(403, "hardcore_not_supported")
        }

        if (isOnline()) {
            return when (val upstream = forwardToRAResult("POST", path, rawBody, headers)) {
                is UpstreamResult.Success -> ProxyResponse.Json(upstream.statusCode, upstream.message, upstream.body)
                is UpstreamResult.HttpError -> {
                    Log.w(TAG, "Award request rejected by upstream: ${upstream.statusCode} ${upstream.message}")
                    ProxyResponse.Json(upstream.statusCode, upstream.message, upstream.body)
                }
                is UpstreamResult.NetworkError -> {
                    Log.w(TAG, "Award request will be queued due to upstream network failure: ${upstream.message}")
                    queueOfflineAward(path, rawBody, headers)
                }
            }
        }

        return queueOfflineAward(path, rawBody, headers)
    }

    private fun handleStartSessionRequest(path: String, rawBody: String): ProxyResponse {
        val gameId = extractParam("g", path, rawBody)?.toIntOrNull()
        val user = extractParam("u", path, rawBody)
        if (gameId == null || user.isNullOrEmpty()) {
            return errorJson(400, "bad request")
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
            okJson(cached!!.responseBody)
        } else {
            Log.e(TAG, "Failed to synthesize startsession for gameId=$gameId user=$user")
            errorJson(503, "no cached response")
        }
    }

    private fun queueOfflineAward(path: String, rawBody: String, headers: Map<String, String>): ProxyResponse {
        when (val result = queueAward(path, rawBody, headers)) {
            QueueAwardResult.Queued -> Unit
            is QueueAwardResult.Error -> {
                Log.e(TAG, "Award queueing failed: ${result.message}")
                return errorJson(500, "award_queue_failed")
            }
        }

        val achievementId = extractParam("a", path, rawBody)?.toIntOrNull() ?: 0
        val score = fetchCachedScore(path, rawBody)
        return okJson("""{"Success":true,"Score":$score,"SoftcoreScore":0,"AchievementID":$achievementId,"Error":"queued_offline"}""")
    }

    private fun handleOnlineRequest(method: String, path: String, rawBody: String, action: String?, headers: Map<String, String>): ProxyResponse {
        val upstream = forwardToRA(method, path, rawBody, headers)
        val shouldCache = upstream != null && shouldCacheResponse(upstream)
        if (shouldCache && action in CACHEABLE_ACTIONS) {
            val normalizedBody = normalizeCachedResponse(action, path, rawBody, upstream)
            val rawKey = cacheKey(path, rawBody)
            val key = normalizedCacheKey(action, path, rawBody, normalizedBody)
            val rawBodyToCache = compactCachedRawResponse(action, upstream)
            val userAgent = headers["user-agent"] ?: ""
            scope.launch(Dispatchers.IO) {
                db.cacheDao().upsert(CacheEntry(cacheKey = rawKey, responseBody = rawBodyToCache))
                Log.i(TAG, "Cached: $rawKey")
                if (key != rawKey) {
                    db.cacheDao().upsert(CacheEntry(cacheKey = key, responseBody = normalizedBody))
                    Log.i(TAG, "Cached: $key")
                }
                if (action == "patch" || action == "achievementsets") {
                    val gameId = extractParam("g", path, rawBody)?.toIntOrNull()
                        ?: extractNormalizedGameId(normalizedBody)
                    val user = extractParam("u", path, rawBody)
                    val token = extractParam("t", path, rawBody)
                    if (gameId != null) {
                        cachePatchImages(context, gameId, userAgent, normalizedBody)
                    }
                    if (gameId != null && user != null && token != null) {
                        cacheUnlocks(context, gameId, LoginCredentials(user, token), userAgent, db)
                        cacheSession(gameId, LoginCredentials(user, token), db)
                    }
                }
            }
            return okJson(upstream)
        }
        if (upstream != null) {
            Log.i(TAG, "Forwarded (not cached) action=$action")
            return okJson(upstream)
        }
        return errorJson(503, "upstream unavailable")
    }

    private fun handleOfflineRequest(path: String, rawBody: String, action: String?): ProxyResponse {
        if (action !in CACHEABLE_ACTIONS) {
            return errorJson(503, "offline")
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
            if (action == "unlocks") {
                ensureOfflineStartSessionCache(path, rawBody)
            }
            Log.i(TAG, "Cache HIT: $key (${cached!!.responseBody.length} bytes)")
            okJson(cached!!.responseBody)
        } else {
            Log.e(TAG, "Cache MISS: $key")
            if (action == "gameid") {
                okJson("""{"Success":false,"Error":"Game not cached. Launch this game while online first.","GameID":0}""")
            } else {
                errorJson(503, "no cached response")
            }
        }
    }

    private fun ensureOfflineStartSessionCache(path: String, rawBody: String) {
        val gameId = extractParam("g", path, rawBody)?.toIntOrNull() ?: return
        val user = extractParam("u", path, rawBody) ?: return
        val latch = CountDownLatch(1)

        scope.launch(Dispatchers.IO) {
            runCatching {
                cacheSession(gameId, LoginCredentials(user, ""), db)
            }
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)
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
                if (action == "awardachievement") {
                    Log.d(TAG, "← RA awardachievement body: $body")
                }
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
            if (achievementId == WARNING_ACHIEVEMENT_ID) {
                Log.i(TAG, "Skipping softcore warning award: achievementId=$achievementId")
                return QueueAwardResult.Queued
            }

            val latch = CountDownLatch(1)
            var alreadyQueued = false
            var alreadyUnlocked = false
            val user = extractParam("u", path, rawBody)
            scope.launch(Dispatchers.IO) {
                alreadyQueued = db.pendingAwardDao().existsByAchievementIdAndStatus(achievementId)
                alreadyUnlocked = isAchievementAlreadyUnlocked(achievementId, user)
                latch.countDown()
            }
            latch.await(DB_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (alreadyQueued) {
                Log.i(TAG, "Award already queued: achievementId=$achievementId, skipping duplicate")
                return QueueAwardResult.Queued
            }
            if (alreadyUnlocked) {
                Log.i(TAG, "Award already unlocked in cache: achievementId=$achievementId, skipping offline queue")
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
                    prevHash = db.pendingAwardDao().getLatestByStatus()?.payloadHash ?: "genesis"
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

    private suspend fun isAchievementAlreadyUnlocked(achievementId: Int, user: String?): Boolean {
        if (achievementId <= 0 || user.isNullOrEmpty()) return false

        val gameId = buildAchievementGameIds(db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH))[achievementId]
            ?: return false
        val unlocksBody = db.cacheDao().get(CacheKeys.unlocks(gameId, user))?.responseBody ?: return false
        val unlocks = runCatching {
            JSONObject(unlocksBody).optJSONArray("UserUnlocks")
        }.getOrNull() ?: return false

        return filterWarningAchievementIds((0 until unlocks.length()).map { unlocks.optInt(it) })
            .contains(achievementId)
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

    private fun httpError(code: Int, message: String): String = proxyHttpError(code, message)

    private fun httpNoContent(): String = proxyHttpNoContent()

    private fun httpFile(file: File): ByteArray = proxyHttpFile(file)

    private fun writeTextResponse(output: java.io.OutputStream, response: String) {
        output.write(response.toHttpBytes())
        output.flush()
    }

    private fun writeBinaryResponse(output: java.io.OutputStream, response: ByteArray) {
        output.write(response)
        output.flush()
    }

    private fun writeResponse(output: java.io.OutputStream, response: ProxyResponse) {
        when (response) {
            is ProxyResponse.Bytes -> writeBinaryResponse(output, response.body)
            is ProxyResponse.Json -> writeJsonResponse(output, response.code, response.message, response.body)
        }
    }

    private fun writeJsonResponse(output: java.io.OutputStream, code: Int, message: String, body: String) {
        val safeMessage = sanitizeHttpReasonPhrase(message, code)
        val headers = (
            "HTTP/1.1 $code $safeMessage\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${utf8Length(body)}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        output.write(headers)
        BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.write(body)
            writer.flush()
        }
        output.flush()
    }

    private fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        2, MAX_WORKER_THREADS,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue()
    )
}

internal fun proxyIsHardcoreRequest(path: String, rawBody: String): Boolean =
    proxyExtractParam("h", path, rawBody) == "1"

internal fun isStaticAssetRequest(path: String): Boolean {
    val cleanPath = path.substringBefore('?')
    return cleanPath.startsWith("/Badge/", ignoreCase = true) ||
        cleanPath.startsWith("/Images/", ignoreCase = true) ||
        cleanPath.startsWith("/UserPic/", ignoreCase = true)
}

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
    if (!transferEncoding.isNullOrBlank() &&
        !transferEncoding.equals("identity", ignoreCase = true) &&
        !isChunkedTransferEncoding(transferEncoding)
    ) {
        501 to "transfer encoding not supported"
    } else {
        null
    }

internal fun isChunkedTransferEncoding(transferEncoding: String?): Boolean =
    transferEncoding
        ?.split(',')
        ?.any { it.trim().equals("chunked", ignoreCase = true) } == true

internal fun parseContentLength(contentLengthHeader: String?): Int? =
    if (contentLengthHeader == null) 0 else contentLengthHeader.toIntOrNull()

internal fun validateBodyRead(expectedLength: Int, actualLength: Int): Pair<Int, String>? =
    if (actualLength != expectedLength) {
        400 to "incomplete request body"
    } else {
        null
    }

internal fun readChunkedBody(reader: BufferedReader): String? {
    val body = StringBuilder()

    while (true) {
        val sizeLine = reader.readLine() ?: return null
        val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
        if (chunkSize < 0) return null
        if (body.length + chunkSize > MAX_REQUEST_BODY_BYTES) return null
        if (chunkSize == 0) {
            while (true) {
                val trailer = reader.readLine() ?: return null
                if (trailer.isEmpty()) {
                    return body.toString()
                }
            }
        }

        val chunkChars = CharArray(chunkSize)
        var totalRead = 0
        while (totalRead < chunkSize) {
            val read = reader.read(chunkChars, totalRead, chunkSize - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        body.appendRange(chunkChars, 0, totalRead)

        val chunkTerminator = reader.readLine() ?: return null
        if (chunkTerminator.isNotEmpty()) return null
    }
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
        "achievementsets" -> "$action:$hash:$user"
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
           "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
           "Connection: close\r\n\r\n" +
           body
}

private fun okJson(body: String): ProxyResponse = ProxyResponse.Json(200, "OK", body)

private fun errorJson(code: Int, message: String): ProxyResponse =
    ProxyResponse.Json(code, message, """{"Success":false,"Error":"$message"}""")

private fun utf8Length(body: String): Int {
    var count = 0
    var index = 0
    while (index < body.length) {
        val ch = body[index]
        val code = ch.code
        when {
            code < 0x80 -> count += 1
            code < 0x800 -> count += 2
            ch.isHighSurrogate() && index + 1 < body.length && body[index + 1].isLowSurrogate() -> {
                count += 4
                index += 1
            }
            else -> count += 3
        }
        index += 1
    }
    return count
}

internal fun proxyHttpOk(body: String): String =
    proxyHttpResponse(200, "OK", body)

internal fun proxyHttpNoContent(): String =
    "HTTP/1.1 204 No Content\r\n" +
        "Content-Length: 0\r\n" +
        "Connection: close\r\n\r\n"

internal fun proxyHttpFile(file: File): ByteArray {
    val bytes = file.readBytes()
    val headers = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: ${contentTypeForFile(file)}\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: close\r\n\r\n"
    return headers.toByteArray(Charsets.US_ASCII) + bytes
}

private fun String.toHttpBytes(): ByteArray = toByteArray(Charsets.UTF_8)

private fun JSONObject.intOrNull(name: String): Int? = runCatching { getInt(name) }.getOrNull()

private fun JSONObject.intOrDefault(name: String, default: Int): Int = intOrNull(name) ?: default

private fun JSONObject.longOrDefault(name: String, default: Long): Long = runCatching { getLong(name) }.getOrDefault(default)

private fun JSONObject.doubleOrNull(name: String): Double? = runCatching { getDouble(name) }.getOrNull()

private fun JSONObject.stringOrNull(name: String): String? = runCatching { getString(name) }.getOrNull()

private fun JSONObject.stringOrDefault(name: String, default: String): String = stringOrNull(name) ?: default

private fun JSONObject.arrayOrNull(name: String): JSONArray? = runCatching { getJSONArray(name) }.getOrNull()

private fun JSONObject.valueOrNull(name: String): Any? = runCatching { get(name) }.getOrNull()

internal fun normalizeCachedResponse(action: String?, path: String, body: String, responseBody: String): String =
    if (action == "achievementsets") {
        normalizeAchievementSetsResponse(path, body, responseBody)
    } else if (action == "unlocks") {
        filterWarningAchievementFromUnlocksResponse(responseBody)
    } else {
        responseBody
    }

internal fun compactCachedRawResponse(action: String?, responseBody: String): String =
    if (action == "achievementsets") {
        compactAchievementSetsResponse(responseBody)
    } else if (action == "unlocks") {
        filterWarningAchievementFromUnlocksResponse(responseBody)
    } else {
        responseBody
    }

internal const val WARNING_ACHIEVEMENT_ID = 101000001

internal fun filterWarningAchievementIds(ids: Iterable<Int>): List<Int> =
    ids.filter { it > 0 && it != WARNING_ACHIEVEMENT_ID }

internal fun filterWarningAchievementFromUnlocksResponse(responseBody: String): String {
    val source = try {
        JSONObject(responseBody)
    } catch (_: Exception) {
        return responseBody
    }

    val unlocks = source.optJSONArray("UserUnlocks") ?: return responseBody
    val filteredUnlocks = JSONArray().apply {
        for (id in filterWarningAchievementIds((0 until unlocks.length()).map { unlocks.optInt(it) })) {
            put(id)
        }
    }

    source.put("UserUnlocks", filteredUnlocks)
    return source.toString()
}

private fun buildAchievementGameIds(patchEntries: List<CacheEntry>): Map<Int, Int> = buildMap {
    patchEntries.forEach { entry ->
        val gameId = CacheKeys.parseGameIdFromPatchKey(entry.cacheKey) ?: return@forEach
        val patchData = runCatching {
            JSONObject(entry.responseBody).getJSONObject("PatchData")
        }.getOrNull() ?: return@forEach
        val achievements = patchData.optJSONArray("Achievements") ?: return@forEach
        for (index in 0 until achievements.length()) {
            val achievement = achievements.optJSONObject(index) ?: continue
            val achievementId = achievement.optInt("ID")
            if (achievementId > 0) {
                putIfAbsent(achievementId, gameId)
            }
        }
    }
}

private fun filterWarningAchievementDefinitions(achievements: JSONArray?): JSONArray {
    if (achievements == null) return JSONArray()

    return JSONArray().apply {
        for (index in 0 until achievements.length()) {
            val achievement = achievements.optJSONObject(index) ?: continue
            if (achievement.optInt("ID") == WARNING_ACHIEVEMENT_ID) continue
            put(achievement)
        }
    }
}

internal fun compactAchievementSetsResponse(responseBody: String): String {
    val source = try {
        JSONObject(responseBody)
    } catch (_: Exception) {
        return responseBody
    }

    if (!shouldCacheResponse(responseBody)) {
        return responseBody
    }

    val gameId = source.intOrNull("GameId")?.takeIf { it > 0 } ?: return responseBody
    val sets = source.arrayOrNull("Sets") ?: return responseBody

    val compact = JSONObject().apply {
        put("Success", true)

        put("GameId", source.intOrDefault("GameId", gameId))
        put("Title", source.stringOrDefault("Title", ""))
        put("ConsoleId", source.intOrDefault("ConsoleId", 0))
        put("ImageIconUrl", source.stringOrDefault("ImageIconUrl", ""))
        source.valueOrNull("RichPresenceGameId")?.let { put("RichPresenceGameId", it) }
        source.valueOrNull("RichPresencePatch")?.let { put("RichPresencePatch", it) }
        put("Sets", compactAchievementSets(sets, gameId, source))
    }

    return compact.toString()
}

private fun compactAchievementSets(sets: JSONArray, gameId: Int, source: JSONObject): JSONArray = JSONArray().apply {
    for (index in 0 until sets.length()) {
        val set = runCatching { sets.getJSONObject(index) }.getOrNull() ?: continue
        put(
            JSONObject().apply {
                put("AchievementSetId", set.intOrDefault("AchievementSetId", 0))
                put("GameId", set.intOrDefault("GameId", gameId))
                put("Title", set.stringOrDefault("Title", source.stringOrDefault("Title", "")))
                put("Type", set.stringOrDefault("Type", "core"))
                put("ImageIconUrl", set.stringOrDefault("ImageIconUrl", source.stringOrDefault("ImageIconUrl", "")))
                put("Achievements", compactAchievementDefinitions(set.arrayOrNull("Achievements")))
                put("Leaderboards", compactLeaderboardDefinitions(set.arrayOrNull("Leaderboards")))
            }
        )
    }
}

private fun compactAchievementDefinitions(achievements: JSONArray?): JSONArray {
    if (achievements == null) return JSONArray()

    return JSONArray().apply {
        for (index in 0 until achievements.length()) {
            val achievement = runCatching { achievements.getJSONObject(index) }.getOrNull() ?: continue
            if (achievement.intOrDefault("ID", 0) == WARNING_ACHIEVEMENT_ID) continue
            put(
                JSONObject().apply {
                    put("ID", achievement.intOrDefault("ID", 0))
                    put("Title", achievement.stringOrDefault("Title", ""))
                    put("Description", achievement.stringOrDefault("Description", ""))
                    put("Flags", achievement.intOrDefault("Flags", 0))
                    put("Points", achievement.intOrDefault("Points", 0))
                    put("MemAddr", achievement.stringOrDefault("MemAddr", ""))
                    put("Author", achievement.stringOrDefault("Author", ""))
                    put("BadgeName", achievement.stringOrDefault("BadgeName", ""))
                    put("Created", achievement.longOrDefault("Created", 0L))
                    put("Modified", achievement.longOrDefault("Modified", 0L))

                    achievement.stringOrNull("Type")?.takeIf { it.isNotEmpty() }?.let { put("Type", it) }
                    achievement.doubleOrNull("Rarity")?.let { put("Rarity", it) }
                    achievement.doubleOrNull("RarityHardcore")?.let { put("RarityHardcore", it) }
                    achievement.stringOrNull("BadgeURL")?.takeIf { it.isNotEmpty() }?.let { put("BadgeURL", it) }
                    achievement.stringOrNull("BadgeLockedURL")?.takeIf { it.isNotEmpty() }?.let { put("BadgeLockedURL", it) }
                }
            )
        }
    }
}

private fun compactLeaderboardDefinitions(leaderboards: JSONArray?): JSONArray {
    if (leaderboards == null) return JSONArray()

    return JSONArray().apply {
        for (index in 0 until leaderboards.length()) {
            val leaderboard = runCatching { leaderboards.getJSONObject(index) }.getOrNull() ?: continue
            put(
                JSONObject().apply {
                    put("ID", leaderboard.intOrDefault("ID", 0))
                    put("Title", leaderboard.stringOrDefault("Title", ""))
                    put("Description", leaderboard.stringOrDefault("Description", ""))
                    put("Mem", leaderboard.stringOrDefault("Mem", ""))
                    put("Format", leaderboard.stringOrDefault("Format", ""))
                    leaderboard.valueOrNull("LowerIsBetter")?.let { put("LowerIsBetter", it) }
                    leaderboard.valueOrNull("Hidden")?.let { put("Hidden", it) }
                }
            )
        }
    }
}

internal fun normalizeAchievementSetsResponse(path: String, body: String, responseBody: String): String {
    val source = try {
        JSONObject(responseBody)
    } catch (_: Exception) {
        return responseBody
    }

    if (!shouldCacheResponse(responseBody)) {
        return responseBody
    }

    val gameId = source.intOrNull("GameId")?.takeIf { it > 0 }
        ?: proxyExtractParam("g", path, body)?.toIntOrNull()
        ?: return responseBody

    val sets = source.arrayOrNull("Sets") ?: return responseBody
    val coreSet = findCoreAchievementSet(sets, gameId) ?: return responseBody

    val normalized = JSONObject().apply {
        put("Success", true)
        put("PatchData", JSONObject().apply {
            put("ID", source.intOrDefault("GameId", gameId))
            put("Title", source.stringOrDefault("Title", ""))
            put("ConsoleID", source.intOrDefault("ConsoleId", 0))
            putPatchImageFields(this, source.stringOrNull("ImageIconUrl")?.takeIf { it.isNotEmpty() })
            put("Achievements", filterWarningAchievementDefinitions(coreSet.arrayOrNull("Achievements")))
            put("Leaderboards", JSONArray())
        })
    }.toString()

    return normalized
}

internal fun normalizedCacheKey(action: String?, path: String, body: String, normalizedBody: String): String =
    if (action == "achievementsets") {
        val user = proxyExtractParam("u", path, body).orEmpty()
        val gameId = extractNormalizedGameId(normalizedBody)
        if (gameId != null && user.isNotEmpty()) {
            CacheKeys.patch(gameId, user)
        } else {
            proxyCacheKey(path, body)
        }
    } else {
        proxyCacheKey(path, body)
    }

internal fun extractNormalizedGameId(responseBody: String): Int? {
    NORMALIZED_PATCH_MARKER_REGEX.find(responseBody)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { return it }

    return NORMALIZED_PATCH_ID_REGEX.find(responseBody)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}

private fun findCoreAchievementSet(sets: JSONArray, gameId: Int): JSONObject? {
    var fallback: JSONObject? = null

    for (index in 0 until sets.length()) {
        val set = runCatching { sets.getJSONObject(index) }.getOrNull() ?: continue
        if (fallback == null) {
            fallback = set
        }

        val setGameId = set.intOrDefault("GameId", 0)
        val setType = set.stringOrDefault("Type", "")
        if (setGameId == gameId && setType.equals("core", ignoreCase = true)) {
            return set
        }
    }

    return fallback
}

internal fun contentTypeForFile(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/png"
}

internal fun proxyHttpGameIdCacheMiss(): String =
    proxyHttpOk("""{"Success":false,"Error":"Game not cached. Launch this game while online first.","GameID":0}""")

internal fun proxyHttpError(code: Int, message: String): String {
    val body = """{"Success":false,"Error":"$message"}"""
    return proxyHttpResponse(code, message, body)
}
