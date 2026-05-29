package com.raofflineproxy

import com.raofflineproxy.proxy.SmartCacheEmulator
import com.raofflineproxy.proxy.parsePpssppRecentCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class PpssppSmartCacheTest {
    @Test
    fun parsePpssppRecentCandidates_readsOrderedRecentEntries() {
        val content = """
            [Achievements]
            AchievementsHost = 127.0.0.1:8080
            [Recent]
            MaxRecent = 60
            FileName0 = /storage/3432-3530/Roms/psp/God of War.iso
            FileName1 = content://com.android.externalstorage.documents/tree/3432-3530%3ARoms%2Fpsp/document/3432-3530%3ARoms%2Fpsp%2Fcvn-gtj.iso
        """.trimIndent()

        val candidates = parsePpssppRecentCandidates(content)

        assertEquals(2, candidates.size)
        assertEquals(SmartCacheEmulator.Ppsspp, candidates[0].emulator)
        assertEquals("/storage/3432-3530/Roms/psp/God of War.iso", candidates[0].path)
        assertEquals("God of War.iso", candidates[0].title)
        assertEquals(0, candidates[0].priority)
        assertEquals("content://com.android.externalstorage.documents/tree/3432-3530%3ARoms%2Fpsp/document/3432-3530%3ARoms%2Fpsp%2Fcvn-gtj.iso", candidates[1].path)
        assertEquals("3432-3530:Roms/psp/cvn-gtj.iso", candidates[1].title)
        assertEquals(1, candidates[1].priority)
    }

    @Test
    fun parsePpssppRecentCandidates_ignoresDuplicatesAndBlankEntries() {
        val content = """
            [Recent]
            FileName0 = /storage/3432-3530/Roms/psp/Game.iso
            FileName1 =
            FileName2 = /storage/3432-3530/Roms/psp/Game.iso
        """.trimIndent()

        val candidates = parsePpssppRecentCandidates(content)

        assertEquals(1, candidates.size)
        assertEquals("/storage/3432-3530/Roms/psp/Game.iso", candidates.single().path)
    }

    @Test
    fun parsePpssppRecentCandidates_returnsEmptyWhenSectionMissing() {
        val content = """
            [General]
            CurrentDirectory = /storage/3432-3530/Roms/psp
        """.trimIndent()

        val candidates = parsePpssppRecentCandidates(content)

        assertEquals(0, candidates.size)
    }
}
