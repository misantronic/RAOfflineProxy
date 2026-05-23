package com.raofflineproxy

import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.proxy.ChainVerificationResult
import com.raofflineproxy.proxy.MAX_AWARD_OFFSET_SECONDS
import com.raofflineproxy.proxy.buildAwardRequestBody
import com.raofflineproxy.proxy.canonicalPayload
import com.raofflineproxy.proxy.clampAwardOffsetSeconds
import com.raofflineproxy.proxy.computeValidationHash
import com.raofflineproxy.proxy.isHardcoreAward
import com.raofflineproxy.proxy.repairPendingChain
import com.raofflineproxy.proxy.replaceOrAppendFormParam
import com.raofflineproxy.proxy.verifyChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AwardFlusherTest {

    private fun signature(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())

    private fun award(
        achievementId: Int = 100,
        queryString: String = "/dorequest.php?r=awardachievement&a=$achievementId&u=player&t=tok&h=0",
        requestBody: String = "a=$achievementId&u=player&t=tok&h=0&v=abc123",
        queuedAt: Long = 1000L,
        payloadHash: String = "",
        prevHash: String = "",
        signature: String = "",
        signedAt: Long = 0L
    ) = PendingAward(
        achievementId = achievementId,
        queryString = queryString,
        requestBody = requestBody,
        userAgent = "rcheevos/11.4.0",
        queuedAt = queuedAt,
        payloadHash = payloadHash,
        prevHash = prevHash,
        signature = signature,
        signedAt = signedAt
    )

    // ── isHardcoreAward() ──

    @Test
    fun isHardcoreAward_softcoreInQuery() {
        val a = award(queryString = "/dorequest.php?r=awardachievement&a=100&h=0")
        assertFalse(isHardcoreAward(a))
    }

    @Test
    fun isHardcoreAward_hardcoreInQuery() {
        val a = award(queryString = "/dorequest.php?r=awardachievement&a=100&h=1")
        assertTrue(isHardcoreAward(a))
    }

    @Test
    fun isHardcoreAward_hardcoreInBody() {
        val a = award(
            queryString = "/dorequest.php?r=awardachievement&a=100",
            requestBody = "a=100&u=player&t=tok&h=1&v=abc"
        )
        assertTrue(isHardcoreAward(a))
    }

    @Test
    fun isHardcoreAward_softcoreInBody() {
        val a = award(
            queryString = "/dorequest.php?r=awardachievement&a=100",
            requestBody = "a=100&u=player&t=tok&h=0&v=abc"
        )
        assertFalse(isHardcoreAward(a))
    }

    @Test
    fun isHardcoreAward_noHParam_isSoftcore() {
        val a = award(
            queryString = "/dorequest.php?r=awardachievement&a=100",
            requestBody = "a=100&u=player&t=tok&v=abc"
        )
        assertFalse(isHardcoreAward(a))
    }

    @Test
    fun isHardcoreAward_queryTakesPriorityOverBody() {
        val a = award(
            queryString = "/dorequest.php?r=awardachievement&a=100&h=0",
            requestBody = "h=1"
        )
        assertFalse(isHardcoreAward(a))
    }

    // ── canonicalPayload() ──

    @Test
    fun canonicalPayload_format() {
        val a = award(achievementId = 42, queryString = "/path", requestBody = "body", queuedAt = 9999L)
        assertEquals("42|/path|body|9999", canonicalPayload(a))
    }

    @Test
    fun canonicalPayload_emptyFields() {
        val a = award(achievementId = 0, queryString = "", requestBody = "", queuedAt = 0L)
        assertEquals("0|||0", canonicalPayload(a))
    }

    @Test
    fun canonicalPayload_deterministicForSameInput() {
        val a = award()
        assertEquals(canonicalPayload(a), canonicalPayload(a))
    }

    // ── replaceOrAppendFormParam() ──

    @Test
    fun replaceOrAppendFormParam_replacesExisting() {
        val result = replaceOrAppendFormParam("a=1&b=2&c=3", "b", "99")
        assertEquals("a=1&b=99&c=3", result)
    }

    @Test
    fun replaceOrAppendFormParam_appendsWhenMissing() {
        val result = replaceOrAppendFormParam("a=1&b=2", "c", "3")
        assertEquals("a=1&b=2&c=3", result)
    }

    @Test
    fun replaceOrAppendFormParam_replacesFirst() {
        val result = replaceOrAppendFormParam("v=old", "v", "new")
        assertEquals("v=new", result)
    }

    @Test
    fun replaceOrAppendFormParam_emptyBody() {
        val result = replaceOrAppendFormParam("", "key", "value")
        assertEquals("&key=value", result)
    }

    @Test
    fun replaceOrAppendFormParam_encodesSpecialChars() {
        val result = replaceOrAppendFormParam("a=1", "b", "hello world")
        assertEquals("a=1&b=hello+world", result)
    }

    @Test
    fun replaceOrAppendFormParam_encodesAmpersand() {
        val result = replaceOrAppendFormParam("a=1", "b", "a&b")
        assertEquals("a=1&b=a%26b", result)
    }

    // ── computeValidationHash() ──

    @Test
    fun computeValidationHash_returns32CharHex() {
        val hash = computeValidationHash(100, "player", 0, 0)
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun computeValidationHash_deterministicOutput() {
        val first = computeValidationHash(42, "user", 0, 100)
        val second = computeValidationHash(42, "user", 0, 100)
        assertEquals(first, second)
    }

    @Test
    fun computeValidationHash_differentAchievementIds_differentHashes() {
        val a = computeValidationHash(1, "user", 0, 0)
        val b = computeValidationHash(2, "user", 0, 0)
        assertTrue(a != b)
    }

    @Test
    fun computeValidationHash_differentUsers_differentHashes() {
        val a = computeValidationHash(1, "user1", 0, 0)
        val b = computeValidationHash(1, "user2", 0, 0)
        assertTrue(a != b)
    }

    @Test
    fun computeValidationHash_withAndWithoutOffset_different() {
        val noOffset = computeValidationHash(42, "user", 0, 0)
        val withOffset = computeValidationHash(42, "user", 0, 100)
        assertTrue(noOffset != withOffset)
    }

    @Test
    fun computeValidationHash_zeroOffset_excludesOffsetFromHash() {
        val noOffset = computeValidationHash(42, "user", 0, 0)
        // With offset=0, the achievementId is NOT added a second time
        // This tests that the "if (secondsSinceUnlock != 0L)" branch is taken correctly
        val withSmallOffset = computeValidationHash(42, "user", 0, 1)
        assertTrue(noOffset != withSmallOffset)
    }

    @Test
    fun computeValidationHash_hardcoreVsSoftcore_different() {
        val softcore = computeValidationHash(42, "user", 0, 0)
        val hardcore = computeValidationHash(42, "user", 1, 0)
        assertTrue(softcore != hardcore)
    }

    @Test
    fun clampAwardOffsetSeconds_withinLimit_unchanged() {
        assertEquals(60L, clampAwardOffsetSeconds(60L))
    }

    @Test
    fun clampAwardOffsetSeconds_aboveLimit_clamped() {
        assertEquals(MAX_AWARD_OFFSET_SECONDS, clampAwardOffsetSeconds(MAX_AWARD_OFFSET_SECONDS + 1))
    }

    @Test
    fun buildAwardRequestBody_usesActualOffsetWithinLimit() {
        val queuedAt = 1_000L
        val nowMillis = queuedAt + 30_000L
        val body = buildAwardRequestBody(
            award(queuedAt = queuedAt),
            nowMillis = nowMillis,
            publicKeyBase64 = { "" }
        )

        assertEquals("30", extractFormParam(body, "o"))
        assertEquals(
            computeValidationHash(100, "player", 0, 30L),
            extractFormParam(body, "v")
        )
    }

    @Test
    fun buildAwardRequestBody_clampsOffsetAtFourteenDays() {
        val queuedAt = 1_000L
        val nowMillis = queuedAt + ((MAX_AWARD_OFFSET_SECONDS + 60L) * 1000L)
        val body = buildAwardRequestBody(
            award(queuedAt = queuedAt),
            nowMillis = nowMillis,
            publicKeyBase64 = { "" }
        )

        assertEquals(MAX_AWARD_OFFSET_SECONDS.toString(), extractFormParam(body, "o"))
        assertEquals(
            computeValidationHash(100, "player", 0, MAX_AWARD_OFFSET_SECONDS),
            extractFormParam(body, "v")
        )
    }

    // ── verifyChain() ──

    @Test
    fun verifyChain_emptyList_isValid() {
        val result = verifyChain(emptyList())
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_singleLegacyAward_isValid() {
        val result = verifyChain(listOf(award(payloadHash = "")))
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_allLegacy_isValid() {
        val awards = listOf(
            award(achievementId = 1, payloadHash = ""),
            award(achievementId = 2, payloadHash = ""),
            award(achievementId = 3, payloadHash = "")
        )
        val result = verifyChain(awards)
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_validSingleSignedAward() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val payload = canonicalPayload(a)
        val hash = sha256Hex(payload)

        val signed = a.copy(
            payloadHash = hash,
            prevHash = "genesis",
            signature = signature("sig")
        )
        val result = verifyChain(
            listOf(signed),
            decodeSignature = { it.toByteArray() },
            verifySignature = { data, signatureBytes ->
                String(data, Charsets.UTF_8) == "$hash:genesis" && String(signatureBytes, Charsets.UTF_8) == signature("sig")
            }
        )
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_validChainOfTwo() {
        val a1 = award(achievementId = 1, queryString = "/p1", requestBody = "b1", queuedAt = 1000L)
        val hash1 = sha256Hex(canonicalPayload(a1))
        val signed1 = a1.copy(payloadHash = hash1, prevHash = "genesis")

        val a2 = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
        val hash2 = sha256Hex(canonicalPayload(a2))
        val signed2 = a2.copy(
            payloadHash = hash2,
            prevHash = hash1,
            signature = signature("sig2")
        )

        val signed1WithSig = signed1.copy(signature = signature("sig1"))
        val result = verifyChain(
            listOf(signed1WithSig, signed2),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> true }
        )
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_brokenPayloadHash() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val signed = a.copy(payloadHash = "tampered_hash", prevHash = "genesis")

        val result = verifyChain(listOf(signed))
        assertTrue(result is ChainVerificationResult.Broken)
        assertEquals(0, (result as ChainVerificationResult.Broken).index)
        assertTrue(result.reason.contains("payloadHash"))
    }

    @Test
    fun verifyChain_brokenPrevHash() {
        val a1 = award(achievementId = 1, queryString = "/p1", requestBody = "b1", queuedAt = 1000L)
        val hash1 = sha256Hex(canonicalPayload(a1))
        val signed1 = a1.copy(payloadHash = hash1, prevHash = "genesis")

        val a2 = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
        val hash2 = sha256Hex(canonicalPayload(a2))
        val signed2 = a2.copy(payloadHash = hash2, prevHash = "wrong_prev_hash")

        val result = verifyChain(
            listOf(signed1.copy(signature = signature("sig1")), signed2.copy(signature = signature("sig2"))),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> true }
        )
        assertTrue(result is ChainVerificationResult.Broken)
        assertEquals(1, (result as ChainVerificationResult.Broken).index)
        assertTrue(result.reason.contains("prevHash"))
    }

    @Test
    fun repairPendingChain_rebasesBrokenPendingOnlyChain() {
        val oldFirst = award(achievementId = 1, queryString = "/dorequest.php?r=awardachievement&a=1", requestBody = "a=1&u=player&t=tok&h=0&v=abc1", queuedAt = 1000L)
        val oldFirstHash = sha256Hex(canonicalPayload(oldFirst))

        val pendingFirst = award(achievementId = 2, queryString = "/dorequest.php?r=awardachievement&a=2", requestBody = "a=2&u=player&t=tok&h=0&v=abc2", queuedAt = 2000L)
        val pendingFirstHash = sha256Hex(canonicalPayload(pendingFirst))
        val pendingSecond = award(achievementId = 3, queryString = "/dorequest.php?r=awardachievement&a=3", requestBody = "a=3&u=player&t=tok&h=0&v=abc3", queuedAt = 3000L)
        val pendingSecondHash = sha256Hex(canonicalPayload(pendingSecond))

        val repaired = repairPendingChain(
            listOf(
                pendingFirst.copy(payloadHash = pendingFirstHash, prevHash = oldFirstHash, signature = signature("old-sig-2")),
                pendingSecond.copy(payloadHash = pendingSecondHash, prevHash = pendingFirstHash, signature = signature("old-sig-3"))
            ),
            signBytes = { data -> "repaired:${String(data, Charsets.UTF_8)}".toByteArray() }
        )

        assertNotNull(repaired)
        repaired!!
        assertEquals(2, repaired.size)
        assertEquals("genesis", repaired[0].prevHash)
        assertEquals(pendingFirstHash, repaired[1].prevHash)

        val result = verifyChain(
            repaired,
            decodeSignature = { Base64.getDecoder().decode(it) },
            verifySignature = { data, signatureBytes ->
                String(signatureBytes, Charsets.UTF_8) == "repaired:${String(data, Charsets.UTF_8)}"
            }
        )
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun repairPendingChain_returnsNullForPayloadHashMismatch() {
        val broken = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
            .copy(payloadHash = "wrong-hash", prevHash = "anything", signature = signature("sig"))

        val repaired = repairPendingChain(listOf(broken))

        assertEquals(null, repaired)
    }

    @Test
    fun verifyChain_legacyBeforeSigned_usesGenesis() {
        val legacy = award(achievementId = 1, payloadHash = "")

        val a2 = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
        val hash2 = sha256Hex(canonicalPayload(a2))
        val signed2 = a2.copy(payloadHash = hash2, prevHash = "genesis")

        val result = verifyChain(
            listOf(legacy, signed2.copy(signature = signature("sig"))),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> true }
        )
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_validChainOfThree() {
        val a1 = award(achievementId = 1, queryString = "/p1", requestBody = "b1", queuedAt = 1000L)
        val h1 = sha256Hex(canonicalPayload(a1))
        val s1 = a1.copy(payloadHash = h1, prevHash = "genesis")

        val a2 = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
        val h2 = sha256Hex(canonicalPayload(a2))
        val s2 = a2.copy(payloadHash = h2, prevHash = h1, signature = signature("sig2"))

        val a3 = award(achievementId = 3, queryString = "/p3", requestBody = "b3", queuedAt = 3000L)
        val h3 = sha256Hex(canonicalPayload(a3))
        val s3 = a3.copy(payloadHash = h3, prevHash = h2, signature = signature("sig3"))

        val s1WithSig = s1.copy(signature = signature("sig1"))

        val result = verifyChain(
            listOf(s1WithSig, s2, s3),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> true }
        )
        assertTrue(result is ChainVerificationResult.Valid)
    }

    @Test
    fun verifyChain_brokenMiddleLink() {
        val a1 = award(achievementId = 1, queryString = "/p1", requestBody = "b1", queuedAt = 1000L)
        val h1 = sha256Hex(canonicalPayload(a1))
        val s1 = a1.copy(payloadHash = h1, prevHash = "genesis", signature = signature("sig1"))

        val a2 = award(achievementId = 2, queryString = "/p2", requestBody = "b2", queuedAt = 2000L)
        val h2 = sha256Hex(canonicalPayload(a2))
        val s2 = a2.copy(payloadHash = h2, prevHash = "wrong", signature = signature("sig2"))

        val a3 = award(achievementId = 3, queryString = "/p3", requestBody = "b3", queuedAt = 3000L)
        val h3 = sha256Hex(canonicalPayload(a3))
        val s3 = a3.copy(payloadHash = h3, prevHash = h2, signature = signature("sig3"))

        val result = verifyChain(
            listOf(s1, s2, s3),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> true }
        )
        assertTrue(result is ChainVerificationResult.Broken)
        assertEquals(1, (result as ChainVerificationResult.Broken).index)
    }

    @Test
    fun verifyChain_missingSignature_isBroken() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val hash = sha256Hex(canonicalPayload(a))

        val result = verifyChain(listOf(a.copy(payloadHash = hash, prevHash = "genesis")))
        assertTrue(result is ChainVerificationResult.Broken)
        result as ChainVerificationResult.Broken
        assertTrue(result.reason.contains("missing signature"))
    }

    @Test
    fun verifyChain_invalidBase64Signature_isBroken() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val hash = sha256Hex(canonicalPayload(a))

        val result = verifyChain(listOf(a.copy(payloadHash = hash, prevHash = "genesis", signature = "%%%not-base64%%%")))
        assertTrue(result is ChainVerificationResult.Broken)
        result as ChainVerificationResult.Broken
        assertTrue(result.reason.contains("invalid base64 signature"))
    }

    @Test
    fun verifyChain_invalidSignature_isBroken() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val hash = sha256Hex(canonicalPayload(a))
        val signature = signature("sig")

        val result = verifyChain(
            listOf(a.copy(payloadHash = hash, prevHash = "genesis", signature = signature)),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> false }
        )
        assertTrue(result is ChainVerificationResult.Broken)
        result as ChainVerificationResult.Broken
        assertTrue(result.reason.contains("invalid signature"))
    }

    @Test
    fun verifyChain_signatureVerifierThrows_isBroken() {
        val a = award(achievementId = 1, queryString = "/path", requestBody = "body", queuedAt = 1000L)
        val hash = sha256Hex(canonicalPayload(a))
        val signature = signature("sig")

        val result = verifyChain(
            listOf(a.copy(payloadHash = hash, prevHash = "genesis", signature = signature)),
            decodeSignature = { it.toByteArray() },
            verifySignature = { _, _ -> throw IllegalStateException("boom") }
        )
        assertTrue(result is ChainVerificationResult.Broken)
        result as ChainVerificationResult.Broken
        assertTrue(result.reason.contains("signature verification failed"))
    }
}
