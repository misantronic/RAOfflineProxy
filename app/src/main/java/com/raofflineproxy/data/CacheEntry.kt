package com.raofflineproxy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "api_cache",
    indices = [Index(value = ["cacheKey"], unique = true)]
)
data class CacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cacheKey: String,
    val responseBody: String,
    val sourceRomPath: String? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    val firstCachedAt: Long = System.currentTimeMillis()
)

data class CacheEntrySummary(
    val id: Long = 0,
    val cacheKey: String,
    val sourceRomPath: String? = null,
    val cachedAt: Long,
    val firstCachedAt: Long
) {
    fun toCacheEntry(responseBody: String): CacheEntry = CacheEntry(
        id = id,
        cacheKey = cacheKey,
        responseBody = responseBody,
        sourceRomPath = sourceRomPath,
        cachedAt = cachedAt,
        firstCachedAt = firstCachedAt
    )
}
