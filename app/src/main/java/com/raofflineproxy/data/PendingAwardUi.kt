package com.raofflineproxy.data

data class PendingAwardUi(
    val gameTitle: String,
    val gameIconUrl: String?,
    val achievementTitle: String,
    val queuedAt: Long,
    val points: Int,
    val badgeUrl: String?,
    val hardcore: Boolean,
    val lastError: String? = null
)
