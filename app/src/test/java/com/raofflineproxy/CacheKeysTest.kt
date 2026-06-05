package com.raofflineproxy

import com.raofflineproxy.data.CacheKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheKeysTest {

    // ── Constants ──

    @Test
    fun userAgent_constant() {
        assertEquals("ua::last", CacheKeys.USER_AGENT)
    }

    @Test
    fun prefixLogin_constant() {
        assertEquals("login2::", CacheKeys.PREFIX_LOGIN)
    }

    @Test
    fun prefixPatch_constant() {
        assertEquals("patch:", CacheKeys.PREFIX_PATCH)
    }

    @Test
    fun prefixUnlocks_constant() {
        assertEquals("unlocks:", CacheKeys.PREFIX_UNLOCKS)
    }

    @Test
    fun prefixStartSession_constant() {
        assertEquals("startsession:", CacheKeys.PREFIX_STARTSESSION)
    }

    @Test
    fun prefixGameId_constant() {
        assertEquals("gameid:", CacheKeys.PREFIX_GAMEID)
    }

    // ── login() ──

    @Test
    fun login_buildsKeyWithUsername() {
        assertEquals("login2::player1", CacheKeys.login("player1"))
    }

    @Test
    fun login_emptyUsername() {
        assertEquals("login2::", CacheKeys.login(""))
    }

    @Test
    fun login_specialCharacters() {
        assertEquals("login2::user@name", CacheKeys.login("user@name"))
    }

    // ── patch() ──

    @Test
    fun patch_buildsKeyWithGameIdAndUser() {
        assertEquals("patch:1234:player1", CacheKeys.patch(1234, "player1"))
    }

    @Test
    fun patch_zeroGameId() {
        assertEquals("patch:0:user", CacheKeys.patch(0, "user"))
    }

    // ── patchPrefix() ──

    @Test
    fun patchPrefix_buildsWithGameIdString() {
        assertEquals("patch:5678:", CacheKeys.patchPrefix("5678"))
    }

    @Test
    fun patchPrefix_emptyGameId() {
        assertEquals("patch::", CacheKeys.patchPrefix(""))
    }

    // ── unlocks() ──

    @Test
    fun unlocks_int_buildsSoftcoreKey() {
        assertEquals("unlocks:42:player:0", CacheKeys.unlocks(42, "player"))
    }

    @Test
    fun unlocks_string_buildsSoftcoreKey() {
        assertEquals("unlocks:42:player:0", CacheKeys.unlocks("42", "player"))
    }

    @Test
    fun unlocks_alwaysAppendsSoftcoreFlag() {
        val key = CacheKeys.unlocks(99, "user")
        assert(key.endsWith(":0")) { "Unlocks key must end with :0 (softcore)" }
    }

    // ── startSession() ──

    @Test
    fun startSession_buildsSoftcoreKey() {
        assertEquals("startsession:100:player:0", CacheKeys.startSession(100, "player"))
    }

    @Test
    fun startSession_alwaysAppendsSoftcoreFlag() {
        val key = CacheKeys.startSession(1, "u")
        assert(key.endsWith(":0")) { "StartSession key must end with :0 (softcore)" }
    }

    // ── parseGameIdFromPatchKey() ──

    @Test
    fun parseGameIdFromPatchKey_validKey() {
        assertEquals(1234, CacheKeys.parseGameIdFromPatchKey("patch:1234:player"))
    }

    @Test
    fun parseGameIdFromPatchKey_zeroGameId() {
        assertEquals(0, CacheKeys.parseGameIdFromPatchKey("patch:0:user"))
    }

    @Test
    fun parseGameIdFromPatchKey_noGameId() {
        assertNull(CacheKeys.parseGameIdFromPatchKey("patch::user"))
    }

    @Test
    fun parseGameIdFromPatchKey_nonNumericGameId() {
        assertNull(CacheKeys.parseGameIdFromPatchKey("patch:abc:user"))
    }

    @Test
    fun parseGameIdFromPatchKey_emptyString() {
        assertNull(CacheKeys.parseGameIdFromPatchKey(""))
    }

    @Test
    fun parseGameIdFromPatchKey_justPrefix() {
        assertNull(CacheKeys.parseGameIdFromPatchKey("patch:"))
    }

    @Test
    fun parseGameIdFromPatchKey_largeGameId() {
        assertEquals(999999, CacheKeys.parseGameIdFromPatchKey("patch:999999:user"))
    }

    // ── parseGameIdStringFromPatchKey() ──

    @Test
    fun parseGameIdStringFromPatchKey_validKey() {
        assertEquals("1234", CacheKeys.parseGameIdStringFromPatchKey("patch:1234:player"))
    }

    @Test
    fun parseGameIdStringFromPatchKey_nonNumericGameId() {
        assertEquals("abc", CacheKeys.parseGameIdStringFromPatchKey("patch:abc:user"))
    }

    @Test
    fun parseGameIdStringFromPatchKey_emptyPrefix() {
        assertNull(CacheKeys.parseGameIdStringFromPatchKey("patch:"))
    }

    @Test
    fun parseGameIdStringFromPatchKey_emptyString() {
        assertNull(CacheKeys.parseGameIdStringFromPatchKey(""))
    }

    @Test
    fun parseGameIdStringFromPatchKey_noColonAfterGameId() {
        assertEquals("5678", CacheKeys.parseGameIdStringFromPatchKey("patch:5678"))
    }

    @Test
    fun parseUserFromPatchKey_validKey() {
        assertEquals("player", CacheKeys.parseUserFromPatchKey("patch:1234:player"))
    }

    @Test
    fun parseUserFromPatchKey_missingUser() {
        assertNull(CacheKeys.parseUserFromPatchKey("patch:1234:"))
    }

    @Test
    fun parseAchievementSetsHash_validKey() {
        assertEquals("abc123hash", CacheKeys.parseAchievementSetsHash("achievementsets:abc123hash:player"))
    }

    @Test
    fun parseUserFromAchievementSetsKey_validKey() {
        assertEquals("player", CacheKeys.parseUserFromAchievementSetsKey("achievementsets:abc123hash:player"))
    }

    @Test
    fun parseAchievementSetsKey_handlesEmbeddedColonsByUsingLastSeparator() {
        assertEquals("hash:with:colons", CacheKeys.parseAchievementSetsHash("achievementsets:hash:with:colons:player"))
        assertEquals("player", CacheKeys.parseUserFromAchievementSetsKey("achievementsets:hash:with:colons:player"))
        assertTrue(CacheKeys.parseAchievementSetsHash("achievementsets:hash:with:colons:player")!!.contains(':'))
    }
}
