package com.raofflineproxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    @Query("SELECT id, cacheKey, sourceRomPath, cachedAt, firstCachedAt FROM api_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getSummary(key: String): CacheEntrySummary?

    @Query("SELECT substr(responseBody, :offset, :length) FROM api_cache WHERE id = :id LIMIT 1")
    suspend fun getResponseBodyChunkById(id: Long, offset: Int, length: Int): String?

    suspend fun get(key: String): CacheEntry? =
        getSummary(key)?.withResponseBody(this)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: CacheEntry)

    @Query(
        "UPDATE api_cache SET responseBody = :responseBody, sourceRomPath = COALESCE(:sourceRomPath, sourceRomPath), cachedAt = :cachedAt WHERE cacheKey = :cacheKey"
    )
    suspend fun updateBody(
        cacheKey: String,
        responseBody: String,
        sourceRomPath: String?,
        cachedAt: Long
    )

    suspend fun upsert(entry: CacheEntry) {
        insertIgnore(entry)
        updateBody(entry.cacheKey, entry.responseBody, entry.sourceRomPath, entry.cachedAt)
    }

    @Query("DELETE FROM api_cache WHERE cachedAt < :before AND cacheKey NOT LIKE 'login2::%' AND cacheKey != 'ua::last'")
    suspend fun evictOlderThan(before: Long)

    @Query("SELECT id, cacheKey, sourceRomPath, cachedAt, firstCachedAt FROM api_cache WHERE cacheKey LIKE 'patch:%' ORDER BY firstCachedAt DESC")
    fun observePatchEntrySummaries(): Flow<List<CacheEntrySummary>>

    @Query("SELECT id, cacheKey, sourceRomPath, cachedAt, firstCachedAt FROM api_cache WHERE cacheKey LIKE 'unlocks:%'")
    fun observeUnlockSummaries(): Flow<List<CacheEntrySummary>>

    suspend fun bodyForSummary(summary: CacheEntrySummary): String? =
        summary.withResponseBody(this)?.responseBody

    @Query("SELECT * FROM api_cache WHERE cacheKey LIKE :prefix || '%' ORDER BY cachedAt DESC")
    fun observeByPrefix(prefix: String): Flow<List<CacheEntry>>

    @Query("SELECT id, cacheKey, sourceRomPath, cachedAt, firstCachedAt FROM api_cache WHERE cacheKey LIKE :prefix || '%' LIMIT 1")
    suspend fun getSummaryByPrefix(prefix: String): CacheEntrySummary?

    suspend fun getByPrefix(prefix: String): CacheEntry? =
        getSummaryByPrefix(prefix)?.withResponseBody(this)

    @Query("SELECT id, cacheKey, sourceRomPath, cachedAt, firstCachedAt FROM api_cache WHERE cacheKey LIKE :prefix || '%'")
    suspend fun getAllSummariesByPrefix(prefix: String): List<CacheEntrySummary>

    suspend fun getAllByPrefix(prefix: String): List<CacheEntry> =
        getAllSummariesByPrefix(prefix).mapNotNull { entry -> entry.withResponseBody(this) }

    @Query("DELETE FROM api_cache WHERE cacheKey LIKE :prefix || '%'")
    suspend fun deleteByKeyPrefix(prefix: String)

    @Query("DELETE FROM api_cache WHERE cacheKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("UPDATE api_cache SET cacheKey = :newKey WHERE cacheKey = :oldKey")
    suspend fun updateCacheKey(oldKey: String, newKey: String)
}

private const val RESPONSE_BODY_CHUNK_SIZE = 32_768

private suspend fun CacheEntrySummary.withResponseBody(cacheDao: CacheDao): CacheEntry? {
    val responseBody = buildString {
        var sqliteOffset = 1
        while (true) {
            val chunk = cacheDao.getResponseBodyChunkById(id, sqliteOffset, RESPONSE_BODY_CHUNK_SIZE)
                ?: return null
            append(chunk)
            if (chunk.length < RESPONSE_BODY_CHUNK_SIZE) break
            sqliteOffset += chunk.length
        }
    }
    return toCacheEntry(responseBody)
}
