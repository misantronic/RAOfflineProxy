package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal const val CACHE_BUDGET_LIMIT = 100
internal const val CACHE_BUDGET_WINDOW_MS = 30L * 60 * 1000

internal data class BudgetWindow(val windowStart: Long = 0L, val used: Int = 0) {
    // A clock that jumped backwards starts a fresh window instead of blocking caching until it
    // catches up again.
    fun current(now: Long, windowMs: Long = CACHE_BUDGET_WINDOW_MS): BudgetWindow =
        if (now < windowStart || now - windowStart >= windowMs) BudgetWindow(now, 0) else this

    fun tryAcquire(
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
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Int = (limit - current(now, windowMs).used).coerceAtLeast(0)

    fun nextAvailableAt(
        now: Long,
        limit: Int = CACHE_BUDGET_LIMIT,
        windowMs: Long = CACHE_BUDGET_WINDOW_MS
    ): Long {
        val window = current(now, windowMs)
        return if (window.used < limit) now else window.windowStart + windowMs
    }

    fun toJson(): String = JSONObject()
        .put("windowStart", windowStart)
        .put("used", used)
        .toString()

    companion object {
        fun fromJson(body: String?): BudgetWindow = runCatching {
            val json = JSONObject(body.orEmpty())
            BudgetWindow(json.optLong("windowStart", 0L), json.optInt("used", 0))
        }.getOrDefault(BudgetWindow())
    }
}

internal object CacheBudget {
    private val mutex = Mutex()

    suspend fun tryAcquire(db: AppDatabase, now: Long = System.currentTimeMillis()): Boolean =
        mutex.withLock {
            val (granted, updated) = load(db).tryAcquire(now)
            if (granted) save(db, updated)
            granted
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
