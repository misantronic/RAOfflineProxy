package com.raofflineproxy.data

data class CachedGame(
    val gameId: String,
    val title: String,
    val user: String,
    val cachedAt: Long,
    val imageIconUrl: String?,
    val unlockedCount: Int = 0,
    val totalAchievements: Int = 0
)
