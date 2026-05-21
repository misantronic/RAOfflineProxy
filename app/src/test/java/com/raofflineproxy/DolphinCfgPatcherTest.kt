package com.raofflineproxy

import com.raofflineproxy.ui.buildPatchedDolphinContent
import com.raofflineproxy.ui.buildRevertedDolphinContent
import com.raofflineproxy.ui.detectDolphinHardcoreEnabled
import com.raofflineproxy.ui.dolphinBackupFileFor
import com.raofflineproxy.ui.ensureDolphinBackupFileExists
import com.raofflineproxy.ui.extractDolphinCredentials
import com.raofflineproxy.ui.ImportedCredentials
import com.raofflineproxy.ui.isDolphinPatchedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DolphinCfgPatcherTest {
    private val proxyAddress = "127.0.0.1:4321"

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectDolphinHardcoreEnabled_trueWhenEnabled() {
        val cfg = """
            [Achievements]
            HostUrl =
            HardcoreEnabled = True
        """.trimIndent()

        assertTrue(detectDolphinHardcoreEnabled(cfg))
    }

    @Test
    fun detectDolphinHardcoreEnabled_falseWhenDisabled() {
        val cfg = """
            [Achievements]
            HardcoreEnabled = False
        """.trimIndent()

        assertFalse(detectDolphinHardcoreEnabled(cfg))
    }

    @Test
    fun buildPatchedDolphinContent_setsHostAndDisablesHardcore() {
        val cfg = """
            [Achievements]
            HostUrl =
            HardcoreEnabled = True
        """.trimIndent()

        val patched = buildPatchedDolphinContent(cfg, proxyAddress)

        assertTrue(patched.contains("HostUrl = $proxyAddress"))
        assertTrue(patched.contains("HardcoreEnabled = False"))
    }

    @Test
    fun extractDolphinCredentials_returnsUsernameAndToken() {
        val cfg = """
            [Achievements]
            Username = player1
            ApiToken = secret-token
        """.trimIndent()

        val credentials = extractDolphinCredentials(cfg)

        assertEquals("player1", credentials?.username)
        assertTrue(credentials is ImportedCredentials.Token)
        assertEquals("secret-token", (credentials as ImportedCredentials.Token).token)
    }

    @Test
    fun extractDolphinCredentials_nullWhenMissingToken() {
        val cfg = """
            [Achievements]
            Username = player1
        """.trimIndent()

        assertEquals(null, extractDolphinCredentials(cfg))
    }

    @Test
    fun buildPatchedDolphinContent_appendsAchievementsSectionWhenMissing() {
        val patched = buildPatchedDolphinContent("[Core]\nCPUThread = True", proxyAddress)

        assertTrue(patched.contains("[Achievements]"))
        assertTrue(patched.contains("HostUrl = $proxyAddress"))
        assertTrue(patched.contains("HardcoreEnabled = False"))
    }

    @Test
    fun buildPatchedDolphinContent_idempotent() {
        val cfg = """
            [Achievements]
            HostUrl = $proxyAddress
            HardcoreEnabled = False
        """.trimIndent()

        assertEquals(cfg, buildPatchedDolphinContent(cfg, proxyAddress))
    }

    @Test
    fun buildRevertedDolphinContent_clearsHostAndRestoresHardcore() {
        val cfg = """
            [Achievements]
            HostUrl = $proxyAddress
            HardcoreEnabled = False
        """.trimIndent()

        val reverted = buildRevertedDolphinContent(cfg, restoreHardcore = true)

        assertTrue(reverted.contains("HostUrl = "))
        assertTrue(reverted.contains("HardcoreEnabled = True"))
    }

    @Test
    fun buildRevertedDolphinContent_keepsHardcoreDisabledWhenRequested() {
        val cfg = """
            [Achievements]
            HostUrl = $proxyAddress
            HardcoreEnabled = False
        """.trimIndent()

        val reverted = buildRevertedDolphinContent(cfg, restoreHardcore = false)

        assertTrue(reverted.contains("HostUrl = "))
        assertTrue(reverted.contains("HardcoreEnabled = False"))
    }

    @Test
    fun isDolphinPatchedContent_trueWhenPatched() {
        assertTrue(isDolphinPatchedContent("[Achievements]\nHostUrl = $proxyAddress", proxyAddress))
    }

    @Test
    fun isDolphinPatchedContent_falseWhenDifferentHost() {
        assertFalse(isDolphinPatchedContent("[Achievements]\nHostUrl = other", proxyAddress))
    }

    @Test
    fun isDolphinPatchedContent_falseWhenHostCleared() {
        assertFalse(isDolphinPatchedContent("[Achievements]\nHostUrl = ", proxyAddress))
    }

    @Test
    fun buildRevertedDolphinContent_isNotDetectedAsPatched() {
        val reverted = buildRevertedDolphinContent(
            "[Achievements]\nHostUrl = $proxyAddress\nHardcoreEnabled = False",
            restoreHardcore = false
        )

        assertFalse(isDolphinPatchedContent(reverted, proxyAddress))
    }

    @Test
    fun ensureDolphinBackupFileExists_createsSiblingBackupWhenMissing() {
        val target = tempFolder.newFile("RetroAchievements.ini")
        val original = "[Achievements]\nHostUrl ="

        ensureDolphinBackupFileExists(target, original)

        assertEquals(original, dolphinBackupFileFor(target).readText())
    }

    @Test
    fun ensureDolphinBackupFileExists_doesNotOverwriteExistingBackup() {
        val target = tempFolder.newFile("RetroAchievements.ini")
        val backup = dolphinBackupFileFor(target)
        backup.writeText("existing backup")

        ensureDolphinBackupFileExists(target, "new content")

        assertEquals("existing backup", backup.readText())
    }
}
