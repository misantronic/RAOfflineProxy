package com.raofflineproxy

import com.raofflineproxy.proxy.awaitPendingAwardWrite
import com.raofflineproxy.proxy.buildPendingAward
import com.raofflineproxy.proxy.contentTypeForFile
import com.raofflineproxy.proxy.compactAchievementSetsResponse
import com.raofflineproxy.proxy.compactCachedRawResponse
import com.raofflineproxy.proxy.isGcAchievementSetsResponse
import com.raofflineproxy.proxy.isChunkedTransferEncoding
import com.raofflineproxy.proxy.isPpssppUserAgent
import com.raofflineproxy.proxy.isStaticAssetRequest
import com.raofflineproxy.proxy.isWiiAchievementSetsResponse
import com.raofflineproxy.proxy.parseContentLength
import com.raofflineproxy.proxy.parseRequestLine
import com.raofflineproxy.proxy.ParsedRequestLineResult
import com.raofflineproxy.proxy.proxyCacheKey
import com.raofflineproxy.proxy.proxyExtractAction
import com.raofflineproxy.proxy.proxyExtractParam
import com.raofflineproxy.proxy.proxyHttpError
import com.raofflineproxy.proxy.proxyHttpGameIdCacheMiss
import com.raofflineproxy.proxy.proxyHttpNoContent
import com.raofflineproxy.proxy.proxyHttpOk
import com.raofflineproxy.proxy.proxyHttpFile
import com.raofflineproxy.proxy.proxyHttpResponse
import com.raofflineproxy.proxy.proxyIsHardcoreRequest
import com.raofflineproxy.proxy.readChunkedBody
import com.raofflineproxy.proxy.filterWarningAchievementIds
import com.raofflineproxy.proxy.filterWarningAchievementFromPatchResponse
import com.raofflineproxy.proxy.filterWarningAchievementFromAchievementSetsResponse
import com.raofflineproxy.proxy.filterWarningAchievementFromStartSessionResponse
import com.raofflineproxy.proxy.filterWarningAchievementForOnline
import com.raofflineproxy.proxy.normalizedCacheKey
import com.raofflineproxy.proxy.sanitizeHttpReasonPhrase
import com.raofflineproxy.proxy.shouldCacheResponse
import com.raofflineproxy.proxy.shouldCompactAchievementSets
import com.raofflineproxy.proxy.shouldQueueAward
import com.raofflineproxy.proxy.UpstreamResult
import com.raofflineproxy.proxy.validateBodyRead
import com.raofflineproxy.proxy.validateTransferEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class ProxyServerTest {

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return index
        }
        return -1
    }

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
    fun cacheKey_startsession_defaultsToSoftcoreSuffix() {
        val key = proxyCacheKey("/dorequest.php?r=startsession&g=42&u=player", "")
        assertEquals("startsession:42:player:0", key)
    }

    @Test
    fun cacheKey_startsession_ignoresIncomingHardcoreFlag() {
        val key = proxyCacheKey("/dorequest.php?r=startsession&g=42&u=player&h=1", "")
        assertEquals("startsession:42:player:0", key)
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
    fun cacheKey_achievementsetsIncludesHash() {
        val key = proxyCacheKey("/dorequest.php?r=achievementsets&m=abc123hash&u=player", "")
        assertEquals("achievementsets:abc123hash:player", key)
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

    @Test
    fun normalizedCacheKey_achievementsetsUsesPatchKey() {
        val normalizedBody = "patch:25493"

        val key = normalizedCacheKey(
            action = "achievementsets",
            path = "/dorequest.php",
            body = "r=achievementsets&u=player&m=hash",
            normalizedBody = normalizedBody
        )

        assertEquals("patch:25493:player", key)
    }

    @Test
    fun normalizedCacheKey_fallsBackWhenNormalizedBodyMissingGameId() {
        val key = normalizedCacheKey(
            action = "achievementsets",
            path = "/dorequest.php",
            body = "r=achievementsets&u=player&m=hash",
            normalizedBody = "unexpected"
        )

        assertEquals("achievementsets:hash:player", key)
    }

    @Test
    fun filterWarningAchievementIds_removesWarningAchievementId() {
        val filtered = filterWarningAchievementIds(listOf(1, 101000001, 2))

        assertEquals(listOf(1, 2), filtered)
    }

    @Test
    fun filterWarningAchievementIds_excludesNonPositiveIds() {
        val filtered = filterWarningAchievementIds(listOf(-1, 0, 101000001, 2))

        assertEquals(listOf(2), filtered)
    }

    // ── filterWarningAchievementFromPatchResponse() ──

    @Test
    fun filterWarningAchievementFromPatchResponse_noopOnMalformedJson() {
        val body = "not json"
        assertEquals(body, filterWarningAchievementFromPatchResponse(body))
    }

    @Test
    fun filterWarningAchievementFromPatchResponse_noopWhenNoPatchData() {
        val body = """{"Success":false,"Error":"not found"}"""
        assertEquals(body, filterWarningAchievementFromPatchResponse(body))
    }

    // ── filterWarningAchievementFromAchievementSetsResponse() ──

    @Test
    fun filterWarningAchievementFromAchievementSetsResponse_noopOnMalformedJson() {
        val body = "not json"
        assertEquals(body, filterWarningAchievementFromAchievementSetsResponse(body))
    }

    @Test
    fun filterWarningAchievementFromAchievementSetsResponse_noopWhenNoSets() {
        val body = """{"Success":true,"GameId":1}"""
        assertEquals(body, filterWarningAchievementFromAchievementSetsResponse(body))
    }

    // ── filterWarningAchievementForOnline() ──

    @Test
    fun filterWarningAchievementForOnline_dispatchesToPatchFilter() {
        val body = """{"Success":true,"PatchData":{"ID":1}}"""
        assertEquals(filterWarningAchievementFromPatchResponse(body), filterWarningAchievementForOnline("patch", body))
    }

    @Test
    fun filterWarningAchievementForOnline_dispatchesToAchievementSetsFilter() {
        val body = """{"Success":true,"GameId":1,"Sets":[]}"""
        assertEquals(filterWarningAchievementFromAchievementSetsResponse(body), filterWarningAchievementForOnline("achievementsets", body))
    }

    @Test
    fun filterWarningAchievementForOnline_passthroughForOtherActions() {
        val body = """{"Success":true}"""
        assertEquals(body, filterWarningAchievementForOnline("login2", body))
        assertEquals(body, filterWarningAchievementForOnline(null, body))
    }

    // ── filterWarningAchievementFromStartSessionResponse() ──

    @Test
    fun filterWarningAchievementFromStartSessionResponse_noopOnMalformedJson() {
        assertEquals("not json", filterWarningAchievementFromStartSessionResponse("not json"))
    }

    @Test
    fun filterWarningAchievementForOnline_dispatchesToStartSessionFilter() {
        val body = """{"Success":true}"""
        assertEquals(filterWarningAchievementFromStartSessionResponse(body), filterWarningAchievementForOnline("startsession", body))
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

    // ── isStaticAssetRequest() ──

    @Test
    fun isStaticAssetRequest_acceptsBadgePath() {
        assertTrue(isStaticAssetRequest("/Badge/83577.png"))
    }

    @Test
    fun isStaticAssetRequest_acceptsImagesPath() {
        assertTrue(isStaticAssetRequest("/Images/025742.png"))
    }

    @Test
    fun isStaticAssetRequest_acceptsUserPicPath() {
        assertTrue(isStaticAssetRequest("/UserPic/player.png?size=64"))
    }

    @Test
    fun isStaticAssetRequest_isCaseInsensitive() {
        assertTrue(isStaticAssetRequest("/badge/83577.png"))
    }

    @Test
    fun isStaticAssetRequest_rejectsApiPath() {
        assertFalse(isStaticAssetRequest("/dorequest.php?r=patch&g=42"))
    }

    @Test
    fun isStaticAssetRequest_rejectsLookalikePath() {
        assertFalse(isStaticAssetRequest("/BadgeHack/83577.png"))
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
    fun validateTransferEncoding_allowsChunked() {
        assertNull(validateTransferEncoding("chunked"))
        assertNull(validateTransferEncoding("gzip, chunked"))
    }

    @Test
    fun isChunkedTransferEncoding_detectsChunkedValue() {
        assertTrue(isChunkedTransferEncoding("chunked"))
        assertTrue(isChunkedTransferEncoding("gzip, chunked"))
        assertFalse(isChunkedTransferEncoding("identity"))
    }

    @Test
    fun readChunkedBody_readsAllChunks() {
        val reader = BufferedReader(StringReader("4\r\ntest\r\n6\r\n-body!\r\n0\r\n\r\n"))

        assertEquals("test-body!", readChunkedBody(reader))
    }

    @Test
    fun readChunkedBody_returnsNullForInvalidChunkSize() {
        val reader = BufferedReader(StringReader("ZZ\r\ntest\r\n0\r\n\r\n"))

        assertNull(readChunkedBody(reader))
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

    // ── proxyHttpNoContent() ──

    @Test
    fun httpNoContent_is204() {
        val response = proxyHttpNoContent()
        assertTrue(response.startsWith("HTTP/1.1 204 No Content\r\n"))
    }

    @Test
    fun httpNoContent_hasZeroContentLength() {
        val response = proxyHttpNoContent()
        assertTrue(response.contains("Content-Length: 0"))
    }

    @Test
    fun httpNoContent_hasNoBody() {
        val response = proxyHttpNoContent()
        assertTrue(response.endsWith("\r\n\r\n"))
        assertEquals("", response.substringAfter("\r\n\r\n"))
    }

    @Test
    fun httpFile_writesBinaryBodyAfterHeaders() {
        val file = File.createTempFile("proxy", ".png")
        try {
            val body = byteArrayOf(0, 1, 2, -1)
            file.writeBytes(body)

            val response = proxyHttpFile(file)
            val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            val separatorIndex = response.indexOf(separator)

            assertTrue(separatorIndex > 0)
            assertEquals(body.toList(), response.copyOfRange(separatorIndex + separator.size, response.size).toList())
        } finally {
            file.delete()
        }
    }

    @Test
    fun contentTypeForFile_detectsJpeg() {
        assertEquals("image/jpeg", contentTypeForFile(File("badge.jpg")))
    }

    @Test
    fun contentTypeForFile_defaultsToPng() {
        assertEquals("image/png", contentTypeForFile(File("badge.png")))
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
    fun buildPendingAward_returnsSignedAward() {
        val award = buildPendingAward(
            path = "/dorequest.php?r=awardachievement&a=123&u=player&t=tok&h=0",
            rawBody = "a=123&u=player&t=tok&h=0&v=abc",
            headers = mapOf("user-agent" to "rcheevos/11.4.0"),
            loadPrevHash = { "genesis" },
            signBytes = { "sig".toByteArray() },
            queuedAt = 1000L
        )

        assertNotNull(award)
        award!!
        assertEquals(123, award.achievementId)
        assertEquals(1000L, award.queuedAt)
        assertEquals("genesis", award.prevHash)
        assertEquals("c2ln", award.signature)
        assertEquals(
            sha256Hex("123|/dorequest.php?r=awardachievement&a=123&u=player&t=tok&h=0|a=123&u=player&t=tok&h=0&v=abc|1000"),
            award.payloadHash
        )
        assertNotEquals(0L, award.signedAt)
    }

    @Test
    fun buildPendingAward_returnsNullWhenSigningFails() {
        val award = buildPendingAward(
            path = "/dorequest.php?r=awardachievement&a=123",
            rawBody = "a=123&u=player&t=tok&h=0&v=abc",
            headers = emptyMap(),
            loadPrevHash = { "genesis" },
            signBytes = { throw IllegalStateException("boom") }
        )

        assertNull(award)
    }

    @Test
    fun awaitPendingAwardWrite_returnsQueuedAfterWriteCompletes() {
        val scope = CoroutineScope(Dispatchers.Default)
        val award = buildPendingAward(
            path = "/dorequest.php?r=awardachievement&a=123&u=player&t=tok&h=0",
            rawBody = "a=123&u=player&t=tok&h=0&v=abc",
            headers = mapOf("user-agent" to "rcheevos/11.4.0"),
            loadPrevHash = { "genesis" },
            signBytes = { "sig".toByteArray() },
            queuedAt = 1000L
        )!!
        var persisted = false

        val result = awaitPendingAwardWrite(scope, award, {
            Thread.sleep(50)
            persisted = true
        }, timeoutSeconds = 1)

        assertTrue(result is com.raofflineproxy.proxy.QueueAwardResult.Queued)
        assertTrue(persisted)
    }

    @Test
    fun awaitPendingAwardWrite_returnsErrorWhenWriteFails() {
        val scope = CoroutineScope(Dispatchers.Default)
        val award = buildPendingAward(
            path = "/dorequest.php?r=awardachievement&a=123&u=player&t=tok&h=0",
            rawBody = "a=123&u=player&t=tok&h=0&v=abc",
            headers = mapOf("user-agent" to "rcheevos/11.4.0"),
            loadPrevHash = { "genesis" },
            signBytes = { "sig".toByteArray() },
            queuedAt = 1000L
        )!!

        val result = awaitPendingAwardWrite(scope, award, {
            throw IllegalStateException("boom")
        }, timeoutSeconds = 1)

        assertTrue(result is com.raofflineproxy.proxy.QueueAwardResult.Error)
        result as com.raofflineproxy.proxy.QueueAwardResult.Error
        assertEquals("db_write_failed", result.message)
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
    fun isPpssppUserAgent_detectsPpsspp() {
        assertTrue(isPpssppUserAgent("PPSSPP/v1.20.4"))
        assertTrue(isPpssppUserAgent("ppsspp gold"))
        assertFalse(isPpssppUserAgent("RetroArch/1.21.0"))
    }

    @Test
    fun isGcAchievementSetsResponse_trueForGcConsoleId() {
        assertTrue(isGcAchievementSetsResponse("""{"Success":true,"ConsoleId":16}"""))
    }

    @Test
    fun isGcAchievementSetsResponse_falseForNonGcConsoleId() {
        assertFalse(isGcAchievementSetsResponse("""{"Success":true,"ConsoleId":12}"""))
    }

    @Test
    fun isWiiAchievementSetsResponse_trueForWiiConsoleId() {
        assertTrue(isWiiAchievementSetsResponse("""{"Success":true,"ConsoleId":19}"""))
    }

    @Test
    fun isWiiAchievementSetsResponse_falseForNonWiiConsoleId() {
        assertFalse(isWiiAchievementSetsResponse("""{"Success":true,"ConsoleId":12}"""))
    }

    @Test
    fun shouldCompactAchievementSets_trueForGcAchievementsets() {
        assertTrue(shouldCompactAchievementSets("achievementsets", """{"Success":true,"ConsoleId":16}"""))
    }

    @Test
    fun shouldCompactAchievementSets_trueForWiiAchievementsets() {
        assertTrue(shouldCompactAchievementSets("achievementsets", """{"Success":true,"ConsoleId":19}"""))
    }

    @Test
    fun shouldCompactAchievementSets_falseForNonAchievementsetsAction() {
        assertFalse(shouldCompactAchievementSets("patch", """{"Success":true,"ConsoleId":16}"""))
    }

    @Test
    fun compactCachedRawResponse_preservesAchievementsetsForPsp() {
        val body = """
            {"Success":true,"GameId":3537,"Title":"Game","ConsoleId":41,"ImageIconUrl":"icon","Sets":[{"Title":null,"Type":"core","AchievementSetId":2174,"GameId":3537,"ImageIconUrl":"icon","Achievements":[],"Leaderboards":[]}]} 
        """.trimIndent()

        assertEquals(body, compactCachedRawResponse("achievementsets", body))
    }

    @Test
    fun compactCachedRawResponse_compactsAchievementsetsForGc() {
        val body = """
            {"Success":true,"GameId":3537,"Title":"Game","ConsoleId":16,"ImageIconUrl":"icon","Sets":[{"Title":null,"Type":"core","AchievementSetId":2174,"GameId":3537,"ImageIconUrl":"icon","Achievements":[{"ID":101000001,"MemAddr":"1=1.300.","Title":"Warning","Description":"warning","Points":0,"Author":"","Modified":1,"Created":1,"BadgeName":"00000","Flags":3,"Type":null,"Rarity":0,"RarityHardcore":0,"BadgeURL":"badge","BadgeLockedURL":"badge_lock"}],"Leaderboards":[]}]} 
        """.trimIndent()

        assertEquals(compactAchievementSetsResponse(body), compactCachedRawResponse("achievementsets", body))
    }

    @Test
    fun compactAchievementSetsResponse_preservesJsonNulls() {
        val compacted = compactAchievementSetsResponse(
            """
            {
              "Success": true,
              "GameId": 3537,
              "Title": "God of War: Chains of Olympus",
              "ConsoleId": 41,
              "ImageIconUrl": "https://media.retroachievements.org/Images/120499.png",
              "Sets": [
                {
                  "Title": null,
                  "Type": "core",
                  "AchievementSetId": 2174,
                  "GameId": 3537,
                  "ImageIconUrl": "https://media.retroachievements.org/Images/120499.png",
                  "Achievements": [
                    {
                      "ID": 245120,
                      "MemAddr": "0xHc8440c=1",
                      "Title": "All-Powerful!",
                      "Description": "Perform a 500-hit combo",
                      "Points": 5,
                      "Author": "Anic",
                      "Modified": 1662937020,
                      "Created": 1662259904,
                      "BadgeName": "274081",
                      "Flags": 3,
                      "Type": null,
                      "Rarity": 11.7,
                      "RarityHardcore": 8.11,
                      "BadgeURL": "https://media.retroachievements.org/Badge/274081.png",
                      "BadgeLockedURL": "https://media.retroachievements.org/Badge/274081_lock.png"
                    }
                  ],
                  "Leaderboards": []
                }
              ]
            }
            """.trimIndent()
        )
        val normalized = compacted.replace(Regex("\\s+"), "")

        assertTrue(compacted, normalized.contains("\"Title\":null"))
        assertTrue(compacted, normalized.contains("\"Type\":null"))
        assertFalse(compacted.contains("\"Title\":\"null\""))
        assertFalse(compacted.contains("\"Type\":\"null\""))
    }

    @Test
    fun startsession_isNotInCacheableActions() {
        val cacheableActions = setOf("patch", "gameid", "achievements", "hashlibrary", "login2", "unlocks")
        assertFalse("startsession" in cacheableActions)
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

    @Test
    fun offlineQueuedAwardResponse_echoesRequestedAchievementId() {
        val response = proxyHttpOk(
            """{"Success":true,"Score":1234,"SoftcoreScore":0,"AchievementID":52114,"Error":"queued_offline"}"""
        )

        assertTrue(response.contains("\"Success\":true"))
        assertTrue(response.contains("\"Score\":1234"))
        assertTrue(response.contains("\"AchievementID\":52114"))
        assertTrue(response.contains("\"Error\":\"queued_offline\""))
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
