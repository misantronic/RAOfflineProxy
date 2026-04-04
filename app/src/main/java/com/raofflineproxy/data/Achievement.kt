package com.raofflineproxy.data

data class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String?,
    val unlocked: Boolean,
    val unlockedHardcore: Boolean
)
