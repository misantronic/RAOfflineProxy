package com.raofflineproxy

import com.raofflineproxy.donation.DonationLinks
import com.raofflineproxy.donation.SupportPrompt
import com.raofflineproxy.donation.dueSupportPrompt
import com.raofflineproxy.usage.UsageCounterKeys
import com.raofflineproxy.usage.UsageCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportPromptTest {

    @Test
    fun nothingIsDue_belowTheFirstMilestone() {
        assertNull(dueSupportPrompt(syncedAwards = 9, lastShownMilestone = 0))
    }

    @Test
    fun firstMilestone_isDueOnceReached() {
        assertEquals(SupportPrompt(10, 12), dueSupportPrompt(syncedAwards = 12, lastShownMilestone = 0))
    }

    @Test
    fun shownMilestone_isNotDueAgain() {
        assertNull(dueSupportPrompt(syncedAwards = 49, lastShownMilestone = 10))
    }

    @Test
    fun skippedMilestones_askOnlyOnce() {
        assertEquals(SupportPrompt(250, 300), dueSupportPrompt(syncedAwards = 300, lastShownMilestone = 0))
        assertNull(dueSupportPrompt(syncedAwards = 300, lastShownMilestone = 250))
    }

    @Test
    fun nothingIsDue_afterTheLastMilestone() {
        assertNull(dueSupportPrompt(syncedAwards = 100_000, lastShownMilestone = 250))
    }

    @Test
    fun quickAmounts_areOneTimeLinks() {
        assertEquals(listOf(300, 500, 1000), DonationLinks.QUICK_ONE_TIME.map { it.amountCents })
        assertEquals(DonationLinks.QUICK_ONE_TIME, DonationLinks.QUICK_ONE_TIME.filter { it in DonationLinks.ONE_TIME })
    }

    @Test
    fun featureCounters_areReported() {
        val counters = UsageCounters()
            .withCount(UsageCounterKeys.FEATURE_SUPPORT_OPENED)
            .withCount(UsageCounterKeys.FEATURE_SUPPORT_OPENED)
            .withCount(UsageCounterKeys.FEATURE_SUPPORT_STARTED)

        assertEquals(
            mapOf(UsageCounterKeys.FEATURE_SUPPORT_OPENED to 2L, UsageCounterKeys.FEATURE_SUPPORT_STARTED to 1L),
            counters.reportable()
        )
    }
}
