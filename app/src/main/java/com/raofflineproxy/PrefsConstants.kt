package com.raofflineproxy

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.content.edit
import com.raofflineproxy.update.AppUpdateInfo
import org.json.JSONArray
import org.json.JSONObject

object PrefsConstants {
    enum class PpssppRootMode {
        Unknown,
        DefaultPackagePath,
        CustomRoot
    }

    const val PREFS_NAME = "ra_proxy_prefs"
    const val KEY_SAF_TREE_URI = "saf_tree_uri"
    const val KEY_RETROARCH_SAF_TREE_URI = "retroarch_saf_tree_uri"
    const val KEY_RETROARCH_SMART_CACHE_SAF_TREE_URI = "retroarch_smart_cache_saf_tree_uri"
    const val KEY_DOLPHIN_SAF_TREE_URI = "dolphin_saf_tree_uri"
    const val KEY_PPSSPP_SAF_TREE_URI = "ppsspp_saf_tree_uri"
    const val KEY_PPSSPP_ROOT_MODE = "ppsspp_root_mode"
    const val KEY_SMART_CACHE_ROM_SAF_TREE_URI = "smart_cache_rom_saf_tree_uri"
    const val KEY_SMART_CACHE_ROM_SAF_TREE_URIS = "smart_cache_rom_saf_tree_uris"
    const val KEY_AUTOSTART_PROXY = "autostart_proxy"
    const val KEY_MANUAL_EMULATOR_PATCHING = "manual_emulator_patching"
    const val KEY_SHIZUKU_MANUAL_PATCHING_ENABLED = "shizuku_manual_patching_enabled"
    const val KEY_ENABLE_SMART_CACHING = "enable_smart_caching"
    const val KEY_ENABLE_RETROARCH = "enable_retroarch"
    const val KEY_ENABLE_DOLPHIN = "enable_dolphin"
    const val KEY_ENABLE_PPSSPP = "enable_ppsspp"
    const val KEY_ENABLE_ARMSX2 = "enable_armsx2"
    const val KEY_ENABLE_FLYCAST = "enable_flycast"
    const val KEY_ENABLE_MELONDUALDS = "enable_melondualds"
    const val KEY_ENABLE_MUPEN64 = "enable_mupen64"
    const val KEY_ENABLE_EMUCOREX = "enable_emucorex"
    const val KEY_ENABLE_ARMSX1 = "enable_armsx1"
    const val KEY_RETROARCH_HARDCORE_WAS_ENABLED = "retroarch_hardcore_was_enabled"
    const val KEY_DOLPHIN_HARDCORE_WAS_ENABLED = "dolphin_hardcore_was_enabled"
    const val KEY_PPSSPP_HARDCORE_WAS_ENABLED = "ppsspp_hardcore_was_enabled"
    const val KEY_DOLPHIN_GAME_SETTINGS_HARDCORE_OVERRIDES = "dolphin_game_settings_hardcore_overrides"
    const val KEY_SKIP_NEXT_CFG_REVERT = "skip_next_cfg_revert"
    const val KEY_PROXY_SHOULD_BE_RUNNING = "proxy_should_be_running"
    const val KEY_RETROARCH_PATCHED_THIS_RUN = "retroarch_patched_this_run"
    const val KEY_DOLPHIN_PATCHED_THIS_RUN = "dolphin_patched_this_run"
    const val KEY_PPSSPP_PATCHED_THIS_RUN = "ppsspp_patched_this_run"
    const val KEY_ARMSX2_PATCHED_THIS_RUN = "armsx2_patched_this_run"
    const val KEY_FLYCAST_PATCHED_THIS_RUN = "flycast_patched_this_run"
    const val KEY_MELONDUALDS_PATCHED_THIS_RUN = "melondualds_patched_this_run"
    const val KEY_MUPEN64_PATCHED_THIS_RUN = "mupen64_patched_this_run"
    const val KEY_EMUCOREX_PATCHED_THIS_RUN = "emucorex_patched_this_run"
    const val KEY_ARMSX1_PATCHED_THIS_RUN = "armsx1_patched_this_run"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_APP_UPDATE_CHECK_ENABLED = "app_update_check_enabled"
    const val KEY_APP_UPDATE_LAST_CHECKED_AT = "app_update_last_checked_at"
    const val KEY_APP_UPDATE_LAST_PROMPTED_AT = "app_update_last_prompted_at"
    const val KEY_HIDE_SUPPORT_BUTTON = "hide_support_button"
    private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    private const val KEY_AVAILABLE_APP_UPDATE = "available_app_update"

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

    fun loadRetroArchSmartCacheSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RETROARCH_SMART_CACHE_SAF_TREE_URI, null)
            ?.toUri()

    fun saveRetroArchSmartCacheSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_RETROARCH_SMART_CACHE_SAF_TREE_URI, uri.toString()) }
    }

    fun clearRetroArchSmartCacheSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_RETROARCH_SMART_CACHE_SAF_TREE_URI) }
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

    fun loadPpssppSafUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PPSSPP_SAF_TREE_URI, null)
            ?.toUri()

    fun loadPpssppRootMode(context: Context): PpssppRootMode =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PPSSPP_ROOT_MODE, PpssppRootMode.Unknown.name)
            ?.let { stored ->
                PpssppRootMode.entries.firstOrNull { it.name == stored }
            }
            ?: PpssppRootMode.Unknown

    fun savePpssppSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_PPSSPP_SAF_TREE_URI, uri.toString()) }
    }

    fun savePpssppRootMode(context: Context, mode: PpssppRootMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_PPSSPP_ROOT_MODE, mode.name) }
    }

    fun clearPpssppSafUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_PPSSPP_SAF_TREE_URI) }
    }

    fun loadDolphinGameSettingsHardcoreOverrides(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOLPHIN_GAME_SETTINGS_HARDCORE_OVERRIDES, null)

    fun saveDolphinGameSettingsHardcoreOverrides(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DOLPHIN_GAME_SETTINGS_HARDCORE_OVERRIDES, value) }
    }

    fun clearDolphinGameSettingsHardcoreOverrides(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_DOLPHIN_GAME_SETTINGS_HARDCORE_OVERRIDES) }
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

    fun loadSmartCachingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLE_SMART_CACHING, true)

    fun loadManualEmulatorPatchingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MANUAL_EMULATOR_PATCHING, false)

    fun loadShizukuManualPatchingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHIZUKU_MANUAL_PATCHING_ENABLED, false)

    fun saveSmartCachingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLE_SMART_CACHING, enabled) }
    }

    fun saveManualEmulatorPatchingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_MANUAL_EMULATOR_PATCHING, enabled) }
    }

    fun saveShizukuManualPatchingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SHIZUKU_MANUAL_PATCHING_ENABLED, enabled) }
    }

    fun clearPermissions(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_SAF_TREE_URI)
                remove(KEY_RETROARCH_SAF_TREE_URI)
                remove(KEY_RETROARCH_SMART_CACHE_SAF_TREE_URI)
                remove(KEY_DOLPHIN_SAF_TREE_URI)
                remove(KEY_PPSSPP_SAF_TREE_URI)
                remove(KEY_PPSSPP_ROOT_MODE)
                remove(KEY_SMART_CACHE_ROM_SAF_TREE_URI)
                remove(KEY_SMART_CACHE_ROM_SAF_TREE_URIS)
                remove(KEY_MANUAL_EMULATOR_PATCHING)
                remove(KEY_SHIZUKU_MANUAL_PATCHING_ENABLED)
            }
    }

    fun saveProxyPort(context: Context, port: Int) {
        require(isValidProxyPort(port)) { "Invalid proxy port: $port" }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_PROXY_PORT, port) }
    }

    fun loadAppUpdateCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_APP_UPDATE_CHECK_ENABLED, true)

    fun saveAppUpdateCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_APP_UPDATE_CHECK_ENABLED, enabled) }
    }

    fun saveAppUpdateLastCheckedAt(context: Context, checkedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_APP_UPDATE_LAST_CHECKED_AT, checkedAt) }
    }

    fun clearAppUpdateLastCheckedAt(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_APP_UPDATE_LAST_CHECKED_AT) }
    }

    fun loadAppUpdateLastPromptedAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_APP_UPDATE_LAST_PROMPTED_AT, 0L)

    fun saveAppUpdateLastPromptedAt(context: Context, promptedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_APP_UPDATE_LAST_PROMPTED_AT, promptedAt) }
    }

    fun saveAvailableAppUpdate(context: Context, update: AppUpdateInfo) {
        val payload = JSONObject()
            .put("versionName", update.versionName)
            .put("apkUrl", update.apkUrl)
            .put("releaseUrl", update.releaseUrl)
            .toString()

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_AVAILABLE_APP_UPDATE, payload) }
    }

    fun clearAvailableAppUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_AVAILABLE_APP_UPDATE) }
    }

    fun loadHideSupportButtonEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_SUPPORT_BUTTON, false)

    fun saveHideSupportButtonEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_SUPPORT_BUTTON, enabled) }
    }

    fun resetHideSupportButtonOnAppUpdate(context: Context, currentVersionCode: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeenVersionCode = prefs.getLong(KEY_LAST_SEEN_VERSION_CODE, -1L)
        if (lastSeenVersionCode == currentVersionCode) return

        prefs.edit {
            remove(KEY_HIDE_SUPPORT_BUTTON)
            putLong(KEY_LAST_SEEN_VERSION_CODE, currentVersionCode)
        }
    }

    fun isValidProxyPort(port: Int): Boolean = port in MIN_PROXY_PORT..MAX_PROXY_PORT
}
