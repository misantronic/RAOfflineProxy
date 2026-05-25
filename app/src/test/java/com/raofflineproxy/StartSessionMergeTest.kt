package com.raofflineproxy

import com.raofflineproxy.data.PENDING_AWARD_STATUS_DELETED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_PENDING
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.proxy.mergeStartSessionUnlockIds
import org.junit.Assert.assertEquals
import org.junit.Test

class StartSessionMergeTest {

    private fun award(
        achievementId: Int,
        user: String = "player",
        hardcore: Int = 0,
        status: String = PENDING_AWARD_STATUS_PENDING
    ) = PendingAward(
        achievementId = achievementId,
        queryString = "/dorequest.php?r=awardachievement&a=$achievementId&u=$user&h=$hardcore",
        requestBody = "a=$achievementId&u=$user&h=$hardcore",
        userAgent = "rcheevos/11.4.0",
        status = status
    )

    @Test
    fun mergeStartSessionUnlockIds_keepsCachedUnlocksWhenNoPendingAwards() {
        val result = mergeStartSessionUnlockIds(
            cachedUnlockIds = listOf(11, 101000001, 22),
            pendingAwards = emptyList(),
            achievementGameIds = emptyMap(),
            gameId = 42,
            user = "player"
        )

        assertEquals(listOf(11, 22), result)
    }

    @Test
    fun mergeStartSessionUnlockIds_addsPendingAwardsForSameGameAndUser() {
        val result = mergeStartSessionUnlockIds(
            cachedUnlockIds = listOf(11),
            pendingAwards = listOf(award(22), award(33)),
            achievementGameIds = mapOf(22 to 42, 33 to 42),
            gameId = 42,
            user = "player"
        )

        assertEquals(listOf(11, 22, 33), result)
    }

    @Test
    fun mergeStartSessionUnlockIds_deduplicatesCachedAndPendingIds() {
        val result = mergeStartSessionUnlockIds(
            cachedUnlockIds = listOf(11, 22),
            pendingAwards = listOf(award(22), award(33)),
            achievementGameIds = mapOf(22 to 42, 33 to 42),
            gameId = 42,
            user = "player"
        )

        assertEquals(listOf(11, 22, 33), result)
    }

    @Test
    fun mergeStartSessionUnlockIds_excludesOtherUsersGamesAndStatuses() {
        val result = mergeStartSessionUnlockIds(
            cachedUnlockIds = emptyList(),
            pendingAwards = listOf(
                award(22, user = "player"),
                award(33, user = "other"),
                award(44, user = "player", status = PENDING_AWARD_STATUS_DELETED),
                award(55, user = "player")
            ),
            achievementGameIds = mapOf(22 to 42, 33 to 42, 44 to 42, 55 to 99),
            gameId = 42,
            user = "player"
        )

        assertEquals(listOf(22), result)
    }

    @Test
    fun mergeStartSessionUnlockIds_excludesHardcorePendingAwards() {
        val result = mergeStartSessionUnlockIds(
            cachedUnlockIds = emptyList(),
            pendingAwards = listOf(award(22, hardcore = 1), award(33, hardcore = 0)),
            achievementGameIds = mapOf(22 to 42, 33 to 42),
            gameId = 42,
            user = "player"
        )

        assertEquals(listOf(33), result)
    }
}
