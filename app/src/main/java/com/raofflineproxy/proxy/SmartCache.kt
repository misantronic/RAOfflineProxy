package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.hash.RomHashInput
import com.raofflineproxy.proxy.hash.hashRom
import com.raofflineproxy.ui.DOLPHIN_PACKAGE_CANDIDATES
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.RETROARCH_PACKAGE_CANDIDATES
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import androidx.core.net.toUri

private const val TAG = "RAProxy/SmartCache"
private const val DOLPHIN_RECENT_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
private val SMART_CACHE_EXT_STORAGE by lazy { Environment.getExternalStorageDirectory().path }

private val RETROARCH_HISTORY_PATHS = RETROARCH_PACKAGE_CANDIDATES.flatMap { packageName ->
    listOf(
        listOf(packageName, "files", "content_history.lpl"),
        listOf(packageName, "files", "playlists", "content_history.lpl")
    )
} + listOf(
    listOf("files", "content_history.lpl"),
    listOf("files", "playlists", "content_history.lpl"),
    listOf("content_history.lpl"),
    listOf("playlists", "content_history.lpl")
)

private val RETROARCH_HISTORY_SOURCE_CANDIDATES by lazy {
    RETROARCH_PACKAGE_CANDIDATES.flatMap { packageName ->
        listOf(
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/files/content_history.lpl",
            "/storage/emulated/0/Android/data/$packageName/files/content_history.lpl",
            "$SMART_CACHE_EXT_STORAGE/Android/data/$packageName/files/playlists/content_history.lpl",
            "/storage/emulated/0/Android/data/$packageName/files/playlists/content_history.lpl"
        )
    } + listOf(
        "$SMART_CACHE_EXT_STORAGE/RetroArch/content_history.lpl",
        "/storage/emulated/0/RetroArch/content_history.lpl",
        "$SMART_CACHE_EXT_STORAGE/RetroArch/playlists/content_history.lpl",
        "/storage/emulated/0/RetroArch/playlists/content_history.lpl"
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

private const val MAX_SMART_CACHE_FILES = 50
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
    Dolphin
}

internal data class SmartCacheCandidate(
    val emulator: SmartCacheEmulator,
    val sourceLabel: String,
    val path: String,
    val title: String? = null,
    val priority: Int = 0
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

private object RetroArchSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.RetroArch

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.retroArchInstalled &&
            emulatorSupport.retroArchEnabled

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult {
        val directHistoryFile = firstReadableFile(RETROARCH_HISTORY_SOURCE_CANDIDATES)
        if (directHistoryFile != null) {
            val directContent = runCatching { directHistoryFile.readText() }
                .onFailure { error -> Log.w(TAG, "RetroArch strategy could not read direct history path=${directHistoryFile.path}", error) }
                .getOrNull()
            if (directContent != null) {
                val candidates = parseRetroArchHistory(directContent)
                Log.i(TAG, "RetroArch strategy discovered ${candidates.size} history candidates from ${directHistoryFile.path}")
                return SmartCacheStrategyResult(candidates = candidates)
            }
        }

        if (treeUri == null) {
            Log.i(TAG, "RetroArch strategy needs SAF grant for history file")
            return SmartCacheStrategyResult(needsSafGrant = true)
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                Log.w(TAG, "RetroArch strategy could not open treeUri=$treeUri")
                return SmartCacheStrategyResult(needsSafGrant = true)
            }
        val historyFile = findDocument(tree, RETROARCH_HISTORY_PATHS)
            ?: run {
                Log.i(TAG, "RetroArch strategy did not find content_history.lpl in granted tree")
                return SmartCacheStrategyResult(message = "history_missing")
            }
        val content = context.contentResolver.openInputStream(historyFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: run {
                Log.w(TAG, "RetroArch strategy could not read history file uri=${historyFile.uri}")
                return SmartCacheStrategyResult(message = "history_missing")
            }

        val candidates = parseRetroArchHistory(content)
        Log.i(TAG, "RetroArch strategy discovered ${candidates.size} history candidates from ${historyFile.uri}")
        return SmartCacheStrategyResult(candidates = candidates)
    }
}

private object DolphinSmartCacheStrategy : SmartCacheStrategy {
    override val emulator: SmartCacheEmulator = SmartCacheEmulator.Dolphin

    override fun isEnabled(context: Context, emulatorSupport: EmulatorSupport): Boolean =
        emulatorSupport.dolphinInstalled &&
            emulatorSupport.dolphinEnabled

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

internal suspend fun runSmartCache(
    context: Context,
    credentials: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    emulatorSupport: EmulatorSupport,
    retroArchTreeUri: Uri?,
    dolphinTreeUri: Uri?,
    romTreeUris: List<Uri>,
    onProgress: (current: Int, total: Int, label: String) -> Unit
): SmartCacheRunResult {
    Log.i(
        TAG,
        "runSmartCache start retroArchTreeUri=$retroArchTreeUri dolphinTreeUri=$dolphinTreeUri romTreeUris=${romTreeUris.size} retroArchEnabled=${emulatorSupport.retroArchEnabled} dolphinEnabled=${emulatorSupport.dolphinEnabled}"
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

    val activeStrategies = listOf(RetroArchSmartCacheStrategy, DolphinSmartCacheStrategy)
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

    if (discoveredCandidates.isEmpty()) {
        Log.i(TAG, "runSmartCache found no discovered candidates needsSafGrant=$needsSafGrant strategyMessage=$strategyMessage")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            needsSafGrant = needsSafGrant,
            message = strategyMessage ?: if (needsSafGrant) "needs_saf_grant" else "no_recent_games",
            requiredSafGrantTargets = requiredSafGrantTargets.toList()
        )
    }

    val candidates = discoveredCandidates.values.toList()
        .filterNot { candidate ->
            val normalizedPath = candidate.path.normalizeCachedRomPath()
            val alreadyCached = normalizedPath in cachedRomPaths
            if (alreadyCached) {
                Log.d(TAG, "Prefiltering candidate path=${candidate.path} because source path is already cached")
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
    resolvedCandidates.filter { it.candidate.emulator == SmartCacheEmulator.Dolphin }.forEach { resolvedCandidate ->
        Log.i(TAG, "Dolphin candidate selected title=${resolvedCandidate.candidate.title} path=${resolvedCandidate.candidate.path}")
    }
    Log.i(
        TAG,
        "runSmartCache readable cap=$candidateCap resolved=${preflight.resolved.size} capped=${resolvedCandidates.size} remainingSlots=$remainingSlots retroArchSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.RetroArch }} dolphinSelected=${resolvedCandidates.count { it.candidate.emulator == SmartCacheEmulator.Dolphin }}"
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
            needsSafGrant = needsSafGrant,
            message = "no_ra_matches"
        )
    }

    return SmartCacheRunResult(
        matched = queueResult.matched,
        total = relevantTotal,
        skipped = queueResult.skipped,
        limitReached = queueResult.limitReached,
        needsSafGrant = needsSafGrant,
        message = null
    ).also {
        Log.i(
            TAG,
            "runSmartCache complete matched=${it.matched} total=${it.total} skipped=${it.skipped} limitReached=${it.limitReached}"
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
    Uri.decode(path.substringAfterLast('/')).takeIf { it.isNotBlank() }

private fun firstReadableFile(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull { it.isFile && it.canRead() }

private fun firstReadableDirectory(paths: List<String>): File? =
    paths.asSequence().map(::File).firstOrNull { it.isDirectory && it.canRead() }

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
                    priority = 0
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
        val directDocument = resolveDocumentByStoredUri(context, candidate.path)
        if (directDocument != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = directDocument)
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate resolved title=${candidate.title} via=storedUri path=${candidate.path}")
            }
            return@forEach
        }

        val absolutePath = candidate.path.toAbsoluteStoragePath() ?: candidate.path
        val directFile = File(absolutePath)
        if (directFile.isFile && directFile.canRead()) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, directFile = directFile)
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate resolved title=${candidate.title} via=directFile path=${candidate.path} absolutePath=$absolutePath")
            }
            return@forEach
        }

        if (hasAllFilesAccess) {
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate unreadable title=${candidate.title} via=allFiles path=${candidate.path} absolutePath=$absolutePath")
            }
            unreadableCount++
            return@forEach
        }

        val document = romTrees.firstNotNullOfOrNull { (_, tree) ->
            resolveDocumentByAbsolutePath(context, tree, absolutePath)
        }
        if (document != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = document)
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate resolved title=${candidate.title} via=romSaf path=${candidate.path} absolutePath=$absolutePath")
            }
        } else {
            val unresolvedPath = absolutePath.takeIf { it.startsWith("/storage/", ignoreCase = true) }
            if (unresolvedPath != null && grantedRomRoots.none { grantedRoot -> grantedRoot.coversPath(unresolvedPath) }) {
                uncoveredUnreadablePaths += unresolvedPath
            }
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate unresolved title=${candidate.title} path=${candidate.path} absolutePath=$absolutePath")
            }
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

        val candidate = resolvedCandidate.candidate
        val label = candidate.title?.takeIf { it.isNotBlank() } ?: candidate.path.substringAfterLast('/')
        onProgress(index + 1, total, label)

        val hash = hashResolvedCandidate(context, resolvedCandidate)
        if (hash == null) {
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate skipped title=${candidate.title} reason=hash-null path=${candidate.path}")
            }
            Log.d(TAG, "Skipping candidate path=${candidate.path} because hashing returned null")
            skipped++
            continue
        }

        val gameId = fetchGameId(context, hash, credentials, userAgent, db)
        if (gameId == null) {
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate skipped title=${candidate.title} reason=no-gameid path=${candidate.path} hash=$hash")
            }
            Log.d(TAG, "Skipping candidate path=${candidate.path} because gameid lookup returned null")
            skipped++
            continue
        }

        val gameIdString = gameId.toString()
        if (gameIdString in cachedGameIds) {
            if (candidate.emulator == SmartCacheEmulator.Dolphin) {
                Log.i(TAG, "Dolphin candidate skipped title=${candidate.title} reason=already-cached path=${candidate.path} gameId=$gameId")
            }
            Log.d(TAG, "Skipping candidate path=${candidate.path} because gameId=$gameId became cached during smart cache")
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
            cacheBadgeImages = false
        )
        if (candidate.emulator == SmartCacheEmulator.Dolphin) {
            Log.i(TAG, "Dolphin candidate cached title=${candidate.title} path=${candidate.path} gameId=$gameId hash=$hash")
        }
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

private fun hashResolvedCandidate(context: Context, candidate: ResolvedSmartCacheCandidate): String? = when {
    candidate.directFile != null -> hashFile(candidate.directFile)
    candidate.documentFile != null -> hashRom(context, candidate.documentFile)
    else -> null
}

private fun hashFile(file: File): String? {
    val fileName = file.name
    if (fileName.endsWith(".zip", ignoreCase = true)) {
        return com.raofflineproxy.proxy.hash.hashZipRom(
            tempDir = file.parentFile ?: file,
            openArchiveStream = { file.inputStream() }
        )
    }

    return hashRom(
        RomHashInput(
            fileName = fileName,
            fileSize = file.length(),
            openStream = { file.inputStream() },
            openDataSource = { FileBackedRomDataSource(file) }
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
    if (!startsWith("content://", ignoreCase = true)) {
        return null
    }

    val uri = runCatching { this.toUri() }.getOrNull() ?: return null
    if (!uri.authority.equals("com.android.externalstorage.documents", ignoreCase = true)) {
        return null
    }

    val encodedPath = uri.path
        ?.substringAfter("/tree/", missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val decodedPath = Uri.decode(encodedPath)
        .substringBefore('?')
        .trim('/')
    return documentIdToAbsoluteStoragePath(decodedPath)
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
