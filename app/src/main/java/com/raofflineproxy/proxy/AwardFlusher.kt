package com.raofflineproxy.proxy

import android.util.Log
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.proxyUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

private const val TAG = "RAProxy/AwardFlusher"
private const val MAX_RETRIES = 5
private const val GENESIS_HASH = "genesis"

sealed interface FlushEvent {
    data object Started : FlushEvent
    data class Progress(val current: Int, val total: Int) : FlushEvent
    data class Completed(val flushed: Int, val total: Int, val skippedStale: Int = 0) : FlushEvent
    data class ChainBroken(val index: Int, val reason: String) : FlushEvent
}

private sealed interface FlushResult {
    data object Success : FlushResult
    data class AuthError(val message: String) : FlushResult
    data class NetworkError(val message: String) : FlushResult
}

private sealed interface ChainVerificationResult {
    data object Valid : ChainVerificationResult
    data class Broken(val index: Int, val reason: String) : ChainVerificationResult
}

private fun isHardcoreAward(award: PendingAward): Boolean {
    val queryParts = award.queryString.split("?", "&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }
    val fromQuery = queryParts.firstOrNull { it.first == "h" }?.second
    if (fromQuery != null) return fromQuery == "1"

    return award.requestBody.split("&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }.firstOrNull { it.first == "h" }?.second == "1"
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun canonicalPayload(award: PendingAward): String =
    "${award.achievementId}|${award.queryString}|${award.requestBody}|${award.queuedAt}"

private fun verifyChain(awards: List<PendingAward>): ChainVerificationResult {
    awards.forEachIndexed { index, award ->
        // Legacy awards queued before anti-tamper was added have empty payloadHash — skip chain checks
        if (award.payloadHash.isEmpty()) return@forEachIndexed

        val expectedPayloadHash = sha256Hex(canonicalPayload(award))
        if (award.payloadHash != expectedPayloadHash) {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): stored payloadHash does not match recomputed hash"
            )
        }

        val expectedPrevHash = if (index == 0) {
            GENESIS_HASH
        } else {
            val prev = awards[index - 1]
            if (prev.payloadHash.isEmpty()) GENESIS_HASH else prev.payloadHash
        }
        if (award.prevHash != expectedPrevHash) {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): chain link broken — prevHash mismatch"
            )
        }
    }
    return ChainVerificationResult.Valid
}

class AwardFlusher(private val db: AppDatabase) {
    private val httpClient = OkHttpClient.Builder().build()

    companion object {
        private val _events = MutableSharedFlow<FlushEvent>(extraBufferCapacity = 8)
        val events = _events.asSharedFlow()
    }

    private suspend fun loadKnownAchievementIds(): Set<Int> {
        val patchEntries = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
        val ids = mutableSetOf<Int>()
        for (entry in patchEntries) {
            runCatching {
                val json = JSONObject(entry.responseBody)
                val patchData = json.optJSONObject("PatchData") ?: return@runCatching
                val achievements = patchData.optJSONArray("Achievements") ?: return@runCatching
                for (i in 0 until achievements.length()) {
                    ids.add(achievements.getJSONObject(i).getInt("ID"))
                }
            }
        }
        return ids
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        val pending = db.pendingAwardDao().getAll()
        if (pending.isEmpty()) return@withContext
        Log.i(TAG, "Flushing ${pending.size} pending awards")

        when (val chain = verifyChain(pending)) {
            is ChainVerificationResult.Broken -> {
                Log.w(TAG, "Chain verification failed: ${chain.reason}")
                _events.emit(FlushEvent.ChainBroken(chain.index, chain.reason))
                return@withContext
            }
            ChainVerificationResult.Valid -> {
                Log.i(TAG, "Chain verification passed")
            }
        }

        val knownAchievementIds = loadKnownAchievementIds()
        Log.i(TAG, "Loaded ${knownAchievementIds.size} known achievement IDs from cache")

        _events.emit(FlushEvent.Started)

        var flushed = 0
        var skippedStale = 0
        pending.forEachIndexed { index, award ->
            _events.emit(FlushEvent.Progress(index + 1, pending.size))
            if (isHardcoreAward(award)) {
                Log.w(TAG, "Deleting stale hardcore award ${award.id} — hardcore mode is not supported")
                db.pendingAwardDao().delete(award)
                flushed++
                return@forEachIndexed
            }
            if (knownAchievementIds.isNotEmpty() && award.achievementId !in knownAchievementIds) {
                Log.w(TAG, "Skipping stale award ${award.id} — achievement ${award.achievementId} not found in cached patch data")
                db.pendingAwardDao().update(
                    award.copy(lastError = "Achievement ${award.achievementId} not found in cached data — may have been retired or modified")
                )
                skippedStale++
                return@forEachIndexed
            }
            when (val result = sendAward(award)) {
                is FlushResult.Success -> {
                    db.pendingAwardDao().delete(award)
                    flushed++
                    Log.i(TAG, "Award flushed: ${award.id}")
                }
                is FlushResult.AuthError -> {
                    Log.w(TAG, "Award ${award.id} auth error — not retrying: ${result.message}")
                    db.pendingAwardDao().update(award.copy(lastError = result.message))
                }
                is FlushResult.NetworkError -> {
                    val updated = award.copy(
                        retryCount = award.retryCount + 1,
                        lastError = result.message
                    )
                    db.pendingAwardDao().update(updated)
                    if (updated.retryCount >= MAX_RETRIES) {
                        Log.w(TAG, "Award ${award.id} reached max retries: ${result.message}")
                    } else {
                        Log.w(TAG, "Award ${award.id} network error (retry ${updated.retryCount}/$MAX_RETRIES): ${result.message}")
                    }
                }
            }
        }
        _events.emit(FlushEvent.Completed(flushed, pending.size, skippedStale))
    }

    private fun sendAward(award: PendingAward): FlushResult {
        return try {
            val url = "$RA_HOST${award.queryString}"
            val body = buildRequestBody(award)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", proxyUserAgent(award.userAgent))
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            Log.d(TAG, "→ RA POST $url")
            request.headers.forEach { (name, value) -> Log.d(TAG, "→ RA header: $name: $value") }
            Log.d(TAG, "→ RA POST body: $body")

            httpClient.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string() ?: ""

                Log.d(TAG, "← RA ${resp.code} for ${award.queryString} body=${responseBody.take(500)}")

                if (resp.code == 401 || resp.code == 403) {
                    return FlushResult.AuthError("Token rejected by server (HTTP ${resp.code})")
                }
                if (!resp.isSuccessful) {
                    return FlushResult.NetworkError("HTTP ${resp.code}")
                }
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val success = json?.optBoolean("Success", false) ?: false
                if (!success) {
                    val error = json?.optString("Error")?.takeIf { it.isNotEmpty() }
                        ?: "Server returned Success:false"
                    val isAuthError = error.contains("Invalid", ignoreCase = true)
                        || error.contains("token", ignoreCase = true)
                        || error.contains("credentials", ignoreCase = true)
                        || error.contains("user", ignoreCase = true)
                    return if (isAuthError) FlushResult.AuthError(error) else FlushResult.NetworkError(error)
                }
                FlushResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Award flush exception for ${award.id}: ${e.message}")
            FlushResult.NetworkError(e.message ?: "Unknown network error")
        }
    }

    private fun buildRequestBody(award: PendingAward): String {
        val timestampField = "&ra_offline_unlocked_at=${award.queuedAt}"
        if (award.payloadHash.isEmpty()) return award.requestBody + timestampField
        val pubKey = runCatching { AwardKeyManager.getPublicKeyBase64() }.getOrElse { "" }
        val chainFields = buildString {
            append("&ra_chain_payload_hash=").append(award.payloadHash)
            append("&ra_chain_prev_hash=").append(award.prevHash)
            append("&ra_chain_sig=").append(award.signature)
            append("&ra_chain_pubkey=").append(pubKey)
        }
        return award.requestBody + timestampField + chainFields
    }
}
