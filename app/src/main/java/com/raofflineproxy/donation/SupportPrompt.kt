package com.raofflineproxy.donation

data class SupportPrompt(val milestone: Int, val syncedAwards: Int)

internal val SUPPORT_PROMPT_MILESTONES = listOf(10, 50, 250)

internal fun dueSupportPrompt(
    syncedAwards: Int,
    lastShownMilestone: Int,
    milestones: List<Int> = SUPPORT_PROMPT_MILESTONES
): SupportPrompt? = milestones
    .lastOrNull { it in (lastShownMilestone + 1)..syncedAwards }
    ?.let { SupportPrompt(it, syncedAwards) }
