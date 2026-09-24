package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal const val CACHE_QUEUE_MAX_ATTEMPTS = 3

internal data class QueuedRom(
    val hashes: List<String>,
    val sourceRomPath: String?,
    val label: String,
    val queuedAt: Long,
    val attempts: Int = 0
) {
    val key: String get() = CacheKeys.cacheQueue(hashes.first())

    fun afterFailedAttempt(): QueuedRom? =
        copy(attempts = attempts + 1).takeIf { it.attempts < CACHE_QUEUE_MAX_ATTEMPTS }

    fun toJson(): String = JSONObject()
        .put("hashes", JSONArray(hashes))
        .put("sourceRomPath", sourceRomPath ?: JSONObject.NULL)
        .put("label", label)
        .put("queuedAt", queuedAt)
        .put("attempts", attempts)
        .toString()

    companion object {
        fun fromJson(body: String): QueuedRom? = runCatching {
            val json = JSONObject(body)
            val hashArray = json.getJSONArray("hashes")
            val hashes = (0 until hashArray.length()).map { hashArray.getString(it) }.filter { it.isNotBlank() }
            if (hashes.isEmpty()) return null
            QueuedRom(
                hashes = hashes,
                sourceRomPath = json.optString("sourceRomPath").takeIf { !json.isNull("sourceRomPath") && it.isNotBlank() },
                label = json.optString("label"),
                queuedAt = json.optLong("queuedAt"),
                attempts = json.optInt("attempts", 0)
            )
        }.getOrNull()
    }
}

data class QueueEstimate(
    val candidates: Int,
    val cachedNow: Int,
    val newlyQueued: Int,
    val queuedAfter: Int,
    val etaMinutes: Int
) {
    val needsConfirmation: Boolean get() = newlyQueued > 0 && queuedAfter > CACHE_BUDGET_LIMIT
}

/** Upper bound for what a bulk operation will do, known before a single file is hashed: every
 *  candidate not already known by path may need RA. ROMs RA doesn't know still count here, so the
 *  real queue can only turn out smaller. */
internal fun estimateQueue(
    candidates: Int,
    alreadyKnown: Int,
    budgetRemaining: Int,
    queuedNow: Int,
    limit: Int = CACHE_BUDGET_LIMIT,
    windowMinutes: Int = (CACHE_BUDGET_WINDOW_MS / 60_000).toInt()
): QueueEstimate {
    val fresh = (candidates - alreadyKnown).coerceAtLeast(0)
    val cachedNow = minOf(fresh, budgetRemaining.coerceAtLeast(0))
    val newlyQueued = fresh - cachedNow
    val queuedAfter = queuedNow + newlyQueued
    val etaMinutes = ceil(queuedAfter.toDouble() / limit).toInt() * windowMinutes
    return QueueEstimate(candidates, cachedNow, newlyQueued, queuedAfter, etaMinutes)
}

internal object CacheQueue {
    // The app's first-batch drain and the service worker share one queue and one budget.
    val drainLock = Mutex()
    private val activeBulkRuns = AtomicInteger()

    /** True while Add ROM, Scan folder or Smart Cache hashes and runs its first batch; the
     *  service worker stands down meanwhile so it never drains a queue that is still filling. */
    val bulkRunActive: Boolean get() = activeBulkRuns.get() > 0

    suspend fun <T> duringBulkRun(block: suspend () -> T): T {
        activeBulkRuns.incrementAndGet()
        try {
            return block()
        } finally {
            activeBulkRuns.decrementAndGet()
        }
    }

    /** Returns false when the ROM was already waiting in the queue. */
    suspend fun enqueue(db: AppDatabase, rom: QueuedRom): Boolean =
        db.cacheDao().insertIgnore(
            CacheEntry(cacheKey = rom.key, responseBody = rom.toJson(), sourceRomPath = rom.sourceRomPath)
        ) != -1L

    suspend fun removeKeys(db: AppDatabase, keys: Collection<String>) {
        keys.forEach { key -> db.cacheDao().deleteByKey(key) }
    }

    suspend fun update(db: AppDatabase, rom: QueuedRom) {
        db.cacheDao().updateBody(rom.key, rom.toJson(), rom.sourceRomPath, System.currentTimeMillis())
    }

    suspend fun remove(db: AppDatabase, rom: QueuedRom) {
        db.cacheDao().deleteByKey(rom.key)
    }

    suspend fun oldest(db: AppDatabase): QueuedRom? {
        val dao = db.cacheDao()
        val summary = dao.oldestSummariesByPrefix(CacheKeys.PREFIX_CACHE_QUEUE, 1).firstOrNull() ?: return null
        val rom = dao.get(summary.cacheKey)?.responseBody?.let(QueuedRom::fromJson)
        if (rom == null) {
            dao.deleteByKey(summary.cacheKey)
            return oldest(db)
        }
        return rom
    }

    suspend fun count(db: AppDatabase): Int = db.cacheDao().countByPrefix(CacheKeys.PREFIX_CACHE_QUEUE)

    fun observeCount(db: AppDatabase): Flow<Int> = db.cacheDao().observeCountByPrefix(CacheKeys.PREFIX_CACHE_QUEUE)

    suspend fun queuedRomPaths(db: AppDatabase): Set<String> =
        db.cacheDao().getAllSummariesByPrefix(CacheKeys.PREFIX_CACHE_QUEUE)
            .mapNotNullTo(mutableSetOf()) { it.sourceRomPath?.normalizeCachedRomPath() }
}
