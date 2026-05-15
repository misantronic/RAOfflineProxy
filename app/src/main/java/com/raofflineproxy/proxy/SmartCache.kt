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
import com.raofflineproxy.ui.EmulatorSupport
import com.raofflineproxy.ui.RETROARCH_PACKAGE_CANDIDATES
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

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

private const val MAX_SMART_CACHE_FILES = 25

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
    val message: String? = null
)

private data class ResolvedSmartCacheCandidate(
    val candidate: SmartCacheCandidate,
    val directFile: File? = null,
    val documentFile: DocumentFile? = null
)

private data class SmartCachePreflightResult(
    val resolved: List<ResolvedSmartCacheCandidate>,
    val unreadableCount: Int,
    val needsRomGrant: Boolean
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

    override fun discoverCandidates(context: Context, treeUri: Uri?): SmartCacheStrategyResult =
        SmartCacheStrategyResult()
}

internal suspend fun runSmartCache(
    context: Context,
    credentials: LoginCredentials,
    userAgent: String,
    db: AppDatabase,
    emulatorSupport: EmulatorSupport,
    retroArchTreeUri: Uri?,
    romTreeUri: Uri?,
    onProgress: (current: Int, total: Int, label: String) -> Unit
): SmartCacheRunResult {
    Log.i(
        TAG,
        "runSmartCache start retroArchTreeUri=$retroArchTreeUri romTreeUri=$romTreeUri retroArchEnabled=${emulatorSupport.retroArchEnabled} dolphinEnabled=${emulatorSupport.dolphinEnabled}"
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
            SmartCacheEmulator.Dolphin -> null
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
            message = strategyMessage ?: if (needsSafGrant) "needs_saf_grant" else "history_empty"
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
    val preflight = resolveSmartCacheCandidates(context, candidates, romTreeUri)
    Log.i(
        TAG,
        "runSmartCache preflight resolved=${preflight.resolved.size} unreadable=${preflight.unreadableCount} needsRomGrant=${preflight.needsRomGrant}"
    )
    if (preflight.needsRomGrant) {
        Log.i(TAG, "runSmartCache requesting ROM tree grant before caching")
        return SmartCacheRunResult(
            matched = 0,
            total = candidates.size,
            skipped = 0,
            limitReached = false,
            needsSafGrant = true,
            message = "needs_rom_saf_grant"
        )
    }
    if (preflight.resolved.isEmpty()) {
        Log.i(TAG, "runSmartCache found no cacheable candidates after preflight")
        return SmartCacheRunResult(
            matched = 0,
            total = 0,
            skipped = 0,
            limitReached = false,
            message = "no_readable_candidates"
        )
    }

    val candidateCap = minOf(remainingSlots, MAX_SMART_CACHE_FILES)
    val resolvedCandidates = preflight.resolved.take(candidateCap)
    Log.i(
        TAG,
        "runSmartCache readable cap=$candidateCap resolved=${preflight.resolved.size} capped=${resolvedCandidates.size} remainingSlots=$remainingSlots"
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

private fun resolveSmartCacheCandidates(
    context: Context,
    candidates: List<SmartCacheCandidate>,
    romTreeUri: Uri?
): SmartCachePreflightResult {
    val resolved = mutableListOf<ResolvedSmartCacheCandidate>()
    var unreadableCount = 0
    var needsRomGrant = false
    val romTree = romTreeUri?.let { DocumentFile.fromTreeUri(context, it) }

    candidates.forEach { candidate ->
        val directFile = File(candidate.path)
        if (directFile.isFile && directFile.canRead()) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, directFile = directFile)
            return@forEach
        }

        if (romTree == null) {
            needsRomGrant = true
            return@forEach
        }

        val document = resolveDocumentByAbsolutePath(context, romTree, candidate.path)
        if (document != null) {
            resolved += ResolvedSmartCacheCandidate(candidate = candidate, documentFile = document)
        } else {
            unreadableCount++
        }
    }

    Log.i(
        TAG,
        "resolveSmartCacheCandidates finished resolved=${resolved.size} unreadable=$unreadableCount needsRomGrant=$needsRomGrant"
    )

    return SmartCachePreflightResult(
        resolved = resolved,
        unreadableCount = unreadableCount,
        needsRomGrant = needsRomGrant
    )
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
            sourceRomPath = candidate.path
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
