package com.raofflineproxy

import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.security.MessageDigest

const val RA_HOST = "https://retroachievements.org"
const val PROXY_PORT = 8080
const val PROXY_HOST = "127.0.0.1"
const val PROXY_BASE = "http://$PROXY_HOST:$PROXY_PORT"
const val PROXY_VALUE = "$PROXY_HOST:$PROXY_PORT"
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

fun proxyUserAgent(original: String): String {
    if (original.contains(PROXY_UA_TAG)) return original
    return "$original $PROXY_UA_TAG/${BuildConfig.VERSION_NAME}"
}

fun redactTokens(input: String): String =
    TOKEN_QUERY_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }

fun redactFormBody(input: String): String =
    TOKEN_FORM_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }
