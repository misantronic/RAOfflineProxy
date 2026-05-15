package com.raofflineproxy

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.edit
import org.json.JSONArray

object PrefsConstants {
    const val PREFS_NAME = "ra_proxy_prefs"
    const val KEY_SAF_TREE_URI = "saf_tree_uri"
    const val KEY_RETROARCH_SAF_TREE_URI = "retroarch_saf_tree_uri"
    const val KEY_DOLPHIN_SAF_TREE_URI = "dolphin_saf_tree_uri"
    const val KEY_SMART_CACHE_ROM_SAF_TREE_URI = "smart_cache_rom_saf_tree_uri"
    const val KEY_SMART_CACHE_ROM_SAF_TREE_URIS = "smart_cache_rom_saf_tree_uris"
    const val KEY_AUTOSTART_PROXY = "autostart_proxy"
    const val KEY_ENABLE_RETROARCH = "enable_retroarch"
    const val KEY_ENABLE_DOLPHIN = "enable_dolphin"
    const val KEY_RETROARCH_HARDCORE_WAS_ENABLED = "retroarch_hardcore_was_enabled"
    const val KEY_DOLPHIN_HARDCORE_WAS_ENABLED = "dolphin_hardcore_was_enabled"
    const val KEY_SKIP_NEXT_CFG_REVERT = "skip_next_cfg_revert"
    const val KEY_RETROARCH_PATCHED_THIS_RUN = "retroarch_patched_this_run"
    const val KEY_DOLPHIN_PATCHED_THIS_RUN = "dolphin_patched_this_run"
    const val KEY_PROXY_PORT = "proxy_port"

    const val DEFAULT_PROXY_PORT = 8080
    private const val MIN_PROXY_PORT = 1024
    private const val MAX_PROXY_PORT = 65535

    fun loadSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RETROARCH_SAF_TREE_URI, null)
                ?.toUri()
                ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_SAF_TREE_URI, null)
                    ?.toUri()

    fun saveSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SAF_TREE_URI, uri.toString())
                putString(KEY_RETROARCH_SAF_TREE_URI, uri.toString())
            }
    }

    fun clearSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_SAF_TREE_URI)
                remove(KEY_RETROARCH_SAF_TREE_URI)
            }
    }

    fun loadDolphinSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOLPHIN_SAF_TREE_URI, null)
            ?.toUri()

    fun saveDolphinSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DOLPHIN_SAF_TREE_URI, uri.toString()) }
    }

    fun clearDolphinSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_DOLPHIN_SAF_TREE_URI) }
    }

    fun loadSmartCacheRomSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SMART_CACHE_ROM_SAF_TREE_URI, null)
            ?.toUri()

    fun loadSmartCacheRomSafUris(context: Context): List<Uri> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SMART_CACHE_ROM_SAF_TREE_URIS, null)
        if (!stored.isNullOrBlank()) {
            return runCatching { JSONArray(stored) }
                .getOrNull()
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index)
                                .takeIf { it.isNotBlank() }
                                ?.toUri()
                                ?.let(::add)
                        }
                    }
                }
                ?.distinctBy { uri -> uri.toString().lowercase() }
                ?: emptyList()
        }

        return loadSmartCacheRomSafUri(context)?.let(::listOf) ?: emptyList()
    }

    fun saveSmartCacheRomSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SMART_CACHE_ROM_SAF_TREE_URI, uri.toString())
                putString(KEY_SMART_CACHE_ROM_SAF_TREE_URIS, JSONArray().put(uri.toString()).toString())
            }
    }

    fun saveSmartCacheRomSafUris(context: Context, uris: List<Uri>) {
        val distinctUris = uris.distinctBy { uri -> uri.toString().lowercase() }
        val encoded = JSONArray().apply {
            distinctUris.forEach { put(it.toString()) }
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_SMART_CACHE_ROM_SAF_TREE_URIS, encoded)
                if (distinctUris.isNotEmpty()) {
                    putString(KEY_SMART_CACHE_ROM_SAF_TREE_URI, distinctUris.first().toString())
                } else {
                    remove(KEY_SMART_CACHE_ROM_SAF_TREE_URI)
                }
            }
    }

    fun addSmartCacheRomSafUri(context: Context, uri: Uri) {
        saveSmartCacheRomSafUris(context, loadSmartCacheRomSafUris(context) + uri)
    }

    fun clearSmartCacheRomSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_SMART_CACHE_ROM_SAF_TREE_URI)
                remove(KEY_SMART_CACHE_ROM_SAF_TREE_URIS)
            }
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
