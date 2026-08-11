package com.raofflineproxy

import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.buildPatchedContent
import com.raofflineproxy.ui.buildPatchedDolphinContent
import com.raofflineproxy.ui.buildPatchedPpssppContent
import com.raofflineproxy.ui.buildRevertedContent
import com.raofflineproxy.ui.buildRevertedDolphinContent
import com.raofflineproxy.ui.buildRevertedPpssppContent
import com.raofflineproxy.ui.ppssppIniPathCandidates
import com.raofflineproxy.ui.requireConfigOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun shizukuRevertRestoresHardcoreWhenItWasEnabledBeforePatching() {
        val retroArch = buildPatchedContent("cheevos_custom_host = \"\"\ncheevos_hardcore_mode_enable = \"true\"", proxyAddress)
        val dolphin = buildPatchedDolphinContent("[Achievements]\nHostUrl = \nHardcoreEnabled = True", proxyAddress)
        val ppsspp = buildPatchedPpssppContent("[Achievements]\nAchievementsHost = \nAchievementsChallengeMode = True", proxyAddress)

        assertTrue(buildRevertedContent(retroArch, restoreHardcore = true).contains("cheevos_hardcore_mode_enable = \"true\""))
        assertTrue(buildRevertedDolphinContent(dolphin, restoreHardcore = true).contains("HardcoreEnabled = True"))
        assertTrue(buildRevertedPpssppContent(ppsspp, restoreHardcore = true).contains("AchievementsChallengeMode = True"))
    }

    @Test
    fun shizukuRevertLeavesHardcoreOffWhenItWasOffBeforePatching() {
        val retroArch = buildPatchedContent("cheevos_custom_host = \"\"\ncheevos_hardcore_mode_enable = \"false\"", proxyAddress)
        val dolphin = buildPatchedDolphinContent("[Achievements]\nHostUrl = \nHardcoreEnabled = False", proxyAddress)
        val ppsspp = buildPatchedPpssppContent("[Achievements]\nAchievementsHost = \nAchievementsChallengeMode = False", proxyAddress)

        assertTrue(buildRevertedContent(retroArch, restoreHardcore = false).contains("cheevos_hardcore_mode_enable = \"false\""))
        assertTrue(buildRevertedDolphinContent(dolphin, restoreHardcore = false).contains("HardcoreEnabled = False"))
        assertTrue(buildRevertedPpssppContent(ppsspp, restoreHardcore = false).contains("AchievementsChallengeMode = False"))
    }

    @Test
    fun shizukuHardcoreDetectorsReadPrePatchState() {
        val retroArch = requireConfigOverride(Emulator.RetroArch).detectHardcoreEnabled
        val dolphin = requireConfigOverride(Emulator.Dolphin).detectHardcoreEnabled
        val ppsspp = requireConfigOverride(Emulator.Ppsspp).detectHardcoreEnabled

        assertTrue(retroArch("cheevos_hardcore_mode_enable = \"true\""))
        assertFalse(retroArch("cheevos_hardcore_mode_enable = \"false\""))
        assertTrue(dolphin("[Achievements]\nHardcoreEnabled = True"))
        assertFalse(dolphin("[Achievements]\nHardcoreEnabled = False"))
        assertTrue(ppsspp("[Achievements]\nAchievementsChallengeMode = True"))
        assertFalse(ppsspp("[Achievements]\nAchievementsChallengeMode = False"))
    }

    @Test
    fun shizukuManagedEmulatorsHaveDistinctWireKeys() {
        val keys = Emulator.SHIZUKU_MANAGED.map { requireConfigOverride(it).shizukuKey }

        assertEquals(listOf("retroarch", "dolphin", "ppsspp"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun ppssppIniPathCandidates_usesDefaultPackagePathWhenConfigured() {
        assertEquals(
            listOf(
                "/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/Android/data/org.ppsspp.ppssppgold/files/PSP/SYSTEM/ppsspp.ini"
            ),
            ppssppIniPathCandidates(
                rootPath = "/storage/emulated/0/PPSSPP/PSP",
                rootMode = PrefsConstants.PpssppRootMode.DefaultPackagePath
            )
        )
    }

    @Test
    fun ppssppIniPathCandidates_prefersCustomRootWhenConfigured() {
        assertEquals(
            listOf(
                "/storage/emulated/0/PPSSPP/PSP/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/PPSSPP/PSP/PSP/SYSTEM/ppsspp.ini"
            ),
            ppssppIniPathCandidates(
            rootPath = "/storage/emulated/0/PPSSPP/PSP",
                rootMode = PrefsConstants.PpssppRootMode.CustomRoot
            )
        )
    }

    @Test
    fun ppssppIniPathCandidates_supportsParentFolderCustomRoot() {
        assertEquals(
            listOf(
                "/storage/emulated/0/PPSSPP/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/PPSSPP/PSP/SYSTEM/ppsspp.ini"
            ),
            ppssppIniPathCandidates(
            rootPath = "/storage/emulated/0/PPSSPP",
                rootMode = PrefsConstants.PpssppRootMode.CustomRoot
            )
        )
    }

    @Test
    fun ppssppIniPathCandidates_returnsEmptyWhenCustomRootMissing() {
        assertTrue(
            ppssppIniPathCandidates(
                rootPath = null,
                rootMode = PrefsConstants.PpssppRootMode.CustomRoot
            ).isEmpty()
        )
    }

    @Test
    fun ppssppIniPathCandidates_customRootSupportsPspFolderAndParentFolder() {
        val candidates = ppssppIniPathCandidates(
            rootPath = "/storage/emulated/0/PPSSPP",
            rootMode = PrefsConstants.PpssppRootMode.CustomRoot
        )

        assertEquals(
            listOf(
                "/storage/emulated/0/PPSSPP/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/PPSSPP/PSP/SYSTEM/ppsspp.ini"
            ),
            candidates
        )
    }
}
