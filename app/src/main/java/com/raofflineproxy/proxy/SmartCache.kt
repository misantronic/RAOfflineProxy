package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
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

private val DOLPHIN_GAMELIST_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "cache", "gamelist.cache")
} + listOf(
    listOf("cache", "gamelist.cache"),
    listOf("gamelist.cache")
)

private val DOLPHIN_GC_PATHS = DOLPHIN_PACKAGE_CANDIDATES.map { packageName ->
    listOf(packageName, "files", "GC")
} + listOf(
    listOf("files", "GC"),
    listOf("GC")
)

private const val MAX_SMART_CACHE_FILES = 50
private const val SMART_CACHE_EMULATOR_BUDGET = 25
private val DOLPHIN_GAME_CODE_REGEX = Regex("(?<![A-Z0-9])[A-Z0-9]{6}(?![A-Z0-9])")
private val DOLPHIN_GCI_CODE_REGEX = Regex("^\\d{2}-([A-Z0-9]{4})-.*\\.gci$", RegexOption.IGNORE_CASE)
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
    val requiredRomGrantPaths: List<String> = emptyList()
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
        val gcRoot = findDirectory(tree, DOLPHIN_GC_PATHS)
            ?: run {
                Log.i(TAG, "Dolphin strategy did not find GC save root in granted tree")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }

        val gamelistBytes = context.contentResolver.openInputStream(gamelistFile.uri)
            ?.use { it.readBytes() }
            ?: run {
                Log.w(TAG, "Dolphin strategy could not read gamelist uri=${gamelistFile.uri}")
                return SmartCacheStrategyResult(message = "no_recent_games")
            }
        val entriesByCode = parseDolphinGameListEntries(gamelistBytes)
            .groupBy { it.gameCode.take(4) }
        if (entriesByCode.isEmpty()) {
            Log.i(TAG, "Dolphin strategy parsed no game entries from gamelist.cache")
            return SmartCacheStrategyResult(message = "no_recent_games")
        }

        val recentSaveCodes = loadRecentGameCubeSaveCodes(gcRoot)
        if (recentSaveCodes.isEmpty()) {
            Log.i(TAG, "Dolphin strategy found no recent GameCube savefiles")
            return SmartCacheStrategyResult(message = "no_recent_games")
        }

        val candidates = recentSaveCodes.entries
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

        Log.i(TAG, "Dolphin strategy discovered ${candidates.size} GameCube candidates from ${gamelistFile.uri}")
        return if (candidates.isEmpty()) {
            SmartCacheStrategyResult(message = "no_recent_games")
        } else {
            SmartCacheStrategyResult(candidates = candidates)
        }
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
            message = strategyMessage ?: if (needsSafGrant) "needs_saf_grant" else "no_recent_games"
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
            requiredRomGrantPaths = preflight.requiredRomGrantPaths
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

private fun findDirectory(root: DocumentFile, pathVariants: List<List<String>>): DocumentFile? =
    pathVariants.firstNotNullOfOrNull { segments ->
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

private fun resolveSmartCacheCandidates(
    context: Context,
    candidates: List<SmartCacheCandidate>,
    romTreeUris: List<Uri>
): SmartCachePreflightResult {
    val resolved = mutableListOf<ResolvedSmartCacheCandidate>()
    var unreadableCount = 0
    val requiredRomGrantPaths = linkedSetOf<String>()
    val romTrees = romTreeUris.mapNotNull { uri ->
        DocumentFile.fromTreeUri(context, uri)?.let { tree -> uri to tree }
    }
    val grantedRomRoots = romTreeUris.map(::treeUriToAbsolutePath)

    candidates.forEach { candidate ->
        val directDocument = resolveDocumentByStoredUri(context, candidate.path)
        if (directDocument != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = directDocument)
            return@forEach
        }

        val absolutePath = candidate.path.toAbsoluteStoragePath() ?: candidate.path
        val directFile = File(absolutePath)
        if (directFile.isFile && directFile.canRead()) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, directFile = directFile)
            return@forEach
        }

        val document = romTrees.firstNotNullOfOrNull { (_, tree) ->
            resolveDocumentByAbsolutePath(context, tree, absolutePath)
        }
        if (document != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = document)
        } else {
            val requestedGrantPath = computeRequestedRomGrantPath(candidate.path)
            if (requestedGrantPath != null && grantedRomRoots.none { grantedRoot -> grantedRoot.coversPath(requestedGrantPath) }) {
                requiredRomGrantPaths += requestedGrantPath
            }
            unreadableCount++
        }
    }

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

private fun computeRequestedRomGrantPath(path: String): String? {
    val absolutePath = path.toAbsoluteStoragePath() ?: path
    val relativePath = absolutePath.substringAfter("/storage/emulated/0/", missingDelimiterValue = "")
        .trim('/')
        .takeIf { it.isNotBlank() }
        ?: return null
    val segments = relativePath.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) {
        return null
    }

    val requestedSegments = when (segments.first()) {
        "Download" -> segments.take(minOf(2, segments.size))
        "ROMs" -> segments.take(1)
        else -> segments.take(1)
    }
    return "/storage/emulated/0/${requestedSegments.joinToString("/")}"
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

    SmartCacheEmulator.entries.forEach { emulator ->
        val bucket = grouped[emulator] ?: return@forEach
        repeat(minOf(perEmulatorBudget, bucket.size)) {
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
    val documentPath = DocumentsContract.getTreeDocumentId(treeUri)
        .substringAfter(':', missingDelimiterValue = "")
        .trim('/')
        .takeIf { it.isNotBlank() }
        ?: return "/storage/emulated/0"
    return "/storage/emulated/0/$documentPath"
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
            Log.d(TAG, "Skipping candidate path=${candidate.path} because hashing returned null")
            skipped++
            continue
        }

        val gameId = fetchGameId(context, hash, credentials, userAgent, db)
        if (gameId == null) {
            Log.d(TAG, "Skipping candidate path=${candidate.path} because gameid lookup returned null")
            skipped++
            continue
        }

        val gameIdString = gameId.toString()
        if (gameIdString in cachedGameIds) {
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
    val documentPath = decodedPath.substringAfter(':', missingDelimiterValue = "")
        .trim('/')
        .takeIf { it.isNotBlank() }
        ?: return null
    return "/storage/emulated/0/$documentPath"
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
