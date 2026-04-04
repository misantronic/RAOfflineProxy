package com.raofflineproxy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_awards",
    indices = [Index(value = ["achievementId"], unique = true)]
)
data class PendingAward(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val achievementId: Int,
    val queryString: String,
    val requestBody: String,
    val userAgent: String,
    val queuedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    // Anti-tamper hash chain fields
    val payloadHash: String = "",
    val prevHash: String = "",
    val signature: String = "",
    val signedAt: Long = 0L
)
