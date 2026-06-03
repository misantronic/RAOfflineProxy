package com.raofflineproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.math.max

const val RA_HOST = "https://retroachievements.org"
private const val PROXY_HOST = "127.0.0.1"
const val PROXY_UA_TAG = "RAOfflineProxy"
private const val REQUEST_THROTTLE_TAG = "RAProxy/RateLimit"
private const val REACHABILITY_TAG = "RAProxy/Reachability"
private const val RETROACHIEVEMENTS_API_MIN_INTERVAL_MS = 500L
private const val RETROACHIEVEMENTS_PROBE_INTERVAL_MS = 30_000L

val sharedHttpClient: OkHttpClient = OkHttpClient.Builder().build()

private val TOKEN_QUERY_REGEX = Regex("""([?&])t=[^&]*""")
private val TOKEN_FORM_REGEX = Regex("""(^|&)t=[^&]*""")

data class RetroAchievementsReachability(
    val reachable: Boolean = false,
    val checkedAt: Long = 0L
)

private object RetroAchievementsReachabilityTracker {
    private val _state = MutableStateFlow(RetroAchievementsReachability())

    @Synchronized
    fun current(): RetroAchievementsReachability = _state.value

    @Synchronized
    fun record(reachable: Boolean, checkedAt: Long = System.currentTimeMillis()): Boolean {
        val current = _state.value
        val updated = RetroAchievementsReachability(reachable = reachable, checkedAt = checkedAt)
        if (current == updated) return false
        _state.value = updated
        return current.reachable != reachable
    }

    @Synchronized
    fun reset() {
        _state.value = RetroAchievementsReachability()
    }
}

fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .toHexString()

fun parseFormParams(body: String): Map<String, String> =
    body.split("&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) null
        else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
    }.toMap()

fun extractFormParam(body: String, name: String): String? =
    parseFormParams(body)[name]

fun buildApiUrl(base: String, action: String, params: Map<String, String>): String {
    val builder = "$base/dorequest.php".toHttpUrlOrNull()?.newBuilder()
        ?: error("Invalid base URL: $base")

    builder.addQueryParameter("r", action)
    params.forEach { (name, value) ->
        builder.addQueryParameter(name, value)
    }
    return builder.build().toString()
}

fun proxyHost(): String = PROXY_HOST

fun isRetroAchievementsReachable(): Boolean =
    RetroAchievementsReachabilityTracker.current().reachable

fun markRetroAchievementsReachable(checkedAt: Long = System.currentTimeMillis()): Boolean =
    RetroAchievementsReachabilityTracker.record(reachable = true, checkedAt = checkedAt)

fun markRetroAchievementsUnreachable(checkedAt: Long = System.currentTimeMillis()): Boolean =
    RetroAchievementsReachabilityTracker.record(reachable = false, checkedAt = checkedAt)

internal fun resetRetroAchievementsReachabilityForTests() {
    RetroAchievementsReachabilityTracker.reset()
}

fun isValidatedNetwork(capabilities: NetworkCapabilities?): Boolean =
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

fun hasValidatedInternet(connectivityManager: ConnectivityManager): Boolean =
    connectivityManager.activeNetwork
        ?.let { connectivityManager.getNetworkCapabilities(it) }
        ?.let(::isValidatedNetwork)
        ?: false

fun shouldProbeRetroAchievements(
    force: Boolean = false,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (force) return true

    val current = RetroAchievementsReachabilityTracker.current()
    if (current.checkedAt == 0L) return true
    if (!current.reachable) return true

    return now - current.checkedAt >= RETROACHIEVEMENTS_PROBE_INTERVAL_MS
}

fun probeRetroAchievements(
    userAgent: String = "$PROXY_UA_TAG/${BuildConfig.VERSION_NAME}",
    force: Boolean = false,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!shouldProbeRetroAchievements(force = force, now = now)) {
        return isRetroAchievementsReachable()
    }

    val request = Request.Builder()
        .url(RA_HOST)
        .head()
        .header("User-Agent", userAgent.ifBlank { "$PROXY_UA_TAG/${BuildConfig.VERSION_NAME}" })
        .build()

    return try {
        sharedHttpClient.newCall(request).execute().use { response ->
            val reachable = response.code < 500
            if (reachable) {
                markRetroAchievementsReachable(now)
            } else {
                markRetroAchievementsUnreachable(now)
            }
            reachable
        }
    } catch (error: Exception) {
        Log.w(REACHABILITY_TAG, "Probe failed: ${error.message}")
        markRetroAchievementsUnreachable(now)
        false
    }
}

fun proxyPort(context: Context): Int = PrefsConstants.loadProxyPort(context)

fun proxyValue(port: Int): String = "${proxyHost()}:$port"

fun proxyValue(context: Context): String = proxyValue(proxyPort(context))

fun proxyBase(port: Int): String = "http://${proxyValue(port)}"

fun proxyUserAgent(original: String): String {
    if (original.contains(PROXY_UA_TAG)) return original
    return "$original $PROXY_UA_TAG/${BuildConfig.VERSION_NAME}"
}

internal fun isLoopbackPortAvailable(port: Int): Boolean = runCatching {
    val bindAddress = InetAddress.getByName(proxyHost())
    ServerSocket(port, 50, bindAddress).use { true }
}.getOrDefault(false)

fun redactTokens(input: String): String =
    TOKEN_QUERY_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }

fun redactFormBody(input: String): String =
    TOKEN_FORM_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }

private object RetroAchievementsRequestThrottle {
    private var nextAllowedAtMillis = 0L

    @Synchronized
    fun await(action: String) {
        val now = System.currentTimeMillis()
        val sleepMillis = max(0L, nextAllowedAtMillis - now)
        if (sleepMillis > 0) {
            Log.d(REQUEST_THROTTLE_TAG, "Delaying $action by ${sleepMillis}ms")
            Thread.sleep(sleepMillis)
        }
        nextAllowedAtMillis = System.currentTimeMillis() + RETROACHIEVEMENTS_API_MIN_INTERVAL_MS
    }
}

fun throttleRetroAchievementsApiRequest(action: String) {
    RetroAchievementsRequestThrottle.await(action)
}
