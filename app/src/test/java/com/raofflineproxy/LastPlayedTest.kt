package com.raofflineproxy

import com.raofflineproxy.data.CacheEntrySummary
import com.raofflineproxy.proxy.recentlyPlayedGameIds
import org.junit.Assert.assertEquals
import org.junit.Test

class LastPlayedTest {

    private fun entry(cacheKey: String, cachedAt: Long) =
        CacheEntrySummary(cacheKey = cacheKey, cachedAt = cachedAt, firstCachedAt = cachedAt)

    @Test
    fun recentlyPlayedGameIds_keepsEntriesInsideWindow() {
        val entries = listOf(entry("lastplayed:10", 5_000), entry("lastplayed:20", 9_000))
        assertEquals(setOf(10, 20), recentlyPlayedGameIds(entries, since = 5_000))
    }

    @Test
    fun recentlyPlayedGameIds_dropsEntriesOlderThanWindow() {
        val entries = listOf(entry("lastplayed:10", 4_999), entry("lastplayed:20", 9_000))
        assertEquals(setOf(20), recentlyPlayedGameIds(entries, since = 5_000))
    }

    @Test
    fun recentlyPlayedGameIds_ignoresUnparseableKeys() {
        val entries = listOf(entry("lastplayed:", 9_000), entry("lastplayed:abc", 9_000))
        assertEquals(emptySet<Int>(), recentlyPlayedGameIds(entries, since = 5_000))
    }

    @Test
    fun recentlyPlayedGameIds_emptyWhenNothingPlayed() {
        assertEquals(emptySet<Int>(), recentlyPlayedGameIds(emptyList(), since = 5_000))
    }
}
