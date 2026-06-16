package com.raofflineproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

const val PENDING_AWARD_STATUS_PENDING = "pending"
const val PENDING_AWARD_STATUS_DELETED = "deleted"
const val PENDING_AWARD_STATUS_STALE = "stale"
const val PENDING_AWARD_STATUS_FLUSHED = "flushed"

@Entity(tableName = "pending_awards")
data class PendingAward(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val achievementId: Int,
    val queryString: String,
    val requestBody: String,
    val userAgent: String,
    val queuedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    val status: String = PENDING_AWARD_STATUS_PENDING,
    // Anti-tamper hash chain fields
    val payloadHash: String = "",
    val prevHash: String = "",
    val signature: String = "",
    val signedAt: Long = 0L,
    // Display metadata snapshot — populated at queue time so history survives cache clears
    val snapshotGameTitle: String? = null,
    val snapshotAchievementTitle: String? = null,
    val snapshotPoints: Int = 0,
    val snapshotBadgeUrl: String? = null,
    val snapshotGameIconUrl: String? = null
)
