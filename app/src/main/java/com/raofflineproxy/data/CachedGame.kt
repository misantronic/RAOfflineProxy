package com.raofflineproxy.data

data class CachedAchievement(
    val id: Int,
    val title: String,
    val description: String?,
    val points: Int,
    val badgeUrl: String?,
    val unlocked: Boolean
)

data class CachedGame(
    val gameId: String,
    val title: String,
    val user: String,
    val consoleId: Int = 0,
    val sourceRomPath: String? = null,
    val cachedAt: Long,
    val imageIconUrl: String?,
    val unlockedCount: Int = 0,
    val pendingAwardCount: Int = 0,
    val totalAchievements: Int = 0,
    val achievements: List<CachedAchievement> = emptyList()
)
