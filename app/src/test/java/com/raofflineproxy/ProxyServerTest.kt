package com.raofflineproxy

import com.raofflineproxy.proxy.proxyCacheKey
import com.raofflineproxy.proxy.proxyExtractAction
import com.raofflineproxy.proxy.proxyExtractParam
import com.raofflineproxy.proxy.proxyHttpError
import com.raofflineproxy.proxy.proxyHttpResponse
import com.raofflineproxy.proxy.proxyHttpGameIdCacheMiss
import com.raofflineproxy.proxy.proxyHttpOk
import com.raofflineproxy.proxy.proxyIsHardcoreRequest
import com.raofflineproxy.proxy.ParsedRequestLineResult
import com.raofflineproxy.proxy.UpstreamResult
import com.raofflineproxy.proxy.parseContentLength
import com.raofflineproxy.proxy.parseRequestLine
import com.raofflineproxy.proxy.sanitizeHttpReasonPhrase
import com.raofflineproxy.proxy.shouldCacheResponse
import com.raofflineproxy.proxy.shouldQueueAward
import com.raofflineproxy.proxy.validateBodyRead
import com.raofflineproxy.proxy.validateTransferEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServerTest {

    // ── proxyExtractAction() ──

    @Test
    fun extractAction_fromQueryString() {
        assertEquals("patch", proxyExtractAction("/dorequest.php?r=patch&g=42", ""))
    }

    @Test
    fun extractAction_fromBody() {
        assertEquals("awardachievement", proxyExtractAction("/dorequest.php", "r=awardachievement&a=100"))
    }

    @Test
    fun extractAction_queryTakesPriority() {
        assertEquals("patch", proxyExtractAction("/dorequest.php?r=patch", "r=gameid"))
    }

    @Test
    fun extractAction_noAction() {
        assertNull(proxyExtractAction("/other.php", ""))
    }

    @Test
    fun extractAction_emptyBoth() {
        assertNull(proxyExtractAction("/", ""))
    }

    @Test
    fun extractAction_gameid() {
        assertEquals("gameid", proxyExtractAction("/dorequest.php?r=gameid&m=abc123", ""))
    }

    @Test
    fun extractAction_login2() {
        assertEquals("login2", proxyExtractAction("/dorequest.php?r=login2&u=player", ""))
    }

    // ── proxyExtractParam() ──

    @Test
    fun extractParam_fromQuery() {
        assertEquals("42", proxyExtractParam("g", "/dorequest.php?r=patch&g=42&u=player", ""))
    }

    @Test
    fun extractParam_fromBody() {
        assertEquals("100", proxyExtractParam("a", "/dorequest.php", "a=100&u=player"))
    }

    @Test
    fun extractParam_queryPriority() {
        assertEquals("qval", proxyExtractParam("u", "/dorequest.php?u=qval", "u=bval"))
    }

    @Test
    fun extractParam_missing() {
        assertNull(proxyExtractParam("z", "/dorequest.php?r=patch", "a=1"))
    }

    @Test
    fun extractParam_emptyValue() {
        assertEquals("", proxyExtractParam("g", "/dorequest.php?g=", ""))
    }

    // ── proxyCacheKey() ──

    @Test
    fun cacheKey_gameid() {
        val key = proxyCacheKey("/dorequest.php?r=gameid&m=abc123hash", "")
        assertEquals("gameid:abc123hash", key)
    }

    @Test
    fun cacheKey_patch() {
        val key = proxyCacheKey("/dorequest.php?r=patch&g=42&u=player", "")
        assertEquals("patch:42:player", key)
    }

    @Test
    fun cacheKey_unlocks_softcore() {
        val key = proxyCacheKey("/dorequest.php?r=unlocks&g=42&u=player&h=0", "")
        assertEquals("unlocks:42:player:0", key)
    }

    @Test
    fun cacheKey_unlocks_hardcore() {
        val key = proxyCacheKey("/dorequest.php?r=unlocks&g=42&u=player&h=1", "")
        assertEquals("unlocks:42:player:1", key)
    }

    @Test
    fun cacheKey_noHardcore_noHSuffix() {
        val key = proxyCacheKey("/dorequest.php?r=patch&g=42&u=player", "")
        assertFalse(key.endsWith(":0"))
        assertFalse(key.endsWith(":1"))
    }

    @Test
    fun cacheKey_login2() {
        val key = proxyCacheKey("/dorequest.php?r=login2&u=player", "")
        assertEquals("login2::player", key)
    }

    @Test
    fun cacheKey_unknownAction() {
        val key = proxyCacheKey("/dorequest.php", "")
        assertEquals("unknown::", key)
    }

    @Test
    fun cacheKey_fallsBackToIParam() {
        val key = proxyCacheKey("/dorequest.php?r=achievements&i=99&u=player", "")
        assertEquals("achievements:99:player", key)
    }

    @Test
    fun cacheKey_gParamTakesPriorityOverI() {
        val key = proxyCacheKey("/dorequest.php?r=patch&g=42&i=99&u=player", "")
        assertEquals("patch:42:player", key)
    }

    @Test
    fun cacheKey_paramsFromBody() {
        val key = proxyCacheKey("/dorequest.php", "r=patch&g=42&u=player")
        assertEquals("patch:42:player", key)
    }

    // ── proxyIsHardcoreRequest() ──

    @Test
    fun isHardcoreRequest_h1InQuery() {
        assertTrue(proxyIsHardcoreRequest("/dorequest.php?r=patch&h=1", ""))
    }

    @Test
    fun isHardcoreRequest_h0InQuery() {
        assertFalse(proxyIsHardcoreRequest("/dorequest.php?r=patch&h=0", ""))
    }

    @Test
    fun isHardcoreRequest_h1InBody() {
        assertTrue(proxyIsHardcoreRequest("/dorequest.php", "r=patch&h=1"))
    }

    @Test
    fun isHardcoreRequest_noHParam() {
        assertFalse(proxyIsHardcoreRequest("/dorequest.php?r=patch", ""))
    }

    @Test
    fun isHardcoreRequest_queryPriority() {
        assertFalse(proxyIsHardcoreRequest("/dorequest.php?h=0", "h=1"))
    }

    // ── parser helpers ──

    @Test
    fun parseRequestLine_validGet() {
        val result = parseRequestLine("GET /dorequest.php?r=patch HTTP/1.1")
        assertTrue(result is ParsedRequestLineResult.Valid)
        result as ParsedRequestLineResult.Valid
        assertEquals("GET", result.method)
        assertEquals("/dorequest.php?r=patch", result.path)
    }

    @Test
    fun parseRequestLine_validPostWithExtraSpaces() {
        val result = parseRequestLine("  POST   /dorequest.php   HTTP/1.1  ")
        assertTrue(result is ParsedRequestLineResult.Valid)
        result as ParsedRequestLineResult.Valid
        assertEquals("POST", result.method)
        assertEquals("/dorequest.php", result.path)
    }

    @Test
    fun parseRequestLine_rejectsMalformedLine() {
        val result = parseRequestLine("BROKEN")
        assertTrue(result is ParsedRequestLineResult.Invalid)
        result as ParsedRequestLineResult.Invalid
        assertEquals(400, result.statusCode)
    }

    @Test
    fun parseRequestLine_rejectsUnsupportedMethod() {
        val result = parseRequestLine("PUT /dorequest.php HTTP/1.1")
        assertTrue(result is ParsedRequestLineResult.Invalid)
        result as ParsedRequestLineResult.Invalid
        assertEquals(405, result.statusCode)
    }

    @Test
    fun parseRequestLine_rejectsNonAbsolutePath() {
        val result = parseRequestLine("GET dorequest.php HTTP/1.1")
        assertTrue(result is ParsedRequestLineResult.Invalid)
        result as ParsedRequestLineResult.Invalid
        assertEquals(400, result.statusCode)
    }

    @Test
    fun validateTransferEncoding_allowsNull() {
        assertNull(validateTransferEncoding(null))
    }

    @Test
    fun validateTransferEncoding_allowsIdentity() {
        assertNull(validateTransferEncoding("identity"))
        assertNull(validateTransferEncoding("Identity"))
    }

    @Test
    fun validateTransferEncoding_rejectsChunked() {
        assertEquals(501 to "transfer encoding not supported", validateTransferEncoding("chunked"))
    }

    @Test
    fun parseContentLength_nullDefaultsToZero() {
        assertEquals(0, parseContentLength(null))
    }

    @Test
    fun parseContentLength_validInteger() {
        assertEquals(123, parseContentLength("123"))
    }

    @Test
    fun parseContentLength_invalidReturnsNull() {
        assertNull(parseContentLength("abc"))
    }

    @Test
    fun validateBodyRead_exactLengthAccepted() {
        assertNull(validateBodyRead(10, 10))
    }

    @Test
    fun validateBodyRead_shortReadRejected() {
        assertEquals(400 to "incomplete request body", validateBodyRead(10, 8))
    }

    // ── proxyHttpOk() ──

    @Test
    fun httpOk_containsStatusLine() {
        val response = proxyHttpOk("{}")
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
    }

    @Test
    fun httpOk_containsContentType() {
        val response = proxyHttpOk("{}")
        assertTrue(response.contains("Content-Type: application/json"))
    }

    @Test
    fun httpOk_containsCorrectContentLength() {
        val body = """{"Success":true}"""
        val response = proxyHttpOk(body)
        assertTrue(response.contains("Content-Length: ${body.toByteArray().size}"))
    }

    @Test
    fun httpOk_containsConnectionClose() {
        val response = proxyHttpOk("{}")
        assertTrue(response.contains("Connection: close"))
    }

    @Test
    fun httpOk_endsWithBody() {
        val body = """{"Success":true}"""
        val response = proxyHttpOk(body)
        assertTrue(response.endsWith(body))
    }

    @Test
    fun httpOk_bodyAfterDoubleNewline() {
        val body = """{"test":1}"""
        val response = proxyHttpOk(body)
        val parts = response.split("\r\n\r\n")
        assertEquals(2, parts.size)
        assertEquals(body, parts[1])
    }

    @Test
    fun httpOk_emptyBody() {
        val response = proxyHttpOk("")
        assertTrue(response.contains("Content-Length: 0"))
        assertTrue(response.endsWith("\r\n\r\n"))
    }

    @Test
    fun httpOk_unicodeBody() {
        val body = """{"msg":"日本語"}"""
        val response = proxyHttpOk(body)
        assertTrue(response.contains("Content-Length: ${body.toByteArray().size}"))
        assertTrue(response.endsWith(body))
    }

    // ── proxyHttpError() ──

    @Test
    fun httpError_containsStatusCode() {
        val response = proxyHttpError(503, "offline")
        assertTrue(response.startsWith("HTTP/1.1 503 offline\r\n"))
    }

    @Test
    fun httpError_containsErrorInBody() {
        val response = proxyHttpError(503, "offline")
        assertTrue(response.contains("""{"Success":false,"Error":"offline"}"""))
    }

    @Test
    fun httpError_containsContentType() {
        val response = proxyHttpError(403, "forbidden")
        assertTrue(response.contains("Content-Type: application/json"))
    }

    @Test
    fun httpError_403() {
        val response = proxyHttpError(403, "hardcore_not_supported")
        assertTrue(response.startsWith("HTTP/1.1 403"))
        assertTrue(response.contains("hardcore_not_supported"))
    }

    @Test
    fun httpError_correctContentLength() {
        val message = "test error"
        val expectedBody = """{"Success":false,"Error":"$message"}"""
        val response = proxyHttpError(500, message)
        assertTrue(response.contains("Content-Length: ${expectedBody.toByteArray().size}"))
    }

    // ── upstream award behavior helpers ──

    @Test
    fun shouldQueueAward_networkErrorOnly() {
        assertTrue(shouldQueueAward(UpstreamResult.NetworkError("timeout")))
        assertFalse(shouldQueueAward(UpstreamResult.Success(200, "OK", "{}")))
        assertFalse(shouldQueueAward(UpstreamResult.HttpError(401, "Unauthorized", "{}")))
    }

    @Test
    fun shouldCacheResponse_requiresSuccessTrue() {
        assertTrue(shouldCacheResponse("""{"Success":true}"""))
        assertFalse(shouldCacheResponse("""{"Success":false}"""))
    }

    @Test
    fun shouldCacheResponse_falseWhenMissingSuccessField() {
        assertFalse(shouldCacheResponse("""{"Error":"bad token"}"""))
    }

    @Test
    fun shouldCacheResponse_falseForInvalidJson() {
        assertFalse(shouldCacheResponse("not json"))
    }

    @Test
    fun sanitizeHttpReasonPhrase_fallsBackForBlankMessage() {
        assertEquals("Unauthorized", sanitizeHttpReasonPhrase("", 401))
    }

    @Test
    fun sanitizeHttpReasonPhrase_stripsNewlines() {
        assertEquals("Bad Request injected", sanitizeHttpReasonPhrase("Bad Request\r\ninjected", 400))
    }

    @Test
    fun httpResponse_preservesUpstreamStatusAndBody() {
        val body = """{"Success":false,"Error":"invalid token"}"""
        val response = proxyHttpResponse(401, "Unauthorized", body)
        assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(response.endsWith(body))
    }

    @Test
    fun httpResponse_usesFallbackReasonPhrase() {
        val response = proxyHttpResponse(503, "", "{}")
        assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable\r\n"))
    }

    // ── proxyHttpGameIdCacheMiss() ──

    @Test
    fun httpGameIdCacheMiss_is200() {
        val response = proxyHttpGameIdCacheMiss()
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
    }

    @Test
    fun httpGameIdCacheMiss_containsSuccessFalse() {
        val response = proxyHttpGameIdCacheMiss()
        assertTrue(response.contains(""""Success":false"""))
    }

    @Test
    fun httpGameIdCacheMiss_containsGameIdZero() {
        val response = proxyHttpGameIdCacheMiss()
        assertTrue(response.contains(""""GameID":0"""))
    }

    @Test
    fun httpGameIdCacheMiss_containsErrorMessage() {
        val response = proxyHttpGameIdCacheMiss()
        assertTrue(response.contains("Game not cached"))
    }
}
