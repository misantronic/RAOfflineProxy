package com.raofflineproxy

import android.content.Context
import android.net.Uri

object PrefsConstants {
    const val PREFS_NAME = "ra_proxy_prefs"
    const val KEY_SAF_TREE_URI = "saf_tree_uri"
    const val KEY_AUTOSTART_PROXY = "autostart_proxy"
    const val KEY_HARDCORE_WAS_ENABLED = "hardcore_was_enabled"

    fun loadSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_TREE_URI, null)
            ?.let { Uri.parse(it) }

    fun saveSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SAF_TREE_URI, uri.toString()).apply()
    }
}
