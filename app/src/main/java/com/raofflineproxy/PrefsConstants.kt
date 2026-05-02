package com.raofflineproxy

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.edit

object PrefsConstants {
    const val PREFS_NAME = "ra_proxy_prefs"
    const val KEY_SAF_TREE_URI = "saf_tree_uri"
    const val KEY_AUTOSTART_PROXY = "autostart_proxy"
    const val KEY_HARDCORE_WAS_ENABLED = "hardcore_was_enabled"
    const val KEY_SKIP_NEXT_CFG_REVERT = "skip_next_cfg_revert"
    const val KEY_PROXY_PORT = "proxy_port"

    const val DEFAULT_PROXY_PORT = 8080
    private const val MIN_PROXY_PORT = 1024
    private const val MAX_PROXY_PORT = 65535

    fun loadSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAF_TREE_URI, null)?.toUri()

    fun saveSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_SAF_TREE_URI, uri.toString()) }
    }

    fun clearSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_SAF_TREE_URI) }
    }

    fun loadProxyPort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)
            .takeIf(::isValidProxyPort)
            ?: DEFAULT_PROXY_PORT

    fun saveProxyPort(context: Context, port: Int) {
        require(isValidProxyPort(port)) { "Invalid proxy port: $port" }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_PROXY_PORT, port) }
    }

    fun isValidProxyPort(port: Int): Boolean = port in MIN_PROXY_PORT..MAX_PROXY_PORT
}
