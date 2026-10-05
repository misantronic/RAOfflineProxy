package com.raofflineproxy.service

import com.raofflineproxy.proxy.AwardSyncError
import org.json.JSONObject

internal const val PROXY_STATUS_VERSION = 2

internal enum class QueueState(val wire: String) {
    Idle("idle"),
    Caching("caching"),
    Waiting("waiting"),
    Blocked("blocked");

    companion object {
        /** [Blocked] means the queue won't move without the user: the proxy is off, or there's no
         *  valid login. Callers waiting for the queue to drain should treat it like [Idle]. */
        fun resolve(count: Int, caching: Boolean, proxyRunning: Boolean, loginBlocked: Boolean): QueueState = when {
            caching -> Caching
            count == 0 -> Idle
            !proxyRunning || loginBlocked -> Blocked
            else -> Waiting
        }
    }
}

internal enum class PendingAwardsState(val wire: String) {
    Idle("idle"),
    Waiting("waiting"),
    Syncing("syncing"),
    Blocked("blocked");

    companion object {
        /** A failed flush is only retried once RetroAchievements becomes reachable again, so
         *  [Blocked] while online means nothing will happen until connectivity drops and returns,
         *  or the proxy restarts. Callers waiting for the awards to upload should treat it like
         *  [Idle]. */
        fun resolve(
            count: Int,
            syncing: Boolean,
            proxyRunning: Boolean,
            online: Boolean,
            lastError: AwardSyncError?
        ): PendingAwardsState = when {
            proxyRunning && syncing -> Syncing
            count == 0 -> Idle
            !proxyRunning -> Blocked
            !online -> Waiting
            lastError != null -> Blocked
            else -> Waiting
        }
    }
}

internal data class ProxyStatus(
    val running: Boolean,
    val shouldBeRunning: Boolean,
    val online: Boolean,
    val queueCount: Int,
    val queueState: QueueState,
    val nextWindowAt: Long?,
    val pendingAwardsCount: Int,
    val pendingAwardsState: PendingAwardsState,
    val pendingAwardsError: AwardSyncError?
) {
    fun toJson(): String = JSONObject()
        .put("version", PROXY_STATUS_VERSION)
        .put("running", running)
        .put("shouldBeRunning", shouldBeRunning)
        .put("online", online)
        .put(
            "queue",
            JSONObject()
                .put("count", queueCount)
                .put("state", queueState.wire)
                .put("nextWindowAt", nextWindowAt?.takeIf { queueState == QueueState.Waiting } ?: JSONObject.NULL)
        )
        .put(
            "pendingAwards",
            JSONObject()
                .put("count", pendingAwardsCount)
                .put("state", pendingAwardsState.wire)
                .put(
                    "error",
                    pendingAwardsError?.takeIf { pendingAwardsState == PendingAwardsState.Blocked }?.wire
                        ?: JSONObject.NULL
                )
        )
        .toString()
}

internal enum class ControlResult(val wire: String) {
    Ok("ok"),
    NoEmulatorEnabled("no_emulator_enabled"),
    PortUnavailable("port_unavailable"),
    PatchFailed("patch_failed"),
    ForegroundServiceNotAllowed("foreground_service_not_allowed");

    companion object {
        fun from(result: HeadlessStartResult): ControlResult = when (result) {
            HeadlessStartResult.Started -> Ok
            HeadlessStartResult.NoEmulatorEnabled -> NoEmulatorEnabled
            HeadlessStartResult.PortUnavailable -> PortUnavailable
            HeadlessStartResult.PatchFailed -> PatchFailed
        }
    }
}

internal fun needsStart(running: Boolean, shouldBeRunning: Boolean): Boolean = !(running && shouldBeRunning)

internal fun needsStop(running: Boolean, shouldBeRunning: Boolean): Boolean = running || shouldBeRunning
