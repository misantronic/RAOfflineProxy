package com.raofflineproxy.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.forEachEntryByPrefix
import com.raofflineproxy.proxy.hash.hashRomCandidates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private const val TAG = "RAProxy/WatchFolders"

internal const val WATCH_RESCAN_INTERVAL_MS = CACHE_BUDGET_WINDOW_MS

data class WatchedFolder(
    val uri: String,
    val name: String,
    val path: String?,
    val watching: Boolean,
    val addedAt: Long,
    val lastScanAt: Long
) {
    // A clock that jumped backwards makes the folder due again instead of postponing it.
    fun isDue(now: Long): Boolean =
        watching && (now < lastScanAt || now - lastScanAt >= WATCH_RESCAN_INTERVAL_MS)

    fun toJson(): String = JSONObject()
        .put("uri", uri)
        .put("name", name)
        .put("path", path ?: JSONObject.NULL)
        .put("watching", watching)
        .put("addedAt", addedAt)
        .put("lastScanAt", lastScanAt)
        .toString()

    companion object {
        fun fromJson(body: String): WatchedFolder? = runCatching {
            val json = JSONObject(body)
            WatchedFolder(
                uri = json.getString("uri").takeIf { it.isNotBlank() } ?: return null,
                name = json.optString("name"),
                path = json.optString("path").takeIf { !json.isNull("path") && it.isNotBlank() },
                watching = json.optBoolean("watching", false),
                addedAt = json.optLong("addedAt"),
                lastScanAt = json.optLong("lastScanAt")
            )
        }.getOrNull()
    }
}

internal data class FolderScan(val files: Int, val new: Int, val handled: Int, val queued: Int) {
    val complete: Boolean get() = handled == new
}

/** Folders the user has added, stored in the cache table so that no schema change is needed.
 *  A watched folder is rescanned once per budget window; [scanWatchedFolder] hashes and queues
 *  only the files it has not handled before. */
internal object WatchFolders {
    fun observe(db: AppDatabase): Flow<List<WatchedFolder>> =
        db.cacheDao().observeByPrefix(CacheKeys.PREFIX_WATCH_FOLDER).map { entries ->
            entries.mapNotNull { WatchedFolder.fromJson(it.responseBody) }
                .sortedBy { it.name.lowercase() }
        }

    suspend fun all(db: AppDatabase): List<WatchedFolder> = buildList {
        db.cacheDao().forEachEntryByPrefix(CacheKeys.PREFIX_WATCH_FOLDER) { entry ->
            WatchedFolder.fromJson(entry.responseBody)?.let(::add)
        }
    }

    suspend fun due(db: AppDatabase, now: Long): List<WatchedFolder> =
        all(db).filter { it.isDue(now) }

    /** Returns false when the folder was already known; its watching state stays as it was. */
    suspend fun add(context: Context, db: AppDatabase, treeUri: Uri, now: Long = System.currentTimeMillis()): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        val folder = WatchedFolder(
            uri = treeUri.toString(),
            name = root?.name ?: treeUri.lastPathSegment?.substringAfterLast(':').orEmpty(),
            path = root?.let(::resolveDocumentRelativePath),
            watching = false,
            addedAt = now,
            lastScanAt = 0L
        )
        return db.cacheDao().insertIgnore(
            CacheEntry(cacheKey = CacheKeys.watchFolder(folder.uri), responseBody = folder.toJson())
        ) != -1L
    }

    suspend fun setWatching(db: AppDatabase, uri: String, watching: Boolean) {
        update(db, uri) { it.copy(watching = watching, lastScanAt = 0L) }
    }

    suspend fun remove(db: AppDatabase, uri: String) {
        db.cacheDao().deleteByKey(CacheKeys.watchFolder(uri))
        forgetSeen(db, seenKeys(db, uri))
    }

    suspend fun recordScan(db: AppDatabase, uri: String, scannedAt: Long) {
        update(db, uri) { it.copy(lastScanAt = scannedAt) }
    }

    private suspend fun update(db: AppDatabase, uri: String, change: (WatchedFolder) -> WatchedFolder) {
        val key = CacheKeys.watchFolder(uri)
        val current = db.cacheDao().get(key)?.let { WatchedFolder.fromJson(it.responseBody) } ?: return
        db.cacheDao().updateBody(key, change(current).toJson(), null, System.currentTimeMillis())
    }

    // The prefix lookup is a LIKE match, so "_" in a tree uri would also match neighbouring
    // folders; the startsWith check keeps it to this one.
    private suspend fun seenKeys(db: AppDatabase, uri: String): Set<String> {
        val prefix = CacheKeys.watchSeenPrefix(uri)
        return db.cacheDao().getKeysByPrefix(prefix).filterTo(mutableSetOf()) { it.startsWith(prefix) }
    }

    private suspend fun seenPaths(db: AppDatabase, uri: String): Set<String> {
        val prefixLength = CacheKeys.watchSeenPrefix(uri).length
        return seenKeys(db, uri).mapTo(mutableSetOf()) { it.substring(prefixLength) }
    }

    private suspend fun markSeen(db: AppDatabase, uri: String, path: String) {
        db.cacheDao().insertIgnore(CacheEntry(cacheKey = CacheKeys.watchSeen(uri, path), responseBody = "{}"))
    }

    private suspend fun forgetSeen(db: AppDatabase, keys: Collection<String>) {
        keys.forEach { db.cacheDao().deleteByKey(it) }
    }

    /** Hashes and queues only the files this folder has not handled before. A file counts as
     *  handled once caching has an answer for it, including "no RetroAchievements match", so
     *  unknown files are not hashed again on every pass. Returns null when the folder cannot
     *  be read, e.g. because its access grant is gone. */
    suspend fun scan(
        context: Context,
        db: AppDatabase,
        folder: WatchedFolder,
        shouldAbort: () -> Boolean,
        now: () -> Long = System::currentTimeMillis
    ): FolderScan? {
        val treeUri = folder.uri.toUri()
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { granted && it.canRead() } ?: return null

        val files = collectScannableFiles(root, MAX_SCAN_ENTRIES)
        val romPaths = files.map(::resolveDocumentAbsolutePath)
        val seenKeys = files.indices.map { romPaths[it] ?: files[it].uri.toString() }
        val seen = seenPaths(db, folder.uri)
        val knownPaths = loadCachedRomPaths(db) + CacheQueue.queuedRomPaths(db)
        val cachedGameIds = loadCachedGameIds(db)

        val unseen = files.indices.filter { seenKeys[it] !in seen }
        val (known, fresh) = unseen.partition { romPaths[it]?.normalizeCachedRomPath() in knownPaths }
        known.forEach { markSeen(db, folder.uri, seenKeys[it]) }

        var handled = 0
        var queued = 0
        for (index in fresh) {
            if (shouldAbort()) break
            val file = files[index]
            val candidates = runCatching { hashRomCandidates(context, file) }
                .onFailure { Log.w(TAG, "Could not hash ${file.name}", it) }
                .getOrDefault(emptyList())
            if (candidates.isNotEmpty() &&
                enqueueRomIfNeeded(db, candidates, cachedGameIds, romPaths[index], file.name ?: "")
            ) {
                queued++
            }
            markSeen(db, folder.uri, seenKeys[index])
            handled++
        }

        // A capped listing leaves out files that still exist, so their markers must survive.
        if (files.size < MAX_SCAN_ENTRIES) {
            val prefix = CacheKeys.watchSeenPrefix(folder.uri)
            val gone = (seen - seenKeys.toSet()).map { "$prefix$it" }
            forgetSeen(db, gone)
        }

        val scan = FolderScan(files.size, fresh.size, handled, queued)
        if (scan.complete) recordScan(db, folder.uri, now())
        return scan
    }
}
