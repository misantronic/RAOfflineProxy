package com.raofflineproxy

import com.raofflineproxy.proxy.isCacheableGameIdResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdCacheTest {

    @Test
    fun cachesMatch() {
        assertTrue(isCacheableGameIdResponse("""{"Success":true,"GameID":10701}"""))
    }

    @Test
    fun cachesMatchWithoutSuccessFlag() {
        assertTrue(isCacheableGameIdResponse("""{"GameID":10701}"""))
    }

    @Test
    fun cachesGenuineNoMatch() {
        assertTrue(isCacheableGameIdResponse("""{"Success":true,"GameID":0}"""))
    }

    @Test
    fun skipsErrorResponse() {
        assertFalse(isCacheableGameIdResponse("""{"Success":false,"Error":"Invalid user/token","GameID":0}"""))
    }

    @Test
    fun skipsNoMatchWithoutSuccessFlag() {
        assertFalse(isCacheableGameIdResponse("""{"GameID":0}"""))
    }

    @Test
    fun skipsNonJsonBody() {
        assertFalse(isCacheableGameIdResponse("<html><body>Maintenance</body></html>"))
    }

    @Test
    fun skipsEmptyBody() {
        assertFalse(isCacheableGameIdResponse(""))
    }
}
