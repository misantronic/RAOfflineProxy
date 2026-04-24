package com.raofflineproxy.proxy

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.data.PENDING_AWARD_STATUS_DELETED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_FLUSHED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_PENDING
import com.raofflineproxy.data.PENDING_AWARD_STATUS_STALE
import com.raofflineproxy.extractFormParam
import com.raofflineproxy.parseFormParams
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.redactFormBody
import com.raofflineproxy.redactTokens
import com.raofflineproxy.sha256Hex
import com.raofflineproxy.sharedHttpClient
import com.raofflineproxy.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

private const val TAG = "RAProxy/AwardFlusher"
private const val MAX_RETRIES = 5
private const val GENESIS_HASH = "genesis"
internal const val MAX_AWARD_OFFSET_SECONDS = 14L * 24 * 60 * 60
private const val POST_FLUSH_REFRESH_DELAY_MS = 3_000L

sealed interface FlushEvent {
    data object Started : FlushEvent
    data class Progress(val current: Int, val total: Int) : FlushEvent
    data class Completed(
        val flushed: Int,
        val total: Int,
        val skippedDeleted: Int = 0,
        val skippedStale: Int = 0,
        val pendingRemaining: Int = 0
    ) : FlushEvent
    data class ChainBroken(val index: Int, val reason: String) : FlushEvent
    data class RefreshFailed(val reason: String) : FlushEvent
}

private sealed interface FlushResult {
    data object Success : FlushResult
    data class AuthError(val message: String) : FlushResult
    data class NetworkError(val message: String) : FlushResult
}

internal sealed interface ChainVerificationResult {
    data object Valid : ChainVerificationResult
    data class Broken(val index: Int, val reason: String) : ChainVerificationResult
}

internal fun isHardcoreAward(award: PendingAward): Boolean {
    val queryParams = parseFormParams(award.queryString.substringAfter("?", ""))
    val fromQuery = queryParams["h"]
    if (fromQuery != null) return fromQuery == "1"
    return parseFormParams(award.requestBody)["h"] == "1"
}

internal fun canonicalPayload(award: PendingAward): String =
    "${award.achievementId}|${award.queryString}|${award.requestBody}|${award.queuedAt}"

internal fun replaceOrAppendFormParam(body: String, name: String, value: String): String {
    val encoded = java.net.URLEncoder.encode(value, "UTF-8")
    val parts = body.split("&").toMutableList()
    val idx = parts.indexOfFirst { it.startsWith("$name=") }
    if (idx >= 0) parts[idx] = "$name=$encoded" else parts.add("$name=$encoded")
    return parts.joinToString("&")
}

internal fun computeValidationHash(
    achievementId: Int,
    username: String,
    hardcore: Int,
    secondsSinceUnlock: Long
): String {
    val md = MessageDigest.getInstance("MD5")
    val aidStr = achievementId.toUInt().toString()
    md.update(aidStr.toByteArray())
    md.update(username.toByteArray())
    md.update(hardcore.toString().toByteArray())
    if (secondsSinceUnlock != 0L) {
        md.update(aidStr.toByteArray())
        md.update(secondsSinceUnlock.toUInt().toString().toByteArray())
    }
    return md.digest().toHexString()
}

internal fun clampAwardOffsetSeconds(rawOffsetSeconds: Long): Long =
    rawOffsetSeconds.coerceIn(0, MAX_AWARD_OFFSET_SECONDS)

internal fun buildAwardRequestBody(
    award: PendingAward,
    nowMillis: Long = System.currentTimeMillis(),
    publicKeyBase64: () -> String = {
        runCatching { AwardKeyManager.getPublicKeyBase64() }.getOrElse { "" }
    }
): String {
    var body = award.requestBody

    val rawOffsetSeconds = (nowMillis - award.queuedAt) / 1000
    val offsetSeconds = clampAwardOffsetSeconds(rawOffsetSeconds)
    if (offsetSeconds > 0) {
        val achievementId = extractFormParam(body, "a")?.toIntOrNull() ?: award.achievementId
        val username = extractFormParam(body, "u") ?: ""
        val hardcore = extractFormParam(body, "h")?.toIntOrNull() ?: 0
        val newHash = computeValidationHash(achievementId, username, hardcore, offsetSeconds)
        body = replaceOrAppendFormParam(body, "v", newHash)
        body = replaceOrAppendFormParam(body, "o", offsetSeconds.toString())
    }

    if (award.payloadHash.isEmpty()) return body

    body = replaceOrAppendFormParam(body, "ra_chain_payload_hash", award.payloadHash)
    body = replaceOrAppendFormParam(body, "ra_chain_prev_hash", award.prevHash)
    body = replaceOrAppendFormParam(body, "ra_chain_sig", award.signature)
    body = replaceOrAppendFormParam(body, "ra_chain_pubkey", publicKeyBase64())
    return body
}

internal fun verifyChain(
    awards: List<PendingAward>,
    decodeSignature: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) },
    verifySignature: (ByteArray, ByteArray) -> Boolean = AwardKeyManager::verify
): ChainVerificationResult {
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

        if (award.signature.isEmpty()) {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): missing signature"
            )
        }

        val signatureBytes = runCatching {
            decodeSignature(award.signature)
        }.getOrElse {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): invalid base64 signature"
            )
        }

        val signInput = "${award.payloadHash}:${award.prevHash}".toByteArray(Charsets.UTF_8)
        val signatureValid = runCatching {
            verifySignature(signInput, signatureBytes)
        }.getOrElse {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): signature verification failed"
            )
        }

        if (!signatureValid) {
            return ChainVerificationResult.Broken(
                index,
                "award #${index + 1} (achievementId=${award.achievementId}): invalid signature"
            )
        }
    }
    return ChainVerificationResult.Valid
}

class AwardFlusher(
    private val context: Context,
    private val db: AppDatabase
) {

    companion object {
        private val _events = MutableSharedFlow<FlushEvent>(extraBufferCapacity = 8)
        val events = _events.asSharedFlow()
    }

    private data class LiveRefreshResult(
        val achievementIds: Set<Int>,
        val gameIds: List<Int>
    )

    private suspend fun refreshAndLoadAchievementIds(
        creds: LoginCredentials,
        userAgent: String
    ): LiveRefreshResult? {
        val patchEntries = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
        val gameIds = patchEntries.mapNotNull { entry ->
            CacheKeys.parseGameIdFromPatchKey(entry.cacheKey)
        }.distinct()

        if (gameIds.isEmpty()) {
            Log.w(TAG, "No cached patch entries — cannot determine game IDs for staleness check")
            return LiveRefreshResult(emptySet(), emptyList())
        }

        val ids = mutableSetOf<Int>()
        val ua = proxyUserAgent(userAgent)
        for (gameId in gameIds) {
            val responseBody = refreshGamePatch(context, gameId, creds, ua, db)
                ?: return null

            runCatching {
                val json = JSONObject(responseBody)
                val patchData = json.optJSONObject("PatchData") ?: return@runCatching
                val achievements = patchData.optJSONArray("Achievements") ?: return@runCatching
                for (i in 0 until achievements.length()) {
                    ids.add(achievements.getJSONObject(i).getInt("ID"))
                }
            }.onFailure { e ->
                Log.e(TAG, "Live refresh parse error for gameId=$gameId: ${e.message}")
                return null
            }
        }
        return LiveRefreshResult(ids, gameIds)
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        val awards = db.pendingAwardDao().getAll()
        if (awards.isEmpty()) return@withContext
        Log.i(TAG, "Flushing ${awards.size} queued awards")

        when (val chain = verifyChain(awards)) {
            is ChainVerificationResult.Broken -> {
                Log.w(TAG, "Chain verification failed: ${chain.reason}")
                _events.emit(FlushEvent.ChainBroken(chain.index, chain.reason))
                return@withContext
            }
            ChainVerificationResult.Valid -> {
                Log.i(TAG, "Chain verification passed")
            }
        }

        if (awards.none { it.status == PENDING_AWARD_STATUS_PENDING }) {
            val skippedDeleted = awards.count { it.status == PENDING_AWARD_STATUS_DELETED }
            val skippedStale = awards.count { it.status == PENDING_AWARD_STATUS_STALE }
            purgeProcessedAwardsIfSafe()
            _events.emit(
                FlushEvent.Completed(
                    flushed = 0,
                    total = awards.size,
                    skippedDeleted = skippedDeleted,
                    skippedStale = skippedStale,
                    pendingRemaining = 0
                )
            )
            return@withContext
        }

        val creds = loadLoginCredentials(db)
        if (creds == null) {
            Log.e(TAG, "Flush blocked — no login credentials available")
            _events.emit(FlushEvent.RefreshFailed("No login credentials available"))
            return@withContext
        }
        val userAgent = loadUserAgent(db)

        val liveRefresh = refreshAndLoadAchievementIds(creds, userAgent)
        if (liveRefresh == null) {
            Log.e(TAG, "Flush blocked — could not refresh achievement data from server")
            _events.emit(FlushEvent.RefreshFailed("Could not refresh achievement data from server. Try again later."))
            return@withContext
        }
        val knownAchievementIds = liveRefresh.achievementIds
        Log.i(TAG, "Live refresh complete: ${knownAchievementIds.size} known achievement IDs")

        _events.emit(FlushEvent.Started)

        var flushed = 0
        var skippedDeleted = 0
        var skippedStale = 0
        awards.forEachIndexed { index, award ->
            _events.emit(FlushEvent.Progress(index + 1, awards.size))

            if (award.status == PENDING_AWARD_STATUS_DELETED) {
                skippedDeleted++
                return@forEachIndexed
            }

            if (award.status == PENDING_AWARD_STATUS_STALE) {
                skippedStale++
                return@forEachIndexed
            }

            if (award.status == PENDING_AWARD_STATUS_FLUSHED) {
                return@forEachIndexed
            }

            if (isHardcoreAward(award)) {
                Log.w(TAG, "Marking stale hardcore award ${award.id} — hardcore mode is not supported")
                db.pendingAwardDao().update(
                    award.copy(
                        status = PENDING_AWARD_STATUS_STALE,
                        lastError = "Hardcore award cannot be flushed because hardcore mode is not supported"
                    )
                )
                skippedStale++
                return@forEachIndexed
            }

            if (knownAchievementIds.isNotEmpty() && award.achievementId !in knownAchievementIds) {
                Log.w(TAG, "Marking stale award ${award.id} — achievement ${award.achievementId} not found in live patch data")
                db.pendingAwardDao().update(
                    award.copy(
                        status = PENDING_AWARD_STATUS_STALE,
                        lastError = "Achievement ${award.achievementId} not found in live server data — may have been retired or modified"
                    )
                )
                skippedStale++
                return@forEachIndexed
            }

            when (val result = sendAward(award)) {
                is FlushResult.Success -> {
                    db.pendingAwardDao().update(
                        award.copy(
                            status = PENDING_AWARD_STATUS_FLUSHED,
                            lastError = null
                        )
                    )
                    flushed++
                    Log.i(TAG, "Award flushed: ${award.id}")
                }
                is FlushResult.AuthError -> {
                    Log.w(TAG, "Award ${award.id} auth error — not retrying: ${result.message}")
                    db.pendingAwardDao().update(
                        award.copy(
                            status = PENDING_AWARD_STATUS_PENDING,
                            lastError = result.message
                        )
                    )
                }
                is FlushResult.NetworkError -> {
                    val updated = award.copy(
                        status = PENDING_AWARD_STATUS_PENDING,
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

        purgeProcessedAwardsIfSafe()
        val pendingRemaining = db.pendingAwardDao().getAllByStatus().size

        if (flushed > 0) {
            Log.i(TAG, "Post-flush unlocks/session refresh in ${POST_FLUSH_REFRESH_DELAY_MS}ms for ${liveRefresh.gameIds.size} game(s)")
            delay(POST_FLUSH_REFRESH_DELAY_MS)
            for (gameId in liveRefresh.gameIds) {
                cacheUnlocks(context, gameId, creds, proxyUserAgent(userAgent))
                cacheSession(gameId, creds, db)
            }
            Log.i(TAG, "Post-flush refresh complete")
        }

        _events.emit(
            FlushEvent.Completed(
                flushed = flushed,
                total = awards.size,
                skippedDeleted = skippedDeleted,
                skippedStale = skippedStale,
                pendingRemaining = pendingRemaining
            )
        )
    }

    private suspend fun purgeProcessedAwardsIfSafe() {
        db.withTransaction {
            if (db.pendingAwardDao().existsByStatus(PENDING_AWARD_STATUS_PENDING)) {
                return@withTransaction
            }
            db.pendingAwardDao().deleteByStatuses(
                listOf(
                    PENDING_AWARD_STATUS_DELETED,
                    PENDING_AWARD_STATUS_STALE,
                    PENDING_AWARD_STATUS_FLUSHED
                )
            )
        }
    }

    private fun sendAward(award: PendingAward): FlushResult {
        return try {
            val url = "$RA_HOST${award.queryString}"
            val body = buildAwardRequestBody(award)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", proxyUserAgent(award.userAgent))
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            Log.d(TAG, "→ RA POST ${redactTokens(url)} (${request.headers.size} headers)")
            Log.d(TAG, "→ RA POST body: ${redactFormBody(body)}")

            sharedHttpClient.newCall(request).execute().use { resp ->
                val responseBody = resp.body.string()

                Log.d(TAG, "← RA ${resp.code} for ${redactTokens(award.queryString)} (${responseBody.length} bytes)")

                if (resp.code == 401 || resp.code == 403) {
                    val errorMessage = "Token rejected by server (HTTP ${resp.code})"
                    RequestFailureNotifier.report(
                        context.getString(R.string.request_failed_award_sync, award.achievementId, errorMessage)
                    )
                    return FlushResult.AuthError(errorMessage)
                }
                if (!resp.isSuccessful) {
                    val errorMessage = "HTTP ${resp.code}"
                    RequestFailureNotifier.report(
                        context.getString(R.string.request_failed_award_sync, award.achievementId, errorMessage)
                    )
                    return FlushResult.NetworkError(errorMessage)
                }
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val success = json?.optBoolean("Success", false) ?: false
                if (!success) {
                    val error = json?.optString("Error")?.takeIf { it.isNotEmpty() }
                        ?: "Server returned Success:false"
                    RequestFailureNotifier.report(
                        context.getString(R.string.request_failed_award_sync, award.achievementId, error)
                    )
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
            val errorMessage = e.message ?: context.getString(R.string.request_error_unknown_reason)
            RequestFailureNotifier.report(
                context.getString(R.string.request_failed_award_sync, award.achievementId, errorMessage)
            )
            FlushResult.NetworkError(errorMessage)
        }
    }

}
