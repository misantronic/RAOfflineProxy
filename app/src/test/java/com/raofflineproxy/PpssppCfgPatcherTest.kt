package com.raofflineproxy

import com.raofflineproxy.ui.buildPatchedPpssppContent
import com.raofflineproxy.ui.buildRevertedPpssppContent
import com.raofflineproxy.ui.detectPpssppHardcoreEnabled
import com.raofflineproxy.ui.isPpssppPatchedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpssppCfgPatcherTest {
    private val proxyAddress = "127.0.0.1:4321"

    @Test
    fun buildPatchedPpssppContent_setsAchievementsHost() {
        val cfg = """
            [Achievements]
            AchievementsHost =
            AchievementsChallengeMode = True
        """.trimIndent()

        val patched = buildPatchedPpssppContent(cfg, proxyAddress)

        assertTrue(patched.contains("AchievementsHost = $proxyAddress"))
        assertTrue(patched.contains("AchievementsChallengeMode = False"))
    }

    @Test
    fun buildPatchedPpssppContent_appendsAchievementsSectionWhenMissing() {
        val patched = buildPatchedPpssppContent("[System]\nLanguage = en_US", proxyAddress)

        assertTrue(patched.contains("[Achievements]"))
        assertTrue(patched.contains("AchievementsHost = $proxyAddress"))
        assertTrue(patched.contains("AchievementsChallengeMode = False"))
    }

    @Test
    fun buildPatchedPpssppContent_isIdempotent() {
        val cfg = """
            [Achievements]
            AchievementsHost = $proxyAddress
            AchievementsChallengeMode = False
        """.trimIndent()

        assertEquals(cfg, buildPatchedPpssppContent(cfg, proxyAddress))
    }

    @Test
    fun buildRevertedPpssppContent_clearsAchievementsHost() {
        val cfg = """
            [Achievements]
            AchievementsHost = $proxyAddress
            AchievementsChallengeMode = False
        """.trimIndent()

        val reverted = buildRevertedPpssppContent(cfg)

        assertTrue(reverted.contains("AchievementsHost = "))
        assertTrue(reverted.contains("AchievementsChallengeMode = False"))
        assertFalse(isPpssppPatchedContent(reverted, proxyAddress))
    }

    @Test
    fun buildRevertedPpssppContent_restoresHardcoreWhenRequested() {
        val cfg = """
            [Achievements]
            AchievementsHost = $proxyAddress
            AchievementsChallengeMode = False
        """.trimIndent()

        val reverted = buildRevertedPpssppContent(cfg, restoreHardcore = true)

        assertTrue(reverted.contains("AchievementsChallengeMode = True"))
    }

    @Test
    fun detectPpssppHardcoreEnabled_trueWhenEnabled() {
        val cfg = """
            [Achievements]
            AchievementsChallengeMode = True
        """.trimIndent()

        assertTrue(detectPpssppHardcoreEnabled(cfg))
    }

    @Test
    fun detectPpssppHardcoreEnabled_falseWhenDisabled() {
        val cfg = """
            [Achievements]
            AchievementsChallengeMode = False
        """.trimIndent()

        assertFalse(detectPpssppHardcoreEnabled(cfg))
    }

    @Test
    fun isPpssppPatchedContent_trueWhenPatched() {
        assertTrue(isPpssppPatchedContent("[Achievements]\nAchievementsHost = $proxyAddress", proxyAddress))
    }

    @Test
    fun isPpssppPatchedContent_falseWhenDifferentHost() {
        assertFalse(isPpssppPatchedContent("[Achievements]\nAchievementsHost = other", proxyAddress))
    }
}
