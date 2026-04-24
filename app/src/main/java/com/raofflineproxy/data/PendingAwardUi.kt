package com.raofflineproxy.data

data class PendingAwardUi(
    val id: Long,
    val achievementId: Int,
    val queryString: String,
    val requestBody: String,
    val userAgent: String,
    val gameTitle: String,
    val gameIconUrl: String?,
    val achievementTitle: String,
    val queuedAt: Long,
    val points: Int,
    val badgeUrl: String?,
    val hardcore: Boolean,
    val retryCount: Int,
    val lastError: String? = null,
    val payloadHash: String,
    val prevHash: String,
    val signature: String,
    val signedAt: Long
)
