package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal const val CACHE_BUDGET_LIMIT = 100
internal const val CACHE_BUDGET_WINDOW_MS = 30L * 60 * 1000
// Bounds how long a batch keeps the device awake, e.g. on a stretch of ROMs RetroAchievements
// doesn't know, which cost lookups but never fill the budget.
internal const val CACHE_BATCH_MAX_MS = 10L * 60 * 1000

/** One budget window: [used] counts games cached; lookups for ROMs RetroAchievements doesn't know
 *  are free. [pausedUntil] holds the queue back after a 429 or after a batch hit its time limit,
 *  so no new batch starts before then, and outlives the window. */
internal data class BudgetWindow(
    val windowStart: Long = 0L,
    val used: Int = 0,
    val pausedUntil: Long = 0L
) {
    // A clock that jumped backwards starts a fresh window instead of blocking caching until it
    // catches up again.
    fun current(now: Long, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow =
        if (now < windowStart || now - windowStart >= windowMs) BudgetWindow(now, pausedUntil = pausedUntil) else this

    fun charge(now: Long, games: Int, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow {
        val window = current(now, windowMs)
        return window.copy(used = window.used + games)
    }

    fun remaining(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Int = if (now < pausedUntil) 0 else (limit - current(now, windowMs).used).coerceAtLeast(0)

    fun nextAvailableAt(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Long {
        val window = current(now, windowMs)
        val windowOpensAt = if (window.used < limit) now else window.windowStart + windowMs
        return maxOf(pausedUntil, windowOpensAt)
    }

    fun endsAt(now: Long, windowMs: Long = CACHE_BUDGET_WINDOW_MS): Long =
        maxOf(pausedUntil, current(now, windowMs).windowStart + windowMs)

    fun toJson(): String = JSONObject()
        .put("windowStart", windowStart)
        .put("used", used)
        .put("pausedUntil", pausedUntil)
        .toString()

    companion object {
        fun fromJson(body: String?): BudgetWindow = runCatching {
            val json = JSONObject(body.orEmpty())
            BudgetWindow(json.optLong("windowStart", 0L), json.optInt("used", 0), json.optLong("pausedUntil", 0L))
        }.getOrDefault(BudgetWindow())
    }
}

internal object CacheBudget {
    private val mutex = Mutex()

    suspend fun chargeGame(db: AppDatabase, now: Long = System.currentTimeMillis()) {
        mutex.withLock { save(db, load(db).charge(now, games = 1)) }
    }

    suspend fun pauseUntil(db: AppDatabase, until: Long) {
        mutex.withLock {
            val window = load(db)
            if (until > window.pausedUntil) save(db, window.copy(pausedUntil = until))
        }
    }

    suspend fun remaining(db: AppDatabase, now: Long = System.currentTimeMillis()): Int =
        mutex.withLock { load(db).remaining(now) }

    suspend fun nextAvailableAt(db: AppDatabase, now: Long = System.currentTimeMillis()): Long =
        mutex.withLock { load(db).nextAvailableAt(now) }

    suspend fun windowEndsAt(db: AppDatabase, now: Long = System.currentTimeMillis()): Long =
        mutex.withLock { load(db).endsAt(now) }

    private suspend fun load(db: AppDatabase): BudgetWindow =
        BudgetWindow.fromJson(db.cacheDao().get(CacheKeys.CACHE_BUDGET)?.responseBody)

    private suspend fun save(db: AppDatabase, window: BudgetWindow) {
        db.cacheDao().upsert(CacheEntry(cacheKey = CacheKeys.CACHE_BUDGET, responseBody = window.toJson()))
    }
}
