package com.raofflineproxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingAwardDao {
    @Query("SELECT * FROM pending_awards WHERE status = :status ORDER BY queuedAt DESC")
    fun observeByStatus(status: String = PENDING_AWARD_STATUS_PENDING): Flow<List<PendingAward>>

    @Query("SELECT * FROM pending_awards ORDER BY queuedAt ASC")
    suspend fun getAll(): List<PendingAward>

    @Query("SELECT * FROM pending_awards WHERE status = :status ORDER BY queuedAt ASC")
    suspend fun getAllByStatus(status: String = PENDING_AWARD_STATUS_PENDING): List<PendingAward>

    @Query("SELECT * FROM pending_awards WHERE status = :status ORDER BY queuedAt DESC LIMIT 1")
    suspend fun getLatestByStatus(status: String = PENDING_AWARD_STATUS_PENDING): PendingAward?

    @Query("SELECT * FROM pending_awards ORDER BY queuedAt DESC LIMIT 1")
    suspend fun getLatest(): PendingAward?

    @Query("SELECT EXISTS(SELECT 1 FROM pending_awards WHERE achievementId = :id AND status = :status)")
    suspend fun existsByAchievementIdAndStatus(
        id: Int,
        status: String = PENDING_AWARD_STATUS_PENDING
    ): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pending_awards WHERE status = :status)")
    suspend fun existsByStatus(status: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(award: PendingAward)

    @Delete
    suspend fun delete(award: PendingAward)

    @Query("DELETE FROM pending_awards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_awards WHERE status IN (:statuses)")
    suspend fun deleteByStatuses(statuses: List<String>)

    @Update
    suspend fun update(award: PendingAward)
}
