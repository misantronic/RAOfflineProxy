package com.raofflineproxy.proxy

import android.util.Base64
import android.util.Log
import com.raofflineproxy.PROXY_PORT
import com.raofflineproxy.PROXY_VALUE
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.extractFormParam
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.redactFormBody
import com.raofflineproxy.redactTokens
import com.raofflineproxy.sha256Hex
import com.raofflineproxy.sharedHttpClient
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "RAProxy"
private const val MAX_WORKER_THREADS = 8
private const val SOCKET_TIMEOUT_MS = 30_000
private const val MAX_REQUEST_BODY_BYTES = 1_048_576 // 1 MiB — rcheevos requests are small

// These requests mutate state on the RA server — do not serve from cache offline
private val AWARD_ACTIONS = setOf("awardachievement", "submitlbentry")

// Offline: return a canned success response instead of hitting the server
private val FAKE_OFFLINE_SUCCESS_ACTIONS = setOf("ping")

// These requests are safe to cache and serve offline
private val CACHEABLE_ACTIONS = setOf("patch", "gameid", "achievements", "hashlibrary", "login2", "unlocks", "startsession")

// Headers OkHttp manages itself — never forward these
private val SKIP_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding")

class ProxyServer(
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean
) {
    private val executor = ThreadPoolExecutor(
        2, MAX_WORKER_THREADS,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue()
    )

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        running = true
        val bindHost = PROXY_VALUE.substringBefore(':')
        serverSocket = ServerSocket(PROXY_PORT, 50, InetAddress.getByName(bindHost))
        executor.execute { acceptLoop() }
        Log.i(TAG, "Proxy started on $bindHost:$PROXY_PORT")
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
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

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
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

                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "GET" }
                val path = parts.getOrElse(1) { "/" }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
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
        Log.i(TAG, "Request: $method ${redactTokens(path)} body=${redactFormBody(rawBody)} action=$action online=${isOnline()}")

        val userAgent = headers["user-agent"]
        if (!userAgent.isNullOrEmpty()) {
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
            val upstream = forwardToRA("POST", path, rawBody, headers)
            if (upstream != null) return httpOk(upstream)
        }
        queueAward(path, rawBody, headers)
        val score = fetchCachedScore(path, rawBody)
        return httpOk("""{"Success":true,"Score":$score,"SoftcoreScore":0,"AchievementID":0,"Error":"queued_offline"}""")
    }

    private fun handleOnlineRequest(method: String, path: String, rawBody: String, action: String?, headers: Map<String, String>): String {
        val upstream = forwardToRA(method, path, rawBody, headers)
        val isValidJson = upstream != null && runCatching { JSONObject(upstream).has("Success") }.getOrDefault(false)
        if (isValidJson && action in CACHEABLE_ACTIONS) {
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
                        cacheUnlocks(gameId, LoginCredentials(user, token), userAgent)
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
        val latch = java.util.concurrent.CountDownLatch(1)
        scope.launch(Dispatchers.IO) {
            cached = db.cacheDao().get(key)
                ?: db.cacheDao().getByPrefix("$key:")
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
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

    private fun forwardToRA(method: String, path: String, rawBody: String, headers: Map<String, String>): String? {
        return try {
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

            sharedHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                Log.d(TAG, "← RA ${resp.code} for ${redactTokens(path)} (${body.length} bytes)")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Upstream returned ${resp.code} for ${redactTokens(path)}")
                    return null
                }
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upstream request failed: ${e.message}")
            null
        }
    }

    private fun queueAward(path: String, rawBody: String, headers: Map<String, String>) {
        val userAgent = headers["user-agent"] ?: ""
        val achievementId = extractParam("a", path, rawBody)?.toIntOrNull() ?: 0
        val queuedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val prevHash = db.pendingAwardDao().getLatest()?.payloadHash ?: "genesis"
            val canonicalPayload = "$achievementId|$path|$rawBody|$queuedAt"
            val payloadHash = sha256Hex(canonicalPayload)
            val signInput = "$payloadHash:$prevHash"
            val signature = runCatching {
                Base64.encodeToString(AwardKeyManager.sign(signInput.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }.getOrElse { e ->
                Log.e(TAG, "Award signing failed: ${e.message}")
                ""
            }
            val signedAt = System.currentTimeMillis()
            db.pendingAwardDao().upsert(
                PendingAward(
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
            )
            Log.i(TAG, "Award queued and signed: achievementId=$achievementId")
        }
    }

    private fun fetchCachedScore(path: String, rawBody: String): Int {
        val user = extractParam("u", path, rawBody) ?: return 0
        val key = CacheKeys.login(user)
        var score = 0
        val latch = java.util.concurrent.CountDownLatch(1)
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
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
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
}

internal fun proxyIsHardcoreRequest(path: String, rawBody: String): Boolean =
    proxyExtractParam("h", path, rawBody) == "1"

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
        else -> if (hardcore.isNotEmpty()) "$action:$gameId:$user:$hardcore" else "$action:$gameId:$user"
    }
}

internal fun proxyExtractParam(param: String, path: String, body: String): String? {
    val fromPath = "http://x$path".toHttpUrlOrNull()?.queryParameter(param)
    if (fromPath != null) return fromPath
    return extractFormParam(body, param)
}

internal fun proxyHttpOk(body: String): String =
    "HTTP/1.1 200 OK\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: ${body.toByteArray().size}\r\n" +
    "Connection: close\r\n\r\n" +
    body

internal fun proxyHttpGameIdCacheMiss(): String =
    proxyHttpOk("""{"Success":false,"Error":"Game not cached. Launch this game while online first.","GameID":0}""")

internal fun proxyHttpError(code: Int, message: String): String {
    val body = """{"Success":false,"Error":"$message"}"""
    return "HTTP/1.1 $code $message\r\n" +
           "Content-Type: application/json\r\n" +
           "Content-Length: ${body.toByteArray().size}\r\n" +
           "Connection: close\r\n\r\n" +
           body
}
