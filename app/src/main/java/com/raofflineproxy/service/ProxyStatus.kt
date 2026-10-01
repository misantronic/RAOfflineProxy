package com.raofflineproxy.service

import org.json.JSONObject

internal const val PROXY_STATUS_VERSION = 1

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

internal data class ProxyStatus(
    val running: Boolean,
    val shouldBeRunning: Boolean,
    val online: Boolean,
    val queueCount: Int,
    val queueState: QueueState,
    val nextWindowAt: Long?
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
