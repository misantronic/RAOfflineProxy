package com.raofflineproxy

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.security.MessageDigest

const val RA_HOST = "https://retroachievements.org"
private const val PROXY_HOST = "127.0.0.1"
const val PROXY_UA_TAG = "RAOfflineProxy"

val sharedHttpClient: OkHttpClient = OkHttpClient.Builder().build()

private val TOKEN_QUERY_REGEX = Regex("""([?&])t=[^&]*""")
private val TOKEN_FORM_REGEX = Regex("""(^|&)t=[^&]*""")

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

fun proxyPort(context: Context): Int = PrefsConstants.loadProxyPort(context)

fun proxyValue(port: Int): String = "${proxyHost()}:$port"

fun proxyValue(context: Context): String = proxyValue(proxyPort(context))

fun proxyBase(port: Int): String = "http://${proxyValue(port)}"

fun proxyBase(context: Context): String = proxyBase(proxyPort(context))

fun proxyUserAgent(original: String): String {
    if (original.contains(PROXY_UA_TAG)) return original
    return "$original $PROXY_UA_TAG/${BuildConfig.VERSION_NAME}"
}

fun redactTokens(input: String): String =
    TOKEN_QUERY_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }

fun redactFormBody(input: String): String =
    TOKEN_FORM_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }
