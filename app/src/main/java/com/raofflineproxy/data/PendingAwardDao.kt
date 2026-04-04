package com.raofflineproxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingAwardDao {
    @Query("SELECT * FROM pending_awards ORDER BY queuedAt ASC")
    fun observe(): Flow<List<PendingAward>>

    @Query("SELECT * FROM pending_awards ORDER BY queuedAt ASC")
    suspend fun getAll(): List<PendingAward>

    @Query("SELECT * FROM pending_awards ORDER BY queuedAt DESC LIMIT 1")
    suspend fun getLatest(): PendingAward?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(award: PendingAward)

    @Delete
    suspend fun delete(award: PendingAward)

    @Update
    suspend fun update(award: PendingAward)

    @Query("SELECT COUNT(*) FROM pending_awards")
    fun observeCount(): Flow<Int>
}
