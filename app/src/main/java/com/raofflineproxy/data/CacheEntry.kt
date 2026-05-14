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
