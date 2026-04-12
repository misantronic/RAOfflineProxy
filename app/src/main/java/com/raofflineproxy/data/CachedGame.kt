package com.raofflineproxy.data

data class UnlockedAchievement(
    val id: Int,
    val title: String,
    val description: String?,
    val points: Int,
    val badgeUrl: String?
)

data class CachedGame(
    val gameId: String,
    val title: String,
    val user: String,
    val cachedAt: Long,
    val imageIconUrl: String?,
    val unlockedCount: Int = 0,
    val totalAchievements: Int = 0,
    val unlockedAchievements: List<UnlockedAchievement> = emptyList()
)
