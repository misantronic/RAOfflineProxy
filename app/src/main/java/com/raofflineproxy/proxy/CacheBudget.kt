package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal const val CACHE_BUDGET_LIMIT = 100
internal const val CACHE_REQUEST_LIMIT = 300
internal const val CACHE_BUDGET_WINDOW_MS = 30L * 60 * 1000
// A game id lookup plus the achievement set and unlocks of a game RetroAchievements knows.
internal const val REQUESTS_PER_NEW_GAME = 3

/** One budget window: [used] counts games cached, [requests] every RA request the queue sent,
 *  including lookups for ROMs RetroAchievements doesn't know. [pausedUntil] holds the queue back
 *  after a 429 and outlives the window. */
internal data class BudgetWindow(
    val windowStart: Long = 0L,
    val used: Int = 0,
    val requests: Int = 0,
    val pausedUntil: Long = 0L
) {
    // A clock that jumped backwards starts a fresh window instead of blocking caching until it
    // catches up again.
    fun current(now: Long, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow =
        if (now < windowStart || now - windowStart >= windowMs) BudgetWindow(now, pausedUntil = pausedUntil) else this

    /** Whether [neededRequests] more requests and one more game still fit into this window. */
    fun fits(
        now: Long,
        neededRequests: Int,
        limit: Int = CACHE_BUDGET_LIMIT,
        requestLimit: Int = CACHE_REQUEST_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Boolean {
        val window = current(now, windowMs)
        return now >= pausedUntil && window.used < limit && window.requests + neededRequests <= requestLimit
    }

    fun charge(now: Long, requests: Int, games: Int, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow {
        val window = current(now, windowMs)
        return window.copy(used = window.used + games, requests = window.requests + requests)
    }

    /** New games that still fit into this window, assuming each needs a lookup. */
    fun remaining(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        requestLimit: Int = CACHE_REQUEST_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Int = if (now < pausedUntil) 0 else current(now, windowMs).gamesLeft(limit, requestLimit)

    fun nextAvailableAt(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        requestLimit: Int = CACHE_REQUEST_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Long {
        val window = current(now, windowMs)
        val windowOpensAt = if (window.gamesLeft(limit, requestLimit) > 0) now else window.windowStart + windowMs
        return maxOf(pausedUntil, windowOpensAt)
    }

    private fun gamesLeft(limit: Int, requestLimit: Int): Int =
        minOf(limit - used, (requestLimit - requests) / REQUESTS_PER_NEW_GAME).coerceAtLeast(0)

    fun toJson(): String = JSONObject()
        .put("windowStart", windowStart)
        .put("used", used)
        .put("requests", requests)
        .put("pausedUntil", pausedUntil)
        .toString()

    companion object {
        fun fromJson(body: String?): BudgetWindow = runCatching {
            val json = JSONObject(body.orEmpty())
            BudgetWindow(
                json.optLong("windowStart", 0L),
                json.optInt("used", 0),
                json.optInt("requests", 0),
                json.optLong("pausedUntil", 0L)
            )
        }.getOrDefault(BudgetWindow())
    }
}

internal object CacheBudget {
    private val mutex = Mutex()

    suspend fun fits(db: AppDatabase, neededRequests: Int, now: Long = System.currentTimeMillis()): Boolean =
        mutex.withLock { load(db).fits(now, neededRequests) }

    suspend fun charge(db: AppDatabase, requests: Int, games: Int, now: Long = System.currentTimeMillis()) {
        if (requests == 0 && games == 0) return
        mutex.withLock { save(db, load(db).charge(now, requests, games)) }
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

    private suspend fun load(db: AppDatabase): BudgetWindow =
        BudgetWindow.fromJson(db.cacheDao().get(CacheKeys.CACHE_BUDGET)?.responseBody)

    private suspend fun save(db: AppDatabase, window: BudgetWindow) {
        db.cacheDao().upsert(CacheEntry(cacheKey = CacheKeys.CACHE_BUDGET, responseBody = window.toJson()))
    }
}
