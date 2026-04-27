package com.raofflineproxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    @Query("SELECT * FROM api_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: CacheEntry)

    @Query("UPDATE api_cache SET responseBody = :responseBody, cachedAt = :cachedAt WHERE cacheKey = :cacheKey")
    suspend fun updateBody(cacheKey: String, responseBody: String, cachedAt: Long)

    suspend fun upsert(entry: CacheEntry) {
        insertIgnore(entry)
        updateBody(entry.cacheKey, entry.responseBody, entry.cachedAt)
    }

    @Query("DELETE FROM api_cache WHERE cachedAt < :before AND cacheKey NOT LIKE 'login2::%' AND cacheKey != 'ua::last'")
    suspend fun evictOlderThan(before: Long)

    @Query("SELECT * FROM api_cache WHERE cacheKey LIKE 'patch:%' ORDER BY firstCachedAt DESC")
    fun observePatchEntries(): Flow<List<CacheEntry>>

    @Query("SELECT * FROM api_cache WHERE cacheKey LIKE :prefix || '%' ORDER BY cachedAt DESC")
    fun observeByPrefix(prefix: String): Flow<List<CacheEntry>>

    @Query("SELECT * FROM api_cache WHERE cacheKey LIKE :prefix || '%' LIMIT 1")
    suspend fun getByPrefix(prefix: String): CacheEntry?

    @Query("SELECT * FROM api_cache WHERE cacheKey LIKE :prefix || '%'")
    suspend fun getAllByPrefix(prefix: String): List<CacheEntry>

    @Query("DELETE FROM api_cache WHERE cacheKey LIKE :prefix || '%'")
    suspend fun deleteByKeyPrefix(prefix: String)
}
