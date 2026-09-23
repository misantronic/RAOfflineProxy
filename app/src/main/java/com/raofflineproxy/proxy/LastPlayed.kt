package com.raofflineproxy.proxy

import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheEntrySummary
import com.raofflineproxy.data.CacheKeys
import java.util.concurrent.ConcurrentHashMap

// Clients ping every couple of minutes per game; the exact second a game was last touched
// never matters, so collapse the writes instead of hitting the DB on every request.
private const val LAST_PLAYED_WRITE_INTERVAL_MS = 60_000L

private val lastPlayedWrites = ConcurrentHashMap<Int, Long>()

internal suspend fun recordGamePlayed(
    db: AppDatabase,
    gameId: Int,
    now: Long = System.currentTimeMillis()
) {
    if (gameId <= 0) return
    val previous = lastPlayedWrites[gameId]
    if (previous != null && now - previous < LAST_PLAYED_WRITE_INTERVAL_MS) return
    lastPlayedWrites[gameId] = now
    db.cacheDao().upsert(
        CacheEntry(
            cacheKey = CacheKeys.lastPlayed(gameId),
            responseBody = now.toString(),
            cachedAt = now
        )
    )
}

internal suspend fun loadRecentlyPlayedGameIds(db: AppDatabase, since: Long): Set<Int> =
    recentlyPlayedGameIds(db.cacheDao().getAllSummariesByPrefix(CacheKeys.PREFIX_LAST_PLAYED), since)

internal fun recentlyPlayedGameIds(entries: List<CacheEntrySummary>, since: Long): Set<Int> =
    entries.asSequence()
        .filter { entry -> entry.cachedAt >= since }
        .mapNotNull { entry -> CacheKeys.parseGameIdFromLastPlayedKey(entry.cacheKey) }
        .toSet()
