package com.raofflineproxy

import com.raofflineproxy.ui.backupFileFor
import com.raofflineproxy.ui.buildPatchedContent
import com.raofflineproxy.ui.buildRevertedContent
import com.raofflineproxy.ui.detectHardcoreEnabled
import com.raofflineproxy.ui.ensureBackupFileExists
import com.raofflineproxy.ui.extractRetroArchCredentials
import com.raofflineproxy.ui.isPatchedContent
import com.raofflineproxy.ui.patchManualEditInstructions
import com.raofflineproxy.ui.revertManualEditInstructions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetroArchCfgPatcherTest {
    private val proxyAddress = "127.0.0.1:4321"

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectHardcoreEnabled_trueWhenEnabled() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_hardcore_mode_enable = "true"
            cheevos_custom_host = ""
        """.trimIndent()

        assertTrue(detectHardcoreEnabled(cfg))
    }

    @Test
    fun detectHardcoreEnabled_falseWhenDisabled() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        assertFalse(detectHardcoreEnabled(cfg))
    }

    @Test
    fun detectHardcoreEnabled_falseWhenMissing() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_custom_host = ""
        """.trimIndent()

        assertFalse(detectHardcoreEnabled(cfg))
    }

    @Test
    fun detectHardcoreEnabled_handlesLeadingWhitespace() {
        assertTrue(detectHardcoreEnabled("   cheevos_hardcore_mode_enable = \"true\""))
    }

    @Test
    fun detectHardcoreEnabled_emptyString() {
        assertFalse(detectHardcoreEnabled(""))
    }

    @Test
    fun extractRetroArchCredentials_returnsUsernameAndToken() {
        val cfg = """
            cheevos_username = "player1"
            cheevos_token = "secret-token"
        """.trimIndent()

        val credentials = extractRetroArchCredentials(cfg)

        assertEquals("player1", credentials?.username)
        assertEquals("secret-token", credentials?.token)
    }

    @Test
    fun extractRetroArchCredentials_handlesLeadingWhitespace() {
        val cfg = "  cheevos_username = \"player1\"\n  cheevos_token = \"secret-token\""

        val credentials = extractRetroArchCredentials(cfg)

        assertEquals("player1", credentials?.username)
        assertEquals("secret-token", credentials?.token)
    }

    @Test
    fun extractRetroArchCredentials_nullWhenTokenEmpty() {
        val cfg = """
            cheevos_username = "player1"
            cheevos_token = ""
        """.trimIndent()

        assertEquals(null, extractRetroArchCredentials(cfg))
    }

    @Test
    fun extractRetroArchCredentials_nullWhenUsernameEmpty() {
        val cfg = """
            cheevos_username = ""
            cheevos_token = "secret-token"
        """.trimIndent()

        assertEquals(null, extractRetroArchCredentials(cfg))
    }

    @Test
    fun extractRetroArchCredentials_nullWhenMissing() {
        assertEquals(null, extractRetroArchCredentials("cheevos_enable = \"true\""))
    }

    @Test
    fun buildPatchedContent_setsProxyHost() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("""cheevos_custom_host = "$proxyAddress""""))
    }

    @Test
    fun buildPatchedContent_disablesHardcore() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "true"
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("""cheevos_hardcore_mode_enable = "false""""))
        assertFalse(patched.contains("""cheevos_hardcore_mode_enable = "true""""))
    }

    @Test
    fun buildPatchedContent_appendsMissingHostLine() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("""cheevos_custom_host = "$proxyAddress""""))
    }

    @Test
    fun buildPatchedContent_appendsMissingHardcoreLine() {
        val cfg = """
            cheevos_enable = "true"
            cheevos_custom_host = ""
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("""cheevos_hardcore_mode_enable = "false""""))
    }

    @Test
    fun buildPatchedContent_bothMissing() {
        val patched = buildPatchedContent("""cheevos_enable = "true"""", proxyAddress)

        assertTrue(patched.contains("""cheevos_custom_host = "$proxyAddress""""))
        assertTrue(patched.contains("""cheevos_hardcore_mode_enable = "false""""))
    }

    @Test
    fun buildPatchedContent_preservesOtherLines() {
        val cfg = """
            video_fullscreen = "true"
            cheevos_custom_host = ""
            audio_driver = "opensl"
            cheevos_hardcore_mode_enable = "true"
            input_autodetect_enable = "true"
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("video_fullscreen"))
        assertTrue(patched.contains("audio_driver"))
        assertTrue(patched.contains("input_autodetect_enable"))
    }

    @Test
    fun buildPatchedContent_idempotent() {
        val cfg = """
            cheevos_custom_host = "$proxyAddress"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        assertEquals(cfg, buildPatchedContent(cfg, proxyAddress))
    }

    @Test
    fun buildPatchedContent_replacesExistingNonEmptyHost() {
        val patched = buildPatchedContent("""cheevos_custom_host = "old.host.com:9999"""", proxyAddress)

        assertTrue(patched.contains("""cheevos_custom_host = "$proxyAddress""""))
        assertFalse(patched.contains("old.host.com"))
    }

    @Test
    fun buildPatchedContent_preservesLeadingWhitespace() {
        val cfg = "  cheevos_custom_host = \"\"\n  cheevos_hardcore_mode_enable = \"true\""

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(patched.contains("""  cheevos_custom_host = "$proxyAddress""""))
        assertTrue(patched.contains("""  cheevos_hardcore_mode_enable = "false""""))
    }

    @Test
    fun buildRevertedContent_clearsProxyHost() {
        val cfg = "cheevos_custom_host = \"$proxyAddress\""

        assertEquals("cheevos_custom_host = \"\"", buildRevertedContent(cfg))
    }

    @Test
    fun buildRevertedContent_withoutRestoreHardcore_leavesHardcoreFalse() {
        val cfg = """
            cheevos_custom_host = "$proxyAddress"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val reverted = buildRevertedContent(cfg, restoreHardcore = false)

        assertTrue(reverted.contains("""cheevos_hardcore_mode_enable = "false""""))
    }

    @Test
    fun buildRevertedContent_withRestoreHardcore_setsHardcoreTrue() {
        val cfg = """
            cheevos_custom_host = "$proxyAddress"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val reverted = buildRevertedContent(cfg, restoreHardcore = true)

        assertTrue(reverted.contains("""cheevos_hardcore_mode_enable = "true""""))
    }

    @Test
    fun buildRevertedContent_restoreHardcore_noHardcoreLine_doesNotAppend() {
        val reverted = buildRevertedContent("""cheevos_custom_host = "$proxyAddress"""", restoreHardcore = true)

        assertFalse(reverted.contains("cheevos_hardcore_mode_enable"))
    }

    @Test
    fun buildRevertedContent_preservesOtherLines() {
        val cfg = """
            video_fullscreen = "true"
            cheevos_custom_host = "$proxyAddress"
            audio_driver = "opensl"
        """.trimIndent()

        val reverted = buildRevertedContent(cfg)

        assertTrue(reverted.contains("video_fullscreen"))
        assertTrue(reverted.contains("audio_driver"))
    }

    @Test
    fun buildRevertedContent_alreadyReverted_noChange() {
        val cfg = """
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        assertEquals(cfg, buildRevertedContent(cfg))
    }

    @Test
    fun ensureBackupFileExists_createsSiblingBackupWhenMissing() {
        val target = tempFolder.newFile("retroarch.cfg")
        val original = "cheevos_custom_host = \"\""

        ensureBackupFileExists(target, original)

        assertEquals(original, backupFileFor(target).readText())
    }

    @Test
    fun ensureBackupFileExists_doesNotOverwriteExistingBackup() {
        val target = tempFolder.newFile("retroarch.cfg")
        val backup = backupFileFor(target)
        backup.writeText("existing backup")

        ensureBackupFileExists(target, "new content")

        assertEquals("existing backup", backup.readText())
    }

    @Test
    fun isPatchedContent_trueWhenPatched() {
        assertTrue(isPatchedContent("""cheevos_custom_host = "$proxyAddress"""", proxyAddress))
    }

    @Test
    fun isPatchedContent_falseWhenEmpty() {
        assertFalse(isPatchedContent("""cheevos_custom_host = """"", proxyAddress))
    }

    @Test
    fun isPatchedContent_falseWhenDifferentHost() {
        assertFalse(isPatchedContent("""cheevos_custom_host = "other.host:9999"""", proxyAddress))
    }

    @Test
    fun isPatchedContent_falseWhenMissing() {
        assertFalse(isPatchedContent("""cheevos_enable = "true"""", proxyAddress))
    }

    @Test
    fun isPatchedContent_handlesLeadingWhitespace() {
        assertTrue(isPatchedContent("""  cheevos_custom_host = "$proxyAddress"""", proxyAddress))
    }

    @Test
    fun isPatchedContent_multiline() {
        val cfg = """
            video_fullscreen = "true"
            cheevos_custom_host = "$proxyAddress"
            audio_driver = "opensl"
        """.trimIndent()

        assertTrue(isPatchedContent(cfg, proxyAddress))
    }

    @Test
    fun patchThenRevert_restoresOriginalContent() {
        val original = """
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val patched = buildPatchedContent(original, proxyAddress)
        val reverted = buildRevertedContent(patched, restoreHardcore = false)

        assertEquals(original, reverted)
    }

    @Test
    fun patchThenRevert_restoresHardcore() {
        val original = """
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "true"
        """.trimIndent()

        val patched = buildPatchedContent(original, proxyAddress)
        val reverted = buildRevertedContent(patched, restoreHardcore = true)

        assertFalse(patched.contains("""cheevos_hardcore_mode_enable = "true""""))
        assertTrue(reverted.contains("cheevos_hardcore_mode_enable = \"true\""))
        assertTrue(reverted.contains("cheevos_custom_host = \"\""))
    }

    @Test
    fun patchedContent_isDetectedByIsPatchedContent() {
        val cfg = """
            cheevos_custom_host = ""
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val patched = buildPatchedContent(cfg, proxyAddress)

        assertTrue(isPatchedContent(patched, proxyAddress))
    }

    @Test
    fun revertedContent_isNotDetectedByIsPatchedContent() {
        val cfg = """
            cheevos_custom_host = "$proxyAddress"
            cheevos_hardcore_mode_enable = "false"
        """.trimIndent()

        val reverted = buildRevertedContent(cfg)

        assertFalse(isPatchedContent(reverted, proxyAddress))
    }

    @Test
    fun patchManualEditInstructions_includeExactLines() {
        val instructions = patchManualEditInstructions(proxyAddress)

        assertTrue(instructions.contains("Grant Folder Access"))
        assertTrue(instructions.contains("cheevos_custom_host = \"$proxyAddress\""))
        assertTrue(instructions.contains("cheevos_hardcore_mode_enable = \"false\""))
    }

    @Test
    fun revertManualEditInstructions_includeExactLines() {
        val instructions = revertManualEditInstructions()

        assertTrue(instructions.contains("Grant Folder Access"))
        assertTrue(instructions.contains("cheevos_custom_host = \"\""))
        assertTrue(instructions.contains("cheevos_hardcore_mode_enable = \"true\""))
    }
}
