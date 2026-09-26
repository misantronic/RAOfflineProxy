package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal const val CACHE_BUDGET_LIMIT = 100
internal const val CACHE_LOOKUP_LIMIT = 300
internal const val CACHE_BUDGET_WINDOW_MS = 30L * 60 * 1000

/** One budget window: [used] counts games cached, [lookups] counts game id lookups, including
 *  those for ROMs RetroAchievements doesn't know. A window closes when either runs out. */
internal data class BudgetWindow(val windowStart: Long = 0L, val used: Int = 0, val lookups: Int = 0) {
    // A clock that jumped backwards starts a fresh window instead of blocking caching until it
    // catches up again.
    fun current(now: Long, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow =
        if (now < windowStart || now - windowStart >= windowMs) BudgetWindow(now) else this

    fun tryAcquireLookup(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        lookupLimit: Int = CACHE_LOOKUP_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Pair<Boolean, BudgetWindow> {
        val window = current(now, windowMs)
        return if (window.isOpen(limit, lookupLimit)) true to window.copy(lookups = window.lookups + 1) else false to window
    }

    fun tryAcquireGame(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Pair<Boolean, BudgetWindow> {
        val window = current(now, windowMs)
        return if (window.used < limit) true to window.copy(used = window.used + 1) else false to window
    }

    fun remaining(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        lookupLimit: Int = CACHE_LOOKUP_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Int {
        val window = current(now, windowMs)
        return if (window.isOpen(limit, lookupLimit)) limit - window.used else 0
    }

    fun nextAvailableAt(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        lookupLimit: Int = CACHE_LOOKUP_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Long {
        val window = current(now, windowMs)
        return if (window.isOpen(limit, lookupLimit)) now else window.windowStart + windowMs
    }

    private fun isOpen(limit: Int, lookupLimit: Int): Boolean = used < limit && lookups < lookupLimit

    fun toJson(): String = JSONObject()
        .put("windowStart", windowStart)
        .put("used", used)
        .put("lookups", lookups)
        .toString()

    companion object {
        fun fromJson(body: String?): BudgetWindow = runCatching {
            val json = JSONObject(body.orEmpty())
            BudgetWindow(json.optLong("windowStart", 0L), json.optInt("used", 0), json.optInt("lookups", 0))
        }.getOrDefault(BudgetWindow())
    }
}

internal object CacheBudget {
    private val mutex = Mutex()

    suspend fun tryAcquireLookup(db: AppDatabase, now: Long = System.currentTimeMillis()): Boolean =
        acquire(db) { it.tryAcquireLookup(now) }

    suspend fun tryAcquireGame(db: AppDatabase, now: Long = System.currentTimeMillis()): Boolean =
        acquire(db) { it.tryAcquireGame(now) }

    suspend fun remaining(db: AppDatabase, now: Long = System.currentTimeMillis()): Int =
        mutex.withLock { load(db).remaining(now) }

    suspend fun nextAvailableAt(db: AppDatabase, now: Long = System.currentTimeMillis()): Long =
        mutex.withLock { load(db).nextAvailableAt(now) }

    private suspend fun acquire(db: AppDatabase, attempt: (BudgetWindow) -> Pair<Boolean, BudgetWindow>): Boolean =
        mutex.withLock {
            val (granted, updated) = attempt(load(db))
            if (granted) save(db, updated)
            granted
        }

    private suspend fun load(db: AppDatabase): BudgetWindow =
        BudgetWindow.fromJson(db.cacheDao().get(CacheKeys.CACHE_BUDGET)?.responseBody)

    private suspend fun save(db: AppDatabase, window: BudgetWindow) {
        db.cacheDao().upsert(CacheEntry(cacheKey = CacheKeys.CACHE_BUDGET, responseBody = window.toJson()))
    }
}
