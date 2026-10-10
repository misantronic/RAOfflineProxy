package com.raofflineproxy

import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxy.WATCH_RESCAN_INTERVAL_MS
import com.raofflineproxy.proxy.WatchedFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedFolderTest {
    private val folder = WatchedFolder(
        uri = "content://tree/primary%3AROMs",
        name = "ROMs",
        path = "ROMs",
        watching = true,
        addedAt = 1_000L,
        lastScanAt = 5_000L
    )

    @Test
    fun json_roundTrips() {
        assertEquals(folder, WatchedFolder.fromJson(folder.toJson()))
        val withoutPath = folder.copy(path = null)
        assertEquals(withoutPath, WatchedFolder.fromJson(withoutPath.toJson()))
    }

    @Test
    fun json_rejectsGarbage() {
        assertNull(WatchedFolder.fromJson("not json"))
        assertNull(WatchedFolder.fromJson("{\"uri\":\"\"}"))
    }

    @Test
    fun isDue_onlyWhenWatchingAndWindowElapsed() {
        assertFalse(folder.isDue(folder.lastScanAt + WATCH_RESCAN_INTERVAL_MS - 1))
        assertTrue(folder.isDue(folder.lastScanAt + WATCH_RESCAN_INTERVAL_MS))
        assertFalse(folder.copy(watching = false).isDue(folder.lastScanAt + WATCH_RESCAN_INTERVAL_MS))
        assertTrue(folder.copy(lastScanAt = 0L).isDue(1L + WATCH_RESCAN_INTERVAL_MS))
    }

    @Test
    fun isDue_clockJumpedBackwards() {
        assertTrue(folder.isDue(folder.lastScanAt - 1))
    }

    @Test
    fun keys_seenKeyStartsWithFolderPrefix() {
        val key = CacheKeys.watchSeen(folder.uri, "/storage/ROMs/a.gba")
        assertTrue(key.startsWith(CacheKeys.watchSeenPrefix(folder.uri)))
        assertEquals("watchfolder:${folder.uri}", CacheKeys.watchFolder(folder.uri))
    }
}
