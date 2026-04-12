package com.raofflineproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConstantsTest {

    // ── ByteArray.toHexString() ──

    @Test
    fun toHexString_emptyArray() {
        assertEquals("", byteArrayOf().toHexString())
    }

    @Test
    fun toHexString_singleByte() {
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHexString())
    }

    @Test
    fun toHexString_zeroByte() {
        assertEquals("00", byteArrayOf(0x00).toHexString())
    }

    @Test
    fun toHexString_multipleBytes() {
        assertEquals("0a1b2c", byteArrayOf(0x0A, 0x1B, 0x2C).toHexString())
    }

    @Test
    fun toHexString_allZeroes() {
        assertEquals("000000", byteArrayOf(0, 0, 0).toHexString())
    }

    @Test
    fun toHexString_lowercaseOutput() {
        val hex = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()).toHexString()
        assertEquals("abcdef", hex)
        assertEquals(hex, hex.lowercase())
    }

    // ── sha256Hex() ──

    @Test
    fun sha256Hex_emptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex("")
        )
    }

    @Test
    fun sha256Hex_helloWorld() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            sha256Hex("hello world")
        )
    }

    @Test
    fun sha256Hex_returns64CharHex() {
        val result = sha256Hex("test input")
        assertEquals(64, result.length)
        assertTrue(result.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun sha256Hex_deterministicOutput() {
        val first = sha256Hex("deterministic")
        val second = sha256Hex("deterministic")
        assertEquals(first, second)
    }

    @Test
    fun sha256Hex_differentInputsDifferentOutputs() {
        val a = sha256Hex("input_a")
        val b = sha256Hex("input_b")
        assertTrue(a != b)
    }

    // ── parseFormParams() ──

    @Test
    fun parseFormParams_singleParam() {
        val result = parseFormParams("key=value")
        assertEquals(mapOf("key" to "value"), result)
    }

    @Test
    fun parseFormParams_multipleParams() {
        val result = parseFormParams("r=patch&g=42&u=player")
        assertEquals("patch", result["r"])
        assertEquals("42", result["g"])
        assertEquals("player", result["u"])
    }

    @Test
    fun parseFormParams_emptyString() {
        val result = parseFormParams("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseFormParams_emptyValue() {
        val result = parseFormParams("key=")
        assertEquals(mapOf("key" to ""), result)
    }

    @Test
    fun parseFormParams_urlEncodedValue() {
        val result = parseFormParams("msg=hello+world")
        assertEquals("hello world", result["msg"])
    }

    @Test
    fun parseFormParams_urlEncodedPercent() {
        val result = parseFormParams("path=%2Ffoo%2Fbar")
        assertEquals("/foo/bar", result["path"])
    }

    @Test
    fun parseFormParams_duplicateKeysLastWins() {
        val result = parseFormParams("a=1&a=2")
        assertEquals("2", result["a"])
    }

    @Test
    fun parseFormParams_noEqualsSign() {
        val result = parseFormParams("malformed&key=value")
        assertEquals(1, result.size)
        assertEquals("value", result["key"])
    }

    @Test
    fun parseFormParams_equalsInValue() {
        val result = parseFormParams("formula=a=b")
        assertEquals("a=b", result["formula"])
    }

    // ── extractFormParam() ──

    @Test
    fun extractFormParam_findsExistingParam() {
        assertEquals("42", extractFormParam("r=patch&g=42&u=player", "g"))
    }

    @Test
    fun extractFormParam_returnNullForMissingParam() {
        assertNull(extractFormParam("r=patch&g=42", "u"))
    }

    @Test
    fun extractFormParam_emptyBody() {
        assertNull(extractFormParam("", "key"))
    }

    @Test
    fun extractFormParam_findsFirstParam() {
        assertEquals("patch", extractFormParam("r=patch&g=42", "r"))
    }

    @Test
    fun extractFormParam_findsLastParam() {
        assertEquals("player", extractFormParam("r=patch&g=42&u=player", "u"))
    }

    // ── proxyUserAgent() ──

    @Test
    fun proxyUserAgent_appendsTagToPlainAgent() {
        val result = proxyUserAgent("rcheevos/11.4.0")
        assertTrue(result.startsWith("rcheevos/11.4.0 RAOfflineProxy/"))
    }

    @Test
    fun proxyUserAgent_idempotent() {
        val first = proxyUserAgent("rcheevos/11.4.0")
        val second = proxyUserAgent(first)
        assertEquals(first, second)
    }

    @Test
    fun proxyUserAgent_alreadyTaggedReturnsUnchanged() {
        val tagged = "rcheevos/11.4.0 RAOfflineProxy/1.0.0"
        assertEquals(tagged, proxyUserAgent(tagged))
    }

    @Test
    fun proxyUserAgent_containsProxyTag() {
        val result = proxyUserAgent("some-agent")
        assertTrue(result.contains("RAOfflineProxy"))
    }

    @Test
    fun proxyUserAgent_emptyInput() {
        val result = proxyUserAgent("")
        assertTrue(result.startsWith(" RAOfflineProxy/"))
    }

    @Test
    fun buildApiUrl_encodesQueryParams() {
        val url = buildApiUrl(
            PROXY_BASE,
            "awardachievement",
            mapOf(
                "u" to "player one",
                "t" to "a+b/c=",
                "g" to "42"
            )
        )

        assertEquals(
            "http://127.0.0.1:8080/dorequest.php?r=awardachievement&u=player%20one&t=a%2Bb%2Fc%3D&g=42",
            url
        )
    }

    // ── Constants ──

    @Test
    fun raHost_isHttps() {
        assertTrue(RA_HOST.startsWith("https://"))
    }

    @Test
    fun proxyPort_is8080() {
        assertEquals(8080, PROXY_PORT)
    }

    @Test
    fun proxyBase_containsPortAndHost() {
        assertEquals("http://127.0.0.1:8080", PROXY_BASE)
    }

    @Test
    fun proxyValue_isHostColon8080() {
        assertEquals("127.0.0.1:8080", PROXY_VALUE)
    }
}
