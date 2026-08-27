package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.hash.RomHashInput
import com.raofflineproxy.proxy.hash.hashRomCandidates
import com.raofflineproxy.proxy.hash.hashZipRomCandidates
import com.raofflineproxy.ui.DOLPHIN_PACKAGE_CANDIDATES
import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.RETROARCH_PACKAGE_CANDIDATES
import com.raofflineproxy.ui.UI_PPSSPP_PACKAGE_CANDIDATES
import com.raofflineproxy.applyScanBatchCooldown
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import androidx.core.net.toUri

private const val TAG = "RAProxy/SmartCache"
private const val DOLPHIN_RECENT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000
private const val RETROARCH_RECENT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000
private const val WATERMELONDS_RECENT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000
private const val ARMSX_RECENT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

private val WATERMELONDS_ROM_LIBRARY_AUTHORITIES by lazy {
    Emulator.WatermelonDs.packageCandidates.map { packageName -> "$packageName.romlibrary" }
}
private val ARMSX1_ROM_LIBRARY_AUTHORITIES by lazy {
    Emulator.Armsx1.packageCandidates.map { packageName -> "$packageName.romlibrary" }
}
private val ARMSX2_ROM_LIBRARY_AUTHORITIES by lazy {
    Emulator.Armsx2.packageCandidates.map { packageName -> "$packageName.romlibrary" }
}
private val SMART_CACHE_EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }

private val RETROARCH_PACKAGE_HISTORY_PATHS = listOf(
    listOf("content_history.lpl"),
    listOf("files", "content_history.lpl")
)

private val RETROARCH_PACKAGE_HISTORY_SOURCE_CANDIDATES by lazy {
    RETROARCH_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/files/content_history.lpl",
            "/storage/emulated/0/Android/data/$packageName/files/content_history.lpl"
        )
    }
}

private val RETROARCH_SHARED_HISTORY_SOURCE_CANDIDATES by lazy {
    listOf(
        "$SMART_CACHE_EXT_STORAGE/RetroArch/playlists/builtin/content_history.lpl",
        "/storage/emulated/0/RetroArch/playlists/builtin/content_history.lpl"
    )
}

private val RETROARCH_SHARED_HISTORY_PATHS = listOf(
    listOf("playlists", "builtin", "content_history.lpl")
)

private val RETROARCH_SHARED_LOGS_PATHS = listOf(
    listOf("playlists", "logs")
)

private val RETROARCH_SHARED_LOGS_SOURCE_CANDIDATES by lazy {
    listOf(
        "$SMART_CACHE_EXT_STORAGE/RetroArch/playlists/logs",
        "/storage/emulated/0/RetroArch/playlists/logs"
    )
}

private val DOLPHIN_GAMELIST_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "cache", "gamelist.cache")
} + listOf(
    listOf("cache", "gamelist.cache"),
    listOf("gamelist.cache")
)

private val DOLPHIN_GAMELIST_SOURCE_CANDIDATES by lazy {
    DOLPHIN_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/cache/gamelist.cache",
            "/storage/emulated/0/Android/data/$packageName/cache/gamelist.cache"
        )
    } + listOf(
        "$SMART_CACHE_EXT_STORAGE/dolphin-emu/cache/gamelist.cache",
        "/storage/emulated/0/dolphin-emu/cache/gamelist.cache"
    )
}

private val DOLPHIN_GC_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "files", "GC")
} + listOf(
    listOf("files", "GC"),
    listOf("GC")
)

private val DOLPHIN_GC_SOURCE_CANDIDATES by lazy {
    DOLPHIN_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/files/GC",
            "/storage/emulated/0/Android/data/$packageName/files/GC"
        )
    } + listOf(
        "$SMART_CACHE_EXT_STORAGE/dolphin-emu/GC",
        "/storage/emulated/0/dolphin-emu/GC"
    )
}

private val DOLPHIN_WII_DISC_TITLE_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "files", "Wii", "title", "00010000")
} + listOf(
    listOf("files", "Wii", "title", "00010000"),
    listOf("Wii", "title", "00010000"),
    listOf("title", "00010000"),
    listOf("00010000")
)

private val DOLPHIN_WII_DISC_TITLE_SOURCE_CANDIDATES by lazy {
    DOLPHIN_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/files/Wii/title/00010000",
            "/storage/emulated/0/Android/data/$packageName/files/Wii/title/00010000"
        )
    } + listOf(
        "$SMART_CACHE_EXT_STORAGE/dolphin-emu/Wii/title/00010000",
        "/storage/emulated/0/dolphin-emu/Wii/title/00010000"
    )
}

private val PPSSPP_RECENTS_PATHS =
    UI_PPSSPP_PACKAGE_CANDIDATES.map { listOf(it, "files", "SYSTEM", "ppsspp.ini") } + listOf(
        listOf("files", "SYSTEM", "ppsspp.ini"),
        listOf("SYSTEM", "ppsspp.ini")
    )

private const val MAX_SMART_CACHE_FILES = 75
private const val SMART_CACHE_EMULATOR_BUDGET = 25
private val DOLPHIN_GAME_CODE_REGEX = Regex("(?<![A-Z0-9])[A-Z0-9]{6}(?![A-Z0-9])")
private val DOLPHIN_GCI_CODE_REGEX = Regex("^\\d{2}-([A-Z0-9]{4})-.*\\.gci$", RegexOption.IGNORE_CASE)
private val DOLPHIN_WII_TITLE_ID_REGEX = Regex("^[0-9A-Fa-f]{8}$")
private val DOLPHIN_ROM_LOCATOR_REGEX = Regex("(?:(?:content|file)://|/storage/)[^\\u0000]+")
private val DOLPHIN_ROM_SUFFIXES = listOf(
    ".rvz",
    ".gcm",
    ".iso",
    ".wad"
)

internal enum class SmartCacheEmulator {
    RetroArch,
    Dolphin,
    Ppsspp,
    WatermelonDs,
    Armsx1,
    Armsx2
}

internal data class SmartCacheCandidate(
    val emulator: SmartCacheEmulator,
    val sourceLabel: String,
    val path: String,
    val title: String? = null,
    val priority: Int = 0,
    val lastModifiedAt: Long? = null,
    val precomputedHash: String? = null
)

internal data class SmartCacheStrategyResult(
    val candidates: List<SmartCacheCandidate> = emptyList(),
    val message: String? = null,
    val needsSafGrant: Boolean = false
)

internal data class SmartCacheRunResult(
    val matched: Int,
    val total: Int,
    val skipped: Int,
    val limitReached: Boolean,
    val needsSafGrant: Boolean = false,
    val message: String? = null,
    val requiredRomGrantPaths: List<String> = emptyList(),
    val requiredSafGrantTargets: List<SmartCacheEmulator> = emptyList()
)

private data class ResolvedSmartCacheCandidate(
    val candidate: SmartCacheCandidate,
    val directFile: File? = null,
    val documentFile: DocumentFile? = null
)

private data class SmartCachePreflightResult(
    val resolved: List<ResolvedSmartCacheCandidate>,
    val unreadableCount: Int,
    val requiredRomGrantPaths: List<String>
)

private data class DolphinGameListEntry(
    val gameCode: String,
    val romLocator: String,
    val title: String?
)

private interface SmartCacheStrategy {
    val emulator: SmartCacheEmulator

    fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean

    fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult
}

private object PpssppSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.Ppsspp

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isEnabled(Emulator.Ppsspp)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult {
        if (treeUri == null) {
            Log.i(TAG, "PPSSPP strategy needs SAF grant for recent games")
            return SmartCacheStrategyResult(message = "needs_ppsspp_saf_grant", needsSafGrant = true)
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                Log.w(TAG, "PPSSPP strategy could not open treeUri=$treeUri")
                return SmartCacheStrategyResult(message = "needs_ppsspp_saf_grant", needsSafGrant = true)
            }
        val iniFile = findDocument(tree, PPSSPP_RECENTS_PATHS)
            ?: run {
                Log.i(TAG, "PPSSPP strategy did not find SYSTEM/ppsspp.ini in granted tree")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }
        val content = context.contentResolver.openInputStream(iniFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: run {
                Log.w(TAG, "PPSSPP strategy could not read ppsspp.ini uri=${iniFile.uri}")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }
        val candidates = parsePpssppRecentCandidates(content)
        Log.i(TAG, "PPSSPP strategy discovered ${candidates.size} recent candidates from ${iniFile.uri}")
        return if (candidates.isEmpty()) {
            SmartCacheStrategyResult(message = "no_recent_games")
        } else {
            SmartCacheStrategyResult(candidates = candidates)
        }
    }
}

private object RetroArchSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.RetroArch

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isEnabled(Emulator.RetroArch)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult {
        val directSharedHistory = firstReadableFile(RETROARCH_SHARED_HISTORY_SOURCE_CANDIDATES)
        val allFilesSharedHistory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            firstExistingFile(RETROARCH_SHARED_HISTORY_SOURCE_CANDIDATES)
        } else {
            null
        }
        val directPackageHistory = firstReadableFile(RETROARCH_PACKAGE_HISTORY_SOURCE_CANDIDATES)
        val packageHistoryFromSaf = treeUri?.let { currentTreeUri ->
            readHistoryCandidatesFromTree(context, currentTreeUri, RETROARCH_PACKAGE_HISTORY_PATHS)
        }
        val sharedHistoryFromSaf = PrefsConstants.loadRetroArchSmartCacheSafUri(context)?.let { currentTreeUri ->
            readHistoryCandidatesFromTree(context, currentTreeUri, RETROARCH_SHARED_HISTORY_PATHS)
        }

        val historyResult = when {
            directSharedHistory != null -> readHistoryCandidates(directSharedHistory, "direct shared history")
                ?.let { candidates -> directSharedHistory.path to candidates }
            allFilesSharedHistory != null -> readHistoryCandidates(allFilesSharedHistory, "all-files shared history")
                ?.let { candidates -> allFilesSharedHistory.path to candidates }
            directPackageHistory != null -> readHistoryCandidates(directPackageHistory, "direct package history")
                ?.let { candidates -> directPackageHistory.path to candidates }
            packageHistoryFromSaf != null -> packageHistoryFromSaf
            sharedHistoryFromSaf != null -> sharedHistoryFromSaf
            else -> null
        }

        if (historyResult == null) {
            val sharedHistoryFile = firstExistingFile(RETROARCH_SHARED_HISTORY_SOURCE_CANDIDATES)
            val packageHistoryFile = firstExistingFile(RETROARCH_PACKAGE_HISTORY_SOURCE_CANDIDATES)
            return when {
                sharedHistoryFile != null -> {
                    Log.i(TAG, "RetroArch strategy needs shared RetroArch history access history=${sharedHistoryFile.path}")
                    SmartCacheStrategyResult(message = "needs_retroarch_shared_access", needsSafGrant = true)
                }
                packageHistoryFile != null && treeUri == null -> {
                    Log.i(TAG, "RetroArch strategy needs SAF grant for history file")
                    SmartCacheStrategyResult(needsSafGrant = true)
                }
                else -> {
                    Log.i(TAG, "RetroArch strategy did not find content_history.lpl")
                    SmartCacheStrategyResult(message = "history_missing")
                }
            }
        }

        val (historySource, historyCandidates) = historyResult
        val directSharedLogs = firstReadableDirectory(RETROARCH_SHARED_LOGS_SOURCE_CANDIDATES)
        val allFilesSharedLogs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            firstExistingDirectory(RETROARCH_SHARED_LOGS_SOURCE_CANDIDATES)
        } else {
            null
        }
        val sharedLogsFromSaf = PrefsConstants.loadRetroArchSmartCacheSafUri(context)?.let { currentTreeUri ->
            loadRecentRetroArchLogsFromTree(context, currentTreeUri)
        }

        val recentLogs = when {
            directSharedLogs != null -> loadRecentRetroArchLogs(directSharedLogs)
            allFilesSharedLogs != null -> loadRecentRetroArchLogs(allFilesSharedLogs)
            sharedLogsFromSaf != null -> sharedLogsFromSaf
            else -> null
        }

        if (recentLogs == null) {
            val sharedLogsDirectory = firstExistingDirectory(RETROARCH_SHARED_LOGS_SOURCE_CANDIDATES)
            return if (sharedLogsDirectory != null) {
                Log.i(TAG, "RetroArch strategy needs shared RetroArch log access history=$historySource logs=${sharedLogsDirectory.path}")
                SmartCacheStrategyResult(message = "needs_retroarch_shared_access", needsSafGrant = true)
            } else {
                Log.i(TAG, "RetroArch strategy found no RetroArch runtime logs for source=$historySource")
                SmartCacheStrategyResult(message = "no_recent_games")
            }
        }

        return buildRetroArchStrategyResult(
            candidates = historyCandidates,
            recentLogs = recentLogs,
            source = historySource
        )
    }
}

private fun readHistoryCandidates(file: File?, label: String): List<SmartCacheCandidate>? {
    if (file == null) {
        return null
    }
    val content = runCatching { file.readText() }
        .onFailure { error -> Log.w(TAG, "RetroArch strategy could not read $label path=${file.path}", error) }
        .getOrNull()
        ?: return null
    val candidates = parseRetroArchHistory(content)
    Log.i(TAG, "RetroArch strategy discovered ${candidates.size} history candidates from ${file.path}")
    return candidates
}

private fun readHistoryCandidatesFromTree(
    context: Context,
    treeUri: Uri,
    historyPaths: List<List<String>>
): Pair<String, List<SmartCacheCandidate>>? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val historyFile = findDocument(tree, historyPaths) ?: return null
    val content = context.contentResolver.openInputStream(historyFile.uri)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: return null
    val historyCandidates = parseRetroArchHistory(content)
    Log.i(TAG, "RetroArch strategy discovered ${historyCandidates.size} history candidates from ${historyFile.uri}")
    return historyFile.uri.toString() to historyCandidates
}

private fun buildRetroArchStrategyResult(
    candidates: List<SmartCacheCandidate>,
    recentLogs: Map<String, Long>,
    source: String
): SmartCacheStrategyResult {
    if (candidates.isEmpty()) {
        return SmartCacheStrategyResult(message = "history_empty")
    }

    val cutoff = System.currentTimeMillis() - RETROARCH_RECENT_WINDOW_MS
    val recentLogBasenames = recentLogs.filterValues { modifiedAt -> modifiedAt >= cutoff }
    Log.i(
        TAG,
        "RetroArch strategy recent-window filter source=$source totalLogs=${recentLogs.size} cutoff=$cutoff retainedLogs=${recentLogBasenames.size}"
    )

    if (recentLogBasenames.isEmpty()) {
        Log.i(TAG, "RetroArch strategy found no log files within the last ${RETROARCH_RECENT_WINDOW_MS / (24L * 60 * 60 * 1000)} days from $source")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }

    val filteredCandidates = candidates.filter { candidate ->
        val logBasename = retroArchRuntimeLogBasename(candidate.path)
        recentLogBasenames.containsKey(logBasename)
    }.map { candidate ->
        val logBasename = retroArchRuntimeLogBasename(candidate.path)
        candidate.copy(lastModifiedAt = recentLogs[logBasename])
    }
    Log.i(
        TAG,
        "RetroArch strategy filtered candidates by recent logs source=$source totalCandidates=${candidates.size} retainedCandidates=${filteredCandidates.size} candidatesWithoutLogs=${candidates.count { candidate -> !recentLogs.containsKey(retroArchRuntimeLogBasename(candidate.path)) }}"
    )
    return if (filteredCandidates.isEmpty()) {
        SmartCacheStrategyResult(message = "no_recent_games")
    } else {
        SmartCacheStrategyResult(candidates = filteredCandidates)
    }
}

private object DolphinSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.Dolphin

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isEnabled(Emulator.Dolphin)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult {
        val directGamelistFile = firstReadableFile(DOLPHIN_GAMELIST_SOURCE_CANDIDATES)
        if (directGamelistFile != null) {
            val directGamelistBytes = runCatching { directGamelistFile.readBytes() }
                .onFailure { error -> Log.w(TAG, "Dolphin strategy could not read direct gamelist path=${directGamelistFile.path}", error) }
                .getOrNull()
            if (directGamelistBytes != null) {
                val recentSaveCodes = linkedMapOf<String, Long>().apply {
                    firstReadableDirectory(DOLPHIN_GC_SOURCE_CANDIDATES)?.let { putAll(loadRecentGameCubeSaveCodes(it)) }
                    firstReadableDirectory(DOLPHIN_WII_DISC_TITLE_SOURCE_CANDIDATES)?.let { mergeRecentSaveCodes(loadRecentWiiDiscSaveCodes(it)) }
                }
                val directResult = buildDolphinStrategyResult(directGamelistBytes, recentSaveCodes, directGamelistFile.path)
                if (directResult.candidates.isNotEmpty() || treeUri == null) {
                    return directResult
                }
                Log.i(TAG, "Dolphin strategy direct discovery produced no candidates, falling back to SAF")
            }
        }

        if (treeUri == null) {
            Log.i(TAG, "Dolphin strategy needs SAF grant for package data")
            return SmartCacheStrategyResult(
                message = "needs_dolphin_saf_grant",
                needsSafGrant = true
            )
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                Log.w(TAG, "Dolphin strategy could not open treeUri=$treeUri")
                return SmartCacheStrategyResult(
                    message = "needs_dolphin_saf_grant",
                    needsSafGrant = true
                )
            }
        val gamelistFile = findDocument(tree, DOLPHIN_GAMELIST_PATHS)
            ?: run {
                Log.i(TAG, "Dolphin strategy did not find gamelist.cache in granted tree")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }
        val gcRoot = findDolphinGcDirectory(tree)
        val wiiTitleRoot = findDolphinWiiDiscTitleDirectory(tree)

        val gamelistBytes = context.contentResolver.openInputStream(gamelistFile.uri)
            ?.use { it.readBytes() }
            ?: run {
                Log.w(TAG, "Dolphin strategy could not read gamelist uri=${gamelistFile.uri}")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }
        val recentSaveCodes = linkedMapOf<String, Long>().apply {
            gcRoot?.let { putAll(loadRecentGameCubeSaveCodes(it)) }
            wiiTitleRoot?.let { mergeRecentSaveCodes(loadRecentWiiDiscSaveCodes(it)) }
        }
        return buildDolphinStrategyResult(gamelistBytes, recentSaveCodes, gamelistFile.uri.toString())
    }
}

private object WatermelonDsSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.WatermelonDs

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isInstalled(Emulator.WatermelonDs) &&
            emulatorSupport.isEnabled(Emulator.WatermelonDs)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult {
        val cutoff = System.currentTimeMillis() - WATERMELONDS_RECENT_WINDOW_MS
        val candidates = WATERMELONDS_ROM_LIBRARY_AUTHORITIES.firstNotNullOfOrNull { authority ->
            queryWatermelonDsRomLibrary(context, authority, cutoff)
        }

        if (candidates == null) {
            Log.i(TAG, "WatermelonDS strategy did not find a readable rom library provider")
            return SmartCacheStrategyResult(message = "no_recent_games")
        }
        if (candidates.isEmpty()) {
            Log.i(TAG, "WatermelonDS strategy found no recently played games within the last ${WATERMELONDS_RECENT_WINDOW_MS / (24L * 60 * 60 * 1000)} days")
            return SmartCacheStrategyResult(message = "no_recent_games")
        }

        Log.i(TAG, "WatermelonDS strategy discovered ${candidates.size} recently played candidates")
        return SmartCacheStrategyResult(candidates = candidates)
    }
}

private fun queryWatermelonDsRomLibrary(context: Context, authority: String, cutoff: Long): List<SmartCacheCandidate>? {
    val uri = Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath("roms")
        .build()

    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val uriIndex = cursor.getColumnIndex("uri")
            val lastPlayedIndex = cursor.getColumnIndex("lastPlayed")
            val hashIndex = cursor.getColumnIndex("retroAchievementsHash")

            buildList {
                while (cursor.moveToNext()) {
                    val lastPlayed = if (lastPlayedIndex >= 0 && !cursor.isNull(lastPlayedIndex)) {
                        cursor.getLong(lastPlayedIndex)
                    } else {
                        null
                    }
                    if (lastPlayed == null || lastPlayed < cutoff) {
                        continue
                    }

                    val hash = hashIndex.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    if (hash.isNullOrBlank()) {
                        continue
                    }

                    val romUri = uriIndex.takeIf { it >= 0 }?.let { cursor.getString(it) } ?: continue
                    val name = nameIndex.takeIf { it >= 0 }?.let { cursor.getString(it) }

                    add(
                        SmartCacheCandidate(
                            emulator = SmartCacheEmulator.WatermelonDs,
                            sourceLabel = authority,
                            path = romUri,
                            title = name,
                            lastModifiedAt = lastPlayed,
                            precomputedHash = hash
                        )
                    )
                }
            }
        }
    }.onFailure { error ->
        Log.i(TAG, "WatermelonDS strategy could not query authority=$authority", error)
    }.getOrNull()
}

private object Armsx1SmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.Armsx1

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isInstalled(Emulator.Armsx1) &&
            emulatorSupport.isEnabled(Emulator.Armsx1)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult =
        discoverArmsxCandidates(context, SmartCacheEmulator.Armsx1, ARMSX1_ROM_LIBRARY_AUTHORITIES)
}

private object Armsx2SmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.Armsx2

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.isInstalled(Emulator.Armsx2) &&
            emulatorSupport.isEnabled(Emulator.Armsx2)

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult =
        discoverArmsxCandidates(context, SmartCacheEmulator.Armsx2, ARMSX2_ROM_LIBRARY_AUTHORITIES)
}

private fun discoverArmsxCandidates(context: Context, emulator: SmartCacheEmulator, authorities: List<String>): SmartCacheStrategyResult {
    val cutoff = System.currentTimeMillis() - ARMSX_RECENT_WINDOW_MS
    val candidates = authorities.firstNotNullOfOrNull { authority ->
        queryArmsxRomLibrary(context, authority, emulator, cutoff)
    }

    if (candidates == null) {
        Log.i(TAG, "$emulator strategy did not find a readable rom library provider")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }
    if (candidates.isEmpty()) {
        Log.i(TAG, "$emulator strategy found no recently played games within the last ${ARMSX_RECENT_WINDOW_MS / (24L * 60 * 60 * 1000)} days")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }

    Log.i(TAG, "$emulator strategy discovered ${candidates.size} recently played candidates")
    return SmartCacheStrategyResult(candidates = candidates)
}

private fun queryArmsxRomLibrary(context: Context, authority: String, emulator: SmartCacheEmulator, cutoff: Long): List<SmartCacheCandidate>? {
    val uri = Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath("games")
        .build()

    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val titleIndex = cursor.getColumnIndex("title")
            val uriIndex = cursor.getColumnIndex("uri")
            val lastPlayedIndex = cursor.getColumnIndex("lastPlayed")

            buildList {
                while (cursor.moveToNext()) {
                    val lastPlayed = if (lastPlayedIndex >= 0 && !cursor.isNull(lastPlayedIndex)) {
                        cursor.getLong(lastPlayedIndex)
                    } else {
                        null
                    }
                    if (lastPlayed == null || lastPlayed < cutoff) {
                        continue
                    }

                    val romUri = uriIndex.takeIf { it >= 0 }?.let { cursor.getString(it) } ?: continue
                    val title = titleIndex.takeIf { it >= 0 }?.let { cursor.getString(it) }

                    add(
                        SmartCacheCandidate(
                            emulator = emulator,
                            sourceLabel = authority,
                            path = romUri,
                            title = title,
                            lastModifiedAt = lastPlayed
                        )
                    )
                }
            }
        }
    }.onFailure { error ->
        Log.i(TAG, "$emulator strategy could not query authority=$authority", error)
    }.getOrNull()
}

internal suspend fun runSmartCache(
    context: Context,
    credentials: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    emulatorSupport: EmulatorSupport,
    retroArchTreeUri: Uri?,
    dolphinTreeUri: Uri?,
    ppssppTreeUri: Uri?,
    romTreeUris: List<Uri>,
    onProgress: (current: Int, total: Int, label: String) -> Unit
): SmartCacheRunResult {
    Log.i(
        TAG,
        "runSmartCache start retroArchTreeUri=$retroArchTreeUri dolphinTreeUri=$dolphinTreeUri romTreeUris=${romTreeUris.size} enabledEmulators=${emulatorSupport.enabled}"
    )
    val cachedGameIds = loadCachedGameIds(db)
    val cachedRomPaths = loadCachedRomPaths(db)
    val remainingSlots = MAX_CACHED_GAMES - cachedGameIds.size
    if (remainingSlots <= 0) {
        Log.i(TAG, "runSmartCache aborted because cache is already full")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = true
        )
    }

    val activeStrategies = listOf(RetroArchSmartCacheStrategy, DolphinSmartCacheStrategy, PpssppSmartCacheStrategy, WatermelonDsSmartCacheStrategy, Armsx1SmartCacheStrategy, Armsx2SmartCacheStrategy)
        .filter { strategy -> strategy.isEnabled(context, emulatorSupport) }
    if (activeStrategies.isEmpty()) {
        Log.i(TAG, "runSmartCache found no active strategies")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            message = "no_strategies"
        )
    }

    val requiredSafGrantTargets = mutableSetOf<SmartCacheEmulator>()

    val discoveredCandidates = linkedMapOf<String, SmartCacheCandidate>()
    var needsSafGrant = false
    var strategyMessage: String? = null
    activeStrategies.forEach { strategy ->
        val treeUri = when (strategy.emulator) {
            SmartCacheEmulator.RetroArch -> retroArchTreeUri
            SmartCacheEmulator.Dolphin -> dolphinTreeUri
            SmartCacheEmulator.Ppsspp -> ppssppTreeUri
            SmartCacheEmulator.WatermelonDs -> null
            SmartCacheEmulator.Armsx1 -> null
            SmartCacheEmulator.Armsx2 -> null
        }
        val result = strategy.discoverCandidates(context, treeUri)
        Log.i(
            TAG,
            "Strategy ${strategy.emulator} produced candidates=${result.candidates.size} needsSafGrant=${result.needsSafGrant} message=${result.message}"
        )
        if (result.needsSafGrant) {
            needsSafGrant = true
            requiredSafGrantTargets += strategy.emulator
        }
        if (strategyMessage == null && !result.message.isNullOrBlank()) {
            strategyMessage = result.message
        }
        result.candidates.forEach { candidate ->
            discoveredCandidates.putIfAbsent(candidate.path, candidate)
        }
    }

    if (needsSafGrant) {
        Log.i(
            TAG,
            "runSmartCache stopping before caching because strategy access is still required targets=$requiredSafGrantTargets message=$strategyMessage"
        )
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            needsSafGrant = true,
            message = strategyMessage ?: when {
                SmartCacheEmulator.RetroArch in requiredSafGrantTargets -> "needs_saf_grant"
                SmartCacheEmulator.Dolphin in requiredSafGrantTargets -> "needs_dolphin_saf_grant"
                else -> "needs_ppsspp_saf_grant"
            },
            requiredSafGrantTargets = requiredSafGrantTargets.toList()
        )
    }

    if (discoveredCandidates.isEmpty()) {
        Log.i(TAG, "runSmartCache found no discovered candidates strategyMessage=$strategyMessage")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            needsSafGrant = false,
            message = strategyMessage ?: "no_recent_games",
            requiredSafGrantTargets = requiredSafGrantTargets.toList()
        )
    }

    discoveredCandidates.values.forEach { candidate ->
        Log.i(TAG, "${candidate.emulator} candidate discovered title=${candidate.title} path=${candidate.path} source=${candidate.sourceLabel}")
    }

    val candidates = discoveredCandidates.values.toList()
        .filterNot { candidate ->
            val normalizedPath = candidate.path.normalizeCachedRomPath()
            val alreadyCached = normalizedPath in cachedRomPaths
            if (alreadyCached) {
                Log.i(TAG, "${candidate.emulator} candidate dropped title=${candidate.title} reason=already-cached-path path=${candidate.path}")
            }
            alreadyCached
        }
    val preflight = resolveSmartCacheCandidates(context, candidates, romTreeUris)
    Log.i(
        TAG,
        "runSmartCache preflight resolved=${preflight.resolved.size} unreadable=${preflight.unreadableCount} requiredRomGrantPaths=${preflight.requiredRomGrantPaths}"
    )
    if (preflight.requiredRomGrantPaths.isNotEmpty()) {
        Log.i(TAG, "runSmartCache requesting ROM tree grants paths=${preflight.requiredRomGrantPaths}")
        return SmartCacheRunResult(
            matched = 0,
            total = candidates.size,
            skipped = 0,
            limitReached = false,
            needsSafGrant = true,
            message = "needs_rom_saf_grant",
            requiredRomGrantPaths = preflight.requiredRomGrantPaths,
            requiredSafGrantTargets = requiredSafGrantTargets.toList()
        )
    }
    if (preflight.resolved.isEmpty()) {
        Log.i(TAG, "runSmartCache found no cacheable candidates after preflight")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            message = "no_readable_candidates",
            requiredRomGrantPaths = emptyList()
        )
    }

    val candidateCap = minOf(remainingSlots, MAX_SMART_CACHE_FILES)
    val resolvedCandidates = selectSmartCacheCandidates(preflight.resolved, candidateCap)
    val droppedByCap = preflight.resolved.filterNot { it in resolvedCandidates }
    resolvedCandidates.forEach { resolvedCandidate ->
        Log.i(
            TAG,
            "${resolvedCandidate.candidate.emulator} candidate selected title=${resolvedCandidate.candidate.title} path=${resolvedCandidate.candidate.path} lastModifiedAt=${resolvedCandidate.candidate.lastModifiedAt} lastModifiedText=${resolvedCandidate.candidate.lastModifiedAt?.let(::formatSmartCacheTimestamp)}"
        )
    }
    droppedByCap.forEach { resolvedCandidate ->
        Log.i(
            TAG,
            "${resolvedCandidate.candidate.emulator} candidate dropped title=${resolvedCandidate.candidate.title} reason=candidate-cap path=${resolvedCandidate.candidate.path}"
        )
    }
    Log.i(
        TAG,
        "runSmartCache readable cap=$candidateCap resolved=${preflight.resolved.size} capped=${resolvedCandidates.size} remainingSlots=$remainingSlots retroArchSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.RetroArch }} dolphinSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.Dolphin }} ppssppSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.Ppsspp }} watermelonDsSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.WatermelonDs }} armsx1Selected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.Armsx1 }} armsx2Selected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.Armsx2 }}"
    )

    val relevantTotal = resolvedCandidates.size
    val queueResult = executeResolvedSmartCacheCandidates(
        context = context,
        credentials = credentials,
        userAgent = userAgent,
        db = db,
        candidates = resolvedCandidates,
        onProgress = onProgress
    )

    if (queueResult.matched == 0 && queueResult.skipped == relevantTotal && !queueResult.limitReached) {
        return SmartCacheRunResult(
            matched = 0,
            total = relevantTotal,
            skipped = queueResult.skipped,
            limitReached = false,
            needsSafGrant = false,
            message = strategyMessage ?: "no_ra_matches",
            requiredSafGrantTargets = requiredSafGrantTargets.toList()
        )
    }

    return SmartCacheRunResult(
        matched = queueResult.matched,
        total = relevantTotal,
        skipped = queueResult.skipped,
        limitReached = queueResult.limitReached,
        needsSafGrant = false,
        message = null,
        requiredSafGrantTargets = requiredSafGrantTargets.toList()
    ).also {
        Log.i(
            TAG,
            "runSmartCache complete matched=${it.matched} total=${it.total} skipped=${it.skipped} limitReached=${it.limitReached} needsSafGrant=${it.needsSafGrant} message=${it.message}"
        )
    }
}

private fun findDocument(root: DocumentFile, pathVariants: List<List<String>>): DocumentFile? =
    pathVariants.firstNotNullOfOrNull { segments ->
        segments.fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isFile }
    }

private fun findDolphinGcDirectory(root: DocumentFile): DocumentFile? =
    DOLPHIN_GC_PATHS.firstNotNullOfOrNull { segments ->
        segments.fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isDirectory }
    }

private fun findDolphinWiiDiscTitleDirectory(root: DocumentFile): DocumentFile? =
    DOLPHIN_WII_DISC_TITLE_PATHS.firstNotNullOfOrNull { segments ->
        segments.fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isDirectory }
    }

private fun parseRetroArchHistory(content: String): List<SmartCacheCandidate> {
    val seenPaths = linkedSetOf<String>()
    val items = parsePlaylistItems(content)
    return items.mapIndexedNotNull { index, item ->
        val path = item.optString("path").trim()
        if (path.isBlank() || !seenPaths.add(path)) {
            return@mapIndexedNotNull null
        }
        SmartCacheCandidate(
            emulator = SmartCacheEmulator.RetroArch,
            sourceLabel = "content_history.lpl",
            path = path,
            title = item.optString("label").takeIf { it.isNotBlank() },
            priority = index
        )
    }
}

internal fun parsePpssppRecentCandidates(content: String): List<SmartCacheCandidate> {
    val lines = content.lines()
    var inRecent = false
    val seenPaths = linkedSetOf<String>()
    val candidates = mutableListOf<SmartCacheCandidate>()

    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            inRecent = trimmed == "[Recent]"
            return@forEach
        }
        if (!inRecent) return@forEach

        val separator = trimmed.indexOf('=')
        if (separator == -1) return@forEach
        val key = trimmed.substring(0, separator).trim()
        if (!key.startsWith("FileName")) return@forEach
        val index = key.removePrefix("FileName").toIntOrNull() ?: return@forEach
        val path = trimmed.substring(separator + 1).trim().takeIf { it.isNotBlank() } ?: return@forEach
        if (!seenPaths.add(path)) return@forEach

        candidates += SmartCacheCandidate(
            emulator = SmartCacheEmulator.Ppsspp,
            sourceLabel = "PPSSPP Recent",
            path = path,
            title = deriveSmartCacheTitle(path),
            priority = index
        )
    }

    return candidates.sortedBy { it.priority }
}

private fun parsePlaylistItems(content: String): List<JSONObject> {
    val root = runCatching { JSONObject(content) }.getOrNull()
    val items = root?.optJSONArray("items")
    if (items != null) {
        return jsonArrayItems(items)
    }

    val array = runCatching { JSONArray(content) }.getOrNull()
    return if (array != null) jsonArrayItems(array) else emptyList()
}

private fun jsonArrayItems(array: JSONArray): List<JSONObject> = buildList {
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let(::add)
    }
}

private fun parseDolphinGameListEntries(content: ByteArray): List<DolphinGameListEntry> {
    val text = content.toString(StandardCharsets.ISO_8859_1)
    val locatorMatches = DOLPHIN_ROM_LOCATOR_REGEX.findAll(text).toList()
    if (locatorMatches.isEmpty()) {
        return emptyList()
    }

    return buildList {
        locatorMatches.forEachIndexed { index, locatorMatch ->
            val nextStart = locatorMatches.getOrNull(index + 1)?.range?.first ?: text.length
            val segment = text.substring(locatorMatch.range.last + 1, nextStart)
            val codeMatch = DOLPHIN_GAME_CODE_REGEX.find(segment) ?: return@forEachIndexed
            val romLocator = sanitizeDolphinRomLocator(locatorMatch.value) ?: return@forEachIndexed
            add(
                DolphinGameListEntry(
                    gameCode = codeMatch.value,
                    romLocator = romLocator,
                    title = deriveSmartCacheTitle(romLocator)
                )
            )
        }
    }.distinctBy { entry -> entry.romLocator }
}

private fun sanitizeDolphinRomLocator(locator: String): String? {
    val trimmed = locator.trim()
    val cutoff = DOLPHIN_ROM_SUFFIXES.firstNotNullOfOrNull { suffix ->
        trimmed.indexOf(suffix, ignoreCase = true)
            .takeIf { it >= 0 }
            ?.let { it + suffix.length }
    } ?: return trimmed.takeIf { it.isNotBlank() }
    return trimmed.substring(0, cutoff)
}

private fun deriveSmartCacheTitle(path: String): String? =
    decodePathSegment(path).substringAfterLast('/').takeIf { it.isNotBlank() }

private fun decodePathSegment(segment: String): String =
    runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }
        .getOrDefault(segment)

private fun retroArchRuntimeLogBasename(path: String): String =
    Uri.decode(path.substringAfterLast('/')).substringBeforeLast('.')

private fun firstExistingFile(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull(File::isFile)

private fun firstExistingDirectory(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull(File::isDirectory)

private fun firstReadableFile(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull { it.isFile && it.canRead() }

private fun firstReadableDirectory(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull { it.isDirectory && it.canRead() }

private fun findRetroArchSharedLogsDirectory(root: DocumentFile): DocumentFile? =
    RETROARCH_SHARED_LOGS_PATHS.firstNotNullOfOrNull { segments ->
        segments.fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isDirectory }
    }

private fun buildDolphinStrategyResult(
    gamelistBytes: ByteArray,
    recentSaveCodes: Map<String, Long>,
    source: String
): SmartCacheStrategyResult {
    val entriesByCode = parseDolphinGameListEntries(gamelistBytes)
        .groupBy { it.gameCode.take(4) }
    if (entriesByCode.isEmpty()) {
        Log.i(TAG, "Dolphin strategy parsed no game entries from $source")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }

    if (recentSaveCodes.isEmpty()) {
        Log.i(TAG, "Dolphin strategy found no GameCube or Wii disc savefiles from $source")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }

    val cutoff = System.currentTimeMillis() - DOLPHIN_RECENT_WINDOW_MS
    val recentWindowSaveCodes = recentSaveCodes.filterValues { modifiedAt -> modifiedAt >= cutoff }
    Log.i(
        TAG,
        "Dolphin strategy recent-window filter source=$source totalSaveCodes=${recentSaveCodes.size} cutoff=$cutoff retainedSaveCodes=${recentWindowSaveCodes.size}"
    )
    if (recentWindowSaveCodes.isEmpty()) {
        Log.i(TAG, "Dolphin strategy found no savefiles within the last 90 days from $source")
        return SmartCacheStrategyResult(message = "no_recent_games")
    }

    val candidates = recentWindowSaveCodes.entries
        .sortedByDescending { it.value }
        .mapNotNull { (code, _) ->
            entriesByCode[code]?.firstOrNull()?.let { entry ->
                SmartCacheCandidate(
                    emulator = SmartCacheEmulator.Dolphin,
                    sourceLabel = "Dolphin GC saves",
                    path = entry.romLocator,
                    title = entry.title,
                    priority = 0,
                    lastModifiedAt = recentWindowSaveCodes[code]
                )
            }
        }
        .distinctBy { it.path }

    Log.i(TAG, "Dolphin strategy discovered ${candidates.size} Dolphin candidates from $source")
    candidates.forEach { candidate ->
        Log.i(TAG, "Dolphin candidate discovered title=${candidate.title} path=${candidate.path}")
    }
    return if (candidates.isEmpty()) {
        SmartCacheStrategyResult(message = "no_recent_games")
    } else {
        SmartCacheStrategyResult(candidates = candidates)
    }
}

private fun loadRecentGameCubeSaveCodes(root: DocumentFile): Map<String, Long> {
    val recentByCode = linkedMapOf<String, Long>()
    collectDocumentFiles(root)
        .filter { document ->
            document.isFile && (document.name?.endsWith(".gci", ignoreCase = true) == true)
        }
        .forEach { document ->
            val name = document.name ?: return@forEach
            val code = DOLPHIN_GCI_CODE_REGEX.matchEntire(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase()
                ?: return@forEach
            val modifiedAt = document.lastModified()
            val previous = recentByCode[code] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByCode[code] = modifiedAt
            }
        }
    return recentByCode
}

private fun loadRecentGameCubeSaveCodes(root: File): Map<String, Long> {
    val recentByCode = linkedMapOf<String, Long>()
    collectFiles(root)
        .filter { file -> file.isFile && file.name.endsWith(".gci", ignoreCase = true) }
        .forEach { file ->
            val code = DOLPHIN_GCI_CODE_REGEX.matchEntire(file.name)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase()
                ?: return@forEach
            val modifiedAt = file.lastModified()
            val previous = recentByCode[code] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByCode[code] = modifiedAt
            }
        }
    return recentByCode
}

private fun loadRecentWiiDiscSaveCodes(root: DocumentFile): Map<String, Long> {
    val recentByCode = linkedMapOf<String, Long>()
    root.listFiles()
        .filter { document -> document.isDirectory }
        .forEach { document ->
            val titleIdSuffix = document.name?.takeIf { DOLPHIN_WII_TITLE_ID_REGEX.matches(it) } ?: return@forEach
            val code = decodeWiiDiscTitleIdToGameCode(titleIdSuffix) ?: return@forEach
            val modifiedAt = document.lastModified()
            val previous = recentByCode[code] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByCode[code] = modifiedAt
            }
        }
    return recentByCode
}

private fun loadRecentWiiDiscSaveCodes(root: File): Map<String, Long> {
    val recentByCode = linkedMapOf<String, Long>()
    root.listFiles()
        ?.filter { file -> file.isDirectory }
        ?.forEach { file ->
            val titleIdSuffix = file.name.takeIf { DOLPHIN_WII_TITLE_ID_REGEX.matches(it) } ?: return@forEach
            val code = decodeWiiDiscTitleIdToGameCode(titleIdSuffix) ?: return@forEach
            val modifiedAt = file.lastModified()
            val previous = recentByCode[code] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByCode[code] = modifiedAt
            }
        }
    return recentByCode
}

private fun decodeWiiDiscTitleIdToGameCode(titleIdSuffix: String): String? = runCatching {
    buildString(4) {
        titleIdSuffix.chunked(2).forEach { byteHex ->
            append(byteHex.toInt(16).toChar())
        }
    }
}.getOrNull()?.takeIf { code -> code.length == 4 && code.all { it.code in 0x20..0x7E } }?.uppercase()

private fun MutableMap<String, Long>.mergeRecentSaveCodes(other: Map<String, Long>) {
    other.forEach { (code, modifiedAt) ->
        val previous = this[code] ?: Long.MIN_VALUE
        if (modifiedAt > previous) {
            this[code] = modifiedAt
        }
    }
}

private fun collectDocumentFiles(root: DocumentFile): List<DocumentFile> = buildList {
    val stack = ArrayDeque<DocumentFile>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val current = stack.removeFirst()
        add(current)
        current.listFiles().forEach { child ->
            if (child.isDirectory) {
                stack.addLast(child)
            } else {
                add(child)
            }
        }
    }
}

private fun collectFiles(root: File): List<File> = buildList {
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val current = stack.removeFirst()
        add(current)
        current.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                stack.addLast(child)
            } else {
                add(child)
            }
        }
    }
}

private fun loadRecentRetroArchLogs(root: DocumentFile): Map<String, Long> {
    val recentByBasename = linkedMapOf<String, Long>()
    collectDocumentFiles(root)
        .filter { document -> document.isFile && (document.name?.endsWith(".lrtl", ignoreCase = true) == true) }
        .forEach { document ->
            val basename = document.name
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val modifiedAt = document.lastModified()
            val previous = recentByBasename[basename] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByBasename[basename] = modifiedAt
            }
        }
    return recentByBasename
}

private fun loadRecentRetroArchLogsFromTree(
    context: Context,
    treeUri: Uri
): Map<String, Long>? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val logsDirectory = findRetroArchSharedLogsDirectory(tree) ?: return null
    return loadRecentRetroArchLogs(logsDirectory)
}

private fun loadRecentRetroArchLogs(root: File): Map<String, Long> {
    val recentByBasename = linkedMapOf<String, Long>()
    collectFiles(root)
        .filter { file -> file.isFile && file.name.endsWith(".lrtl", ignoreCase = true) }
        .forEach { file ->
            val basename = file.name.substringBeforeLast('.').trim().takeIf { it.isNotBlank() } ?: return@forEach
            val modifiedAt = file.lastModified()
            val previous = recentByBasename[basename] ?: Long.MIN_VALUE
            if (modifiedAt > previous) {
                recentByBasename[basename] = modifiedAt
            }
        }
    return recentByBasename
}

private fun resolveSmartCacheCandidates(
    context: Context,
    candidates: List<SmartCacheCandidate>,
    romTreeUris: List<Uri>
): SmartCachePreflightResult {
    val resolved = mutableListOf<ResolvedSmartCacheCandidate>()
    var unreadableCount = 0
    val uncoveredUnreadablePaths = mutableListOf<String>()
    val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    val romTrees = romTreeUris.mapNotNull { uri ->
        DocumentFile.fromTreeUri(context, uri)?.let { tree -> uri to tree }
    }
    val grantedRomRoots = romTreeUris.map(::treeUriToAbsolutePath)

    candidates.forEach { candidate ->
        if (!candidate.precomputedHash.isNullOrBlank()) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate)
            Log.i(TAG, "${candidate.emulator} candidate resolved title=${candidate.title} via=precomputedHash path=${candidate.path}")
            return@forEach
        }

        val directDocument = resolveDocumentByStoredUri(context, candidate.path)
        if (directDocument != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = directDocument)
            Log.i(TAG, "${candidate.emulator} candidate resolved title=${candidate.title} via=storedUri path=${candidate.path}")
            return@forEach
        }

        val absolutePath = candidate.path.toAbsoluteStoragePath() ?: candidate.path
        val directFile = File(absolutePath)
        if (directFile.isFile && directFile.canRead()) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, directFile = directFile)
            Log.i(TAG, "${candidate.emulator} candidate resolved title=${candidate.title} via=directFile path=${candidate.path} absolutePath=$absolutePath")
            return@forEach
        }

        if (hasAllFilesAccess) {
            Log.i(TAG, "${candidate.emulator} candidate unreadable title=${candidate.title} via=allFiles path=${candidate.path} absolutePath=$absolutePath")
            unreadableCount++
            return@forEach
        }

        val document = romTrees.firstNotNullOfOrNull { (_, tree) ->
            resolveDocumentByAbsolutePath(context, tree, absolutePath)
        }
        if (document != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = document)
            Log.i(TAG, "${candidate.emulator} candidate resolved title=${candidate.title} via=romSaf path=${candidate.path} absolutePath=$absolutePath")
        } else {
            val unresolvedPath = absolutePath.takeIf { it.startsWith("/storage/", ignoreCase = true) }
            if (unresolvedPath != null && grantedRomRoots.none { grantedRoot -> grantedRoot.coversPath(unresolvedPath) }) {
                uncoveredUnreadablePaths += unresolvedPath
            }
            Log.i(TAG, "${candidate.emulator} candidate unresolved title=${candidate.title} path=${candidate.path} absolutePath=$absolutePath")
            unreadableCount++
        }
    }

    val requiredRomGrantPaths = computeRequestedRomGrantPaths(uncoveredUnreadablePaths)

    Log.i(
        TAG,
        "resolveSmartCacheCandidates finished resolved=${resolved.size} unreadable=$unreadableCount requiredRomGrantPaths=$requiredRomGrantPaths"
    )

    return SmartCachePreflightResult(
        resolved = resolved,
        unreadableCount = unreadableCount,
        requiredRomGrantPaths = collapseRequestedRomGrantPaths(requiredRomGrantPaths.toList())
    )
}

private fun computeRequestedRomGrantPaths(paths: List<String>): List<String> {
    val normalizedPaths = paths
        .map { it.replace('\\', '/').trim().trimEnd('/') }
        .filter { it.startsWith("/storage/", ignoreCase = true) }
        .distinct()
    if (normalizedPaths.isEmpty()) {
        return emptyList()
    }

    return normalizedPaths
        .groupBy { path ->
            val segments = path.trim('/').split('/')
            val volume = segments.getOrNull(1)?.lowercase().orEmpty()
            val root = segments.getOrNull(2)?.lowercase().orEmpty()
            "$volume::$root"
        }
        .values
        .mapNotNull(::preferredGrantPathForGroup)
}

private fun preferredGrantPathForGroup(paths: List<String>): String? {
    val commonParent = commonParentDirectory(paths) ?: return null
    val normalizedCommon = commonParent.replace('\\', '/').trim().trimEnd('/')
    val segments = normalizedCommon.trim('/').split('/').filter { it.isNotBlank() }
    val volume = segments.getOrNull(1)?.lowercase().orEmpty()
    val root = segments.getOrNull(2)?.lowercase().orEmpty()

    if (volume != "emulated" || root != "0") {
        return normalizedCommon
    }

    val topLevelRoot = rootSegmentAfterStorage(paths)
    if (topLevelRoot.equals("roms", ignoreCase = true) || topLevelRoot.equals("rom", ignoreCase = true)) {
        return listOf("storage", "emulated", "0", topLevelRoot).joinToString(prefix = "/", separator = "/")
    }

    return normalizedCommon
}

private fun rootSegmentAfterStorage(paths: List<String>): String {
    val normalizedSegments = paths
        .map { it.replace('\\', '/').trim().trimEnd('/').split('/').filter { segment -> segment.isNotBlank() } }
    val first = normalizedSegments.firstOrNull() ?: return ""
    return first.getOrNull(3).orEmpty()
}

private fun commonParentDirectory(paths: List<String>): String? {
    val splitPaths = paths.map { path ->
        path.replace('\\', '/').trim().trimEnd('/').split('/').filter { it.isNotBlank() }
    }
    val first = splitPaths.firstOrNull() ?: return null
    val minSize = splitPaths.minOf { it.size }
    val commonSegments = mutableListOf<String>()
    for (index in 0 until minSize) {
        val segment = first[index]
        if (splitPaths.all { it[index].equals(segment, ignoreCase = true) }) {
            commonSegments += segment
        } else {
            break
        }
    }
    if (commonSegments.size <= 1) {
        return null
    }
    return "/${commonSegments.joinToString("/")}"
}

private fun collapseRequestedRomGrantPaths(paths: List<String>): List<String> {
    val normalizedPaths = paths
        .map { it.replace('\\', '/').trim().trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    return normalizedPaths.filterNot { candidate ->
        normalizedPaths.any { other ->
            other != candidate && candidate.startsWith("$other/")
        }
    }
}

private fun selectSmartCacheCandidates(
    candidates: List<ResolvedSmartCacheCandidate>,
    candidateCap: Int
): List<ResolvedSmartCacheCandidate> {
    if (candidateCap <= 0 || candidates.isEmpty()) {
        return emptyList()
    }

    val grouped = candidates.groupBy { it.candidate.emulator }
        .mapValues { (_, entries) -> entries.toMutableList() }
        .toMutableMap()
    val selected = mutableListOf<ResolvedSmartCacheCandidate>()
    val perEmulatorBudget = minOf(SMART_CACHE_EMULATOR_BUDGET, candidateCap)
    val emulatorCount = SmartCacheEmulator.entries.size.coerceAtLeast(1)
    val firstPassBudget = minOf(perEmulatorBudget, candidateCap / emulatorCount)

    SmartCacheEmulator.entries.forEach { emulator ->
        val bucket = grouped[emulator] ?: return@forEach
        repeat(minOf(firstPassBudget, bucket.size)) {
            if (selected.size < candidateCap) {
                selected += bucket.removeAt(0)
            }
        }
    }

    if (selected.size >= candidateCap) {
        return selected
    }

    val remainingBuckets = SmartCacheEmulator.entries
        .mapNotNull { emulator -> grouped[emulator] }
    while (selected.size < candidateCap) {
        var addedAny = false
        remainingBuckets.forEach { bucket ->
            if (selected.size >= candidateCap || bucket.isEmpty()) {
                return@forEach
            }
            selected += bucket.removeAt(0)
            addedAny = true
        }
        if (!addedAny) {
            break
        }
    }

    return selected
}

private fun treeUriToAbsolutePath(treeUri: Uri): String {
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        .takeIf { it.isNotBlank() }
        ?: return "/storage/emulated/0"
    return documentIdToAbsoluteStoragePath(treeDocumentId)
}

private fun String.coversPath(otherPath: String): Boolean {
    val normalizedRoot = replace('\\', '/').trim().trimEnd('/')
    val normalizedOther = otherPath.replace('\\', '/').trim().trimEnd('/')
    return normalizedOther.equals(normalizedRoot, ignoreCase = true) ||
        normalizedOther.startsWith("$normalizedRoot/", ignoreCase = true)
}

private fun formatSmartCacheTimestamp(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(timestamp))

private suspend fun executeResolvedSmartCacheCandidates(
    context: Context,
    credentials: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    candidates: List<ResolvedSmartCacheCandidate>,
    onProgress: (current: Int, total: Int, label: String) -> Unit
): ScanResult {
    val cachedGameIds = loadCachedGameIds(db)
    val total = candidates.size
    var matched = 0
    var skipped = 0
    var limitReached = false

    for ((index, resolvedCandidate) in candidates.withIndex()) {
        if (cachedGameIds.size >= MAX_CACHED_GAMES) {
            skipped += total - index
            limitReached = true
            break
        }

        applyScanBatchCooldown(index, TAG)

        val candidate = resolvedCandidate.candidate
        val label = candidate.title?.takeIf { it.isNotBlank() } ?: candidate.path.substringAfterLast('/')
        onProgress(index + 1, total, label)

        val candidateHashes = hashResolvedCandidate(context, resolvedCandidate)
        if (candidateHashes.isEmpty()) {
            Log.i(
                TAG,
                "${candidate.emulator} candidate dropped title=${candidate.title} reason=hash-null path=${candidate.path} lastModifiedAt=${candidate.lastModifiedAt} lastModifiedText=${candidate.lastModifiedAt?.let(::formatSmartCacheTimestamp)}"
            )
            skipped++
            continue
        }

        val resolved = resolveGameId(context, candidateHashes, credentials, userAgent, db)
        if (resolved == null) {
            Log.i(
                TAG,
                "${candidate.emulator} candidate dropped title=${candidate.title} reason=no-gameid path=${candidate.path} hashes=$candidateHashes lastModifiedAt=${candidate.lastModifiedAt} lastModifiedText=${candidate.lastModifiedAt?.let(::formatSmartCacheTimestamp)}"
            )
            skipped++
            continue
        }
        val (hash, gameId) = resolved

        val gameIdString = gameId.toString()
        if (gameIdString in cachedGameIds) {
            Log.i(
                TAG,
                "${candidate.emulator} candidate dropped title=${candidate.title} reason=already-cached path=${candidate.path} gameId=$gameId lastModifiedAt=${candidate.lastModifiedAt} lastModifiedText=${candidate.lastModifiedAt?.let(::formatSmartCacheTimestamp)}"
            )
            skipped++
            continue
        }

        cacheGame(
            context = context,
            gameId = gameId,
            creds = credentials,
            userAgent = userAgent,
            db = db,
            romHash = hash,
            sourceRomPath = candidate.path,
        )
        Log.i(
            TAG,
            "${candidate.emulator} candidate cached title=${candidate.title} path=${candidate.path} gameId=$gameId hash=$hash lastModifiedAt=${candidate.lastModifiedAt} lastModifiedText=${candidate.lastModifiedAt?.let(::formatSmartCacheTimestamp)}"
        )
        cachedGameIds.add(gameIdString)
        matched++
    }

    return ScanResult(
        matched = matched,
        total = total,
        skipped = skipped,
        limitReached = limitReached
    )
}

private fun hashResolvedCandidate(context: Context, candidate: ResolvedSmartCacheCandidate): List<String> = when {
    !candidate.candidate.precomputedHash.isNullOrBlank() -> listOf(candidate.candidate.precomputedHash)
    candidate.directFile != null -> hashFile(candidate.directFile)
    candidate.documentFile != null -> hashRomCandidates(context, candidate.documentFile)
    else -> emptyList()
}

private fun hashFile(file: File): List<String> {
    val fileName = file.name
    if (fileName.endsWith(".zip", ignoreCase = true)) {
        return com.raofflineproxy.proxy.hash.hashZipRomCandidates(
            fileName = fileName,
            sourcePath = file.absolutePath,
            tempDir = file.parentFile ?: file,
            openArchiveStream = { file.inputStream() }
        )
    }

    return hashRomCandidates(
        RomHashInput(
            fileName = fileName,
            fileSize = file.length(),
            openStream = { file.inputStream() },
            openDataSource = { FileBackedRomDataSource(file) },
            // Real path: the native hasher reads it directly, no temp copy.
            sourcePath = file.absolutePath
        )
    )
}

private fun resolveDocumentByAbsolutePath(context: Context, root: DocumentFile, absolutePath: String): DocumentFile? {
    val normalizedPath = absolutePath.replace('\\', '/').trim().trimEnd('/')
    if (normalizedPath.isEmpty()) {
        return null
    }

    val candidates = buildPathCandidatesForTree(root.uri, normalizedPath)

    val directResolved = candidates.firstNotNullOfOrNull { candidate ->
        val documentId = buildDocumentIdForTree(root.uri, candidate) ?: return@firstNotNullOfOrNull null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(root.uri, documentId)
        DocumentFile.fromSingleUri(context, documentUri)
            ?.takeIf { it.exists() && it.isFile }
    }
    if (directResolved != null) {
        return directResolved
    }

    val resolved = candidates.firstNotNullOfOrNull { candidate ->
        candidate.split('/').filter { it.isNotBlank() }
            .fold(root as DocumentFile?) { current, segment -> current?.findFile(segment) }
            ?.takeIf { it.exists() && it.isFile }
    }

    if (resolved == null) {
        Log.d(TAG, "resolveDocumentByAbsolutePath failed absolutePath=$normalizedPath treeUri=${root.uri} candidates=$candidates")
    }
    return resolved
}

private fun resolveDocumentByStoredUri(context: Context, path: String): DocumentFile? {
    if (!path.startsWith("content://", ignoreCase = true)) {
        return null
    }
    return runCatching {
        DocumentFile.fromSingleUri(context, path.toUri())
            ?.takeIf { it.exists() && it.isFile }
    }.getOrNull()
}

private fun String.toAbsoluteStoragePath(): String? {
    if (startsWith("file://", ignoreCase = true)) {
        return runCatching { this.toUri() }.getOrNull()?.path
    }

    if (!startsWith("content://", ignoreCase = true)) {
        return null
    }

    val uri = runCatching { this.toUri() }.getOrNull() ?: return null
    if (!uri.authority.equals("com.android.externalstorage.documents", ignoreCase = true)) {
        return null
    }

    // A tree+document URI (.../tree/<treeDocId>/document/<docId>) encodes the
    // real file under <docId>, not <treeDocId>; DocumentsContract.getDocumentId
    // extracts that correctly. Dolphin's gamelist.cache instead stores a bare
    // tree URI with the filename appended directly as an extra path segment
    // (.../tree/<treeDocId>/<filename>), which getDocumentId rejects; recover
    // the filename from the trailing segments before falling back to the tree
    // doc id alone.
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: documentIdFromTrailingSegments(uri)
        ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    return documentIdToAbsoluteStoragePath(documentId)
}

private fun documentIdFromTrailingSegments(uri: Uri): String? {
    val segments = uri.pathSegments
    val treeIndex = segments.indexOf("tree")
    if (treeIndex == -1 || treeIndex + 1 >= segments.size) {
        return null
    }
    val treeDocId = segments[treeIndex + 1]
    val trailingSegments = segments.drop(treeIndex + 2)
    if (trailingSegments.isEmpty()) {
        return null
    }
    return (listOf(treeDocId) + trailingSegments).joinToString("/")
}

private fun documentIdToAbsoluteStoragePath(documentId: String): String {
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?: return "/storage/emulated/0"
    val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        .trim('/')

    val storageRoot = if (volume.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$volume"
    }

    return if (relativePath.isBlank()) {
        storageRoot
    } else {
        "$storageRoot/$relativePath"
    }
}

private fun buildDocumentIdForTree(treeUri: Uri, relativePath: String): String? {
    val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        .takeIf { it.isNotBlank() }
        ?: return null
    val normalizedRelativePath = relativePath.trim('/').takeIf { it.isNotBlank() } ?: return rootDocumentId
    return "$rootDocumentId/$normalizedRelativePath"
}

private fun buildPathCandidatesForTree(treeUri: Uri, normalizedPath: String): List<String> {
    val candidates = linkedSetOf<String>()
    candidates += normalizedPath

    val storageIndex = normalizedPath.indexOf("/storage/", ignoreCase = true)
    if (storageIndex >= 0) {
        candidates += normalizedPath.substring(storageIndex + "/storage/".length)
    }

    val androidDataIndex = normalizedPath.indexOf("/Android/data/", ignoreCase = true)
    if (androidDataIndex >= 0) {
        candidates += normalizedPath.substring(androidDataIndex + "/Android/data/".length)
    }

    val treeDocumentPath = DocumentsContract.getTreeDocumentId(treeUri)
        .substringAfter(':', "")
        .trim('/')
    if (treeDocumentPath.isNotEmpty()) {
        val marker = "/$treeDocumentPath/"
        val markerIndex = normalizedPath.indexOf(marker, ignoreCase = true)
        if (markerIndex >= 0) {
            candidates += normalizedPath.substring(markerIndex + marker.length)
        }
        if (normalizedPath.endsWith("/$treeDocumentPath", ignoreCase = true)) {
            candidates += ""
        }
    }

    return candidates.filter { it.isNotBlank() }
}

private class FileBackedRomDataSource(
    file: File
) : com.raofflineproxy.proxy.hash.RomDataSource {
    private val inputStream = FileInputStream(file)
    private val channel = inputStream.channel

    override val length: Long
        get() = channel.size()

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        channel.position(offset)
        val targetLength = length.coerceAtMost(buffer.size)
        var totalRead = 0
        while (totalRead < targetLength) {
            val read = inputStream.read(buffer, totalRead, targetLength - totalRead)
            if (read <= 0) {
                break
            }
            totalRead += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        channel.close()
        inputStream.close()
    }
}
