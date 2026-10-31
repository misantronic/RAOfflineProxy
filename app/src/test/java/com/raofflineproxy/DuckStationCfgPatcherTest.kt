package com.raofflineproxy

import com.raofflineproxy.ui.buildPatchedDuckStationContent
import com.raofflineproxy.ui.buildRevertedDuckStationContent
import com.raofflineproxy.ui.ImportedCredentials
import com.raofflineproxy.ui.detectDuckStationHardcoreEnabled
import com.raofflineproxy.ui.extractDuckStationCredentials
import com.raofflineproxy.ui.isDuckStationPatchedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckStationCfgPatcherTest {
    private val proxyBase = "http://127.0.0.1:4321"

    private val settings = """
        [Interface]
        SetupWizardComplete = true

        [Cheevos]
        ChallengeMode = true
        Enabled = true
        Token = secret
        Username = player

        [Main]
        EmulationSpeed = 1
    """.trimIndent()

    @Test
    fun patch_addsHostAndDisablesHardcore() {
        val patched = buildPatchedDuckStationContent(settings, proxyBase)

        assertTrue(patched.contains("Host = $proxyBase"))
        assertTrue(patched.contains("ChallengeMode = false"))
        assertTrue(isDuckStationPatchedContent(patched, proxyBase))
    }

    @Test
    fun patch_keepsHostInsideCheevosSection() {
        val patched = buildPatchedDuckStationContent(settings, proxyBase)

        val cheevos = patched.substringAfter("[Cheevos]").substringBefore("[Main]")
        assertTrue(cheevos.contains("Host = $proxyBase"))
        assertTrue(patched.contains("\n\n[Main]"))
    }

    @Test
    fun patch_leavesOtherKeysUntouched() {
        val patched = buildPatchedDuckStationContent(settings, proxyBase)

        assertTrue(patched.contains("Token = secret"))
        assertTrue(patched.contains("Username = player"))
        assertTrue(patched.contains("EmulationSpeed = 1"))
    }

    @Test
    fun patch_isIdempotent() {
        val once = buildPatchedDuckStationContent(settings, proxyBase)

        assertEquals(once, buildPatchedDuckStationContent(once, proxyBase))
    }

    @Test
    fun patch_replacesExistingHost() {
        val cfg = "[Cheevos]\nHost = https://example.org\nChallengeMode = false"

        val patched = buildPatchedDuckStationContent(cfg, proxyBase)

        assertEquals(1, Regex("(?m)^Host = ").findAll(patched).count())
        assertTrue(patched.contains("Host = $proxyBase"))
    }

    @Test
    fun patch_appendsCheevosSectionWhenMissing() {
        val patched = buildPatchedDuckStationContent("[Main]\nEmulationSpeed = 1", proxyBase)

        assertTrue(patched.contains("[Cheevos]"))
        assertTrue(patched.contains("Host = $proxyBase"))
        assertTrue(patched.contains("ChallengeMode = false"))
    }

    @Test
    fun revert_clearsHostAndKeepsHardcoreOffByDefault() {
        val reverted = buildRevertedDuckStationContent(buildPatchedDuckStationContent(settings, proxyBase))

        assertFalse(isDuckStationPatchedContent(reverted, proxyBase))
        assertTrue(reverted.contains("ChallengeMode = false"))
    }

    @Test
    fun revert_restoresHardcoreWhenItWasEnabled() {
        val reverted = buildRevertedDuckStationContent(
            buildPatchedDuckStationContent(settings, proxyBase),
            restoreHardcore = true
        )

        assertTrue(reverted.contains("ChallengeMode = true"))
    }

    @Test
    fun revert_isIdempotent() {
        val once = buildRevertedDuckStationContent(buildPatchedDuckStationContent(settings, proxyBase))

        assertEquals(once, buildRevertedDuckStationContent(once))
    }

    @Test
    fun detectHardcore_readsChallengeMode() {
        assertTrue(detectDuckStationHardcoreEnabled(settings))
        assertFalse(detectDuckStationHardcoreEnabled("[Cheevos]\nChallengeMode = false"))
        assertFalse(detectDuckStationHardcoreEnabled("[Main]\nChallengeMode = true"))
        assertFalse(detectDuckStationHardcoreEnabled(""))
    }

    @Test
    fun isPatched_ignoresHostOutsideCheevosSection() {
        assertFalse(isDuckStationPatchedContent("[Logging]\nHost = $proxyBase", proxyBase))
    }

    @Test
    fun extractCredentials_readsUsernameAndToken() {
        assertEquals(
            ImportedCredentials.Token(username = "player", token = "secret"),
            extractDuckStationCredentials(settings)
        )
    }

    @Test
    fun extractCredentials_returnsNullWhenLoggedOut() {
        assertEquals(null, extractDuckStationCredentials("[Cheevos]\nUsername =\nToken ="))
        assertEquals(null, extractDuckStationCredentials("[Main]\nUsername = x\nToken = y"))
    }
}
