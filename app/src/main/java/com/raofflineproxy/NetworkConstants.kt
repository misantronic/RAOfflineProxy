package com.raofflineproxy

const val RA_HOST = "https://retroachievements.org"
const val PROXY_PORT = 8080
const val PROXY_HOST = "127.0.0.1"
const val PROXY_BASE = "http://$PROXY_HOST:$PROXY_PORT"
const val PROXY_VALUE = "$PROXY_HOST:$PROXY_PORT"
const val PROXY_UA_TAG = "RAOfflineProxy"

private val TOKEN_QUERY_REGEX = Regex("""([?&])t=[^&]*""")
private val TOKEN_FORM_REGEX = Regex("""(^|&)t=[^&]*""")

fun proxyUserAgent(original: String): String {
    if (original.contains(PROXY_UA_TAG)) return original
    return "$original $PROXY_UA_TAG/${BuildConfig.VERSION_NAME}"
}

fun redactTokens(input: String): String =
    TOKEN_QUERY_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }

fun redactFormBody(input: String): String =
    TOKEN_FORM_REGEX.replace(input) { "${it.groupValues[1]}t=<token>" }
