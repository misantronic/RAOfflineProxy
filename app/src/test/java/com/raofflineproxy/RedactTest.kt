package com.raofflineproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {

    // ── redactTokens (URL query strings) ──

    @Test
    fun redactTokens_replacesTokenInQueryString() {
        val input = "/dorequest.php?r=patch&g=1234&u=player&t=SECRET"

        assertEquals(
            "/dorequest.php?r=patch&g=1234&u=player&t=<token>",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_replacesTokenWhenFirst() {
        val input = "/dorequest.php?t=SECRET&r=patch&u=player"

        assertEquals(
            "/dorequest.php?t=<token>&r=patch&u=player",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_replacesTokenInMiddle() {
        val input = "/dorequest.php?r=patch&t=SECRET&g=42"

        assertEquals(
            "/dorequest.php?r=patch&t=<token>&g=42",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_handlesEmptyTokenValue() {
        val input = "/dorequest.php?r=patch&t=&u=player"

        assertEquals(
            "/dorequest.php?r=patch&t=<token>&u=player",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_noTokenParam_returnsUnchanged() {
        val input = "/dorequest.php?r=patch&g=1234&u=player"

        assertEquals(input, redactTokens(input))
    }

    @Test
    fun redactTokens_fullUrl() {
        val input = "https://retroachievements.org/dorequest.php?r=patch&g=99&u=user1&t=abc123xyz"

        assertEquals(
            "https://retroachievements.org/dorequest.php?r=patch&g=99&u=user1&t=<token>",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_doesNotRedactOtherTParams() {
        val input = "/dorequest.php?title=test&token=xyz&t=SECRET"

        assertEquals(
            "/dorequest.php?title=test&token=xyz&t=<token>",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_emptyString() {
        assertEquals("", redactTokens(""))
    }

    @Test
    fun redactTokens_tokenAtEndNoValue() {
        val input = "/dorequest.php?r=patch&t="

        assertEquals(
            "/dorequest.php?r=patch&t=<token>",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_longTokenValue() {
        val token = "a".repeat(200)
        val input = "/dorequest.php?r=patch&t=$token&u=player"

        assertEquals(
            "/dorequest.php?r=patch&t=<token>&u=player",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_replacesPasswordInQueryString() {
        val input = "/dorequest.php?r=login2&u=player&p=hunter2"

        assertEquals(
            "/dorequest.php?r=login2&u=player&p=<password>",
            redactTokens(input)
        )
    }

    @Test
    fun redactTokens_replacesBothTokenAndPassword() {
        val input = "/dorequest.php?r=login2&u=player&p=hunter2&t=SECRET"

        assertEquals(
            "/dorequest.php?r=login2&u=player&p=<password>&t=<token>",
            redactTokens(input)
        )
    }

    // ── redactFormBody (form-encoded bodies) ──

    @Test
    fun redactFormBody_replacesTokenAtStart() {
        val input = "t=SECRET&r=awardachievement&a=123"

        assertEquals(
            "t=<token>&r=awardachievement&a=123",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_replacesTokenInMiddle() {
        val input = "r=awardachievement&t=SECRET&a=123"

        assertEquals(
            "r=awardachievement&t=<token>&a=123",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_replacesTokenAtEnd() {
        val input = "r=awardachievement&a=123&t=SECRET"

        assertEquals(
            "r=awardachievement&a=123&t=<token>",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_handlesEmptyTokenValue() {
        val input = "r=patch&t=&u=player"

        assertEquals(
            "r=patch&t=<token>&u=player",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_noTokenParam_returnsUnchanged() {
        val input = "r=patch&g=1234&u=player"

        assertEquals(input, redactFormBody(input))
    }

    @Test
    fun redactFormBody_tokenOnly() {
        val input = "t=SECRET"

        assertEquals("t=<token>", redactFormBody(input))
    }

    @Test
    fun redactFormBody_emptyString() {
        assertEquals("", redactFormBody(""))
    }

    @Test
    fun redactFormBody_doesNotRedactOtherTParams() {
        val input = "title=test&token=xyz&t=SECRET"

        assertEquals(
            "title=test&token=xyz&t=<token>",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_urlEncodedTokenValue() {
        val input = "r=patch&t=abc%3D123%26def&u=player"

        assertEquals(
            "r=patch&t=<token>&u=player",
            redactFormBody(input)
        )
    }

    @Test
    fun redactFormBody_replacesPassword() {
        val input = "r=login2&u=player&p=hunter2"

        assertEquals(
            "r=login2&u=player&p=<password>",
            redactFormBody(input)
        )
    }
}
