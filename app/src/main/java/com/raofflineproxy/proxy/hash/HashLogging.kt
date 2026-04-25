package com.raofflineproxy.proxy.hash

import android.util.Log

internal fun logInfo(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}

internal fun logWarn(tag: String, message: String) {
    runCatching { Log.w(tag, message) }
}
