package com.raofflineproxy

import com.raofflineproxy.ui.buildPatchedContent
import com.raofflineproxy.ui.buildPatchedDolphinContent
import com.raofflineproxy.ui.buildPatchedPpssppContent
import com.raofflineproxy.ui.buildRevertedContent
import com.raofflineproxy.ui.buildRevertedDolphinContent
import com.raofflineproxy.ui.buildRevertedPpssppContent
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManualPatcherTest {
    private val proxyAddress = "127.0.0.1:4321"

    @Test
    fun retroArchPatchAndRevertTransformsMatchManualRequirements() {
        val original = "cheevos_custom_host = \"\"\ncheevos_hardcore_mode_enable = \"true\""

        val patched = buildPatchedContent(original, proxyAddress)
        val reverted = buildRevertedContent(patched, restoreHardcore = false)

        assertTrue(patched.contains("cheevos_custom_host = \"$proxyAddress\""))
        assertTrue(patched.contains("cheevos_hardcore_mode_enable = \"false\""))
        assertTrue(reverted.contains("cheevos_custom_host = \"\""))
    }

    @Test
    fun dolphinPatchAndRevertTransformsMatchManualRequirements() {
        val original = "[Achievements]\nHostUrl = \nHardcoreEnabled = True"

        val patched = buildPatchedDolphinContent(original, proxyAddress)
        val reverted = buildRevertedDolphinContent(patched, restoreHardcore = false)

        assertTrue(patched.contains("HostUrl = $proxyAddress"))
        assertTrue(patched.contains("HardcoreEnabled = False"))
        assertTrue(reverted.contains("HostUrl = "))
    }

    @Test
    fun ppssppPatchAndRevertTransformsMatchManualRequirements() {
        val original = "[Achievements]\nAchievementsHost = \nAchievementsChallengeMode = True"

        val patched = buildPatchedPpssppContent(original, proxyAddress)
        val reverted = buildRevertedPpssppContent(patched, restoreHardcore = false)

        assertTrue(patched.contains("AchievementsHost = $proxyAddress"))
        assertTrue(patched.contains("AchievementsChallengeMode = False"))
        assertTrue(reverted.contains("AchievementsHost = "))
    }
}
