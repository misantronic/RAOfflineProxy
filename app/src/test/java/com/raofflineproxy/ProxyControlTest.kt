package com.raofflineproxy

import com.raofflineproxy.proxy.AwardSyncError
import com.raofflineproxy.service.ControlResult
import com.raofflineproxy.service.HeadlessStartResult
import com.raofflineproxy.service.PROXY_STATUS_VERSION
import com.raofflineproxy.service.PendingAwardsState
import com.raofflineproxy.service.ProxyStatus
import com.raofflineproxy.service.QueueState
import com.raofflineproxy.service.needsStart
import com.raofflineproxy.service.needsStop
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyControlTest {

    // ── Queue state ──

    @Test
    fun queueState_cachingWinsOverEverything() {
        assertEquals(QueueState.Caching, QueueState.resolve(count = 0, caching = true, proxyRunning = false, loginBlocked = true))
        assertEquals(QueueState.Caching, QueueState.resolve(count = 5, caching = true, proxyRunning = true, loginBlocked = false))
    }

    @Test
    fun queueState_emptyQueueIsIdle() {
        assertEquals(QueueState.Idle, QueueState.resolve(count = 0, caching = false, proxyRunning = true, loginBlocked = false))
        assertEquals(QueueState.Idle, QueueState.resolve(count = 0, caching = false, proxyRunning = false, loginBlocked = true))
    }

    @Test
    fun queueState_blockedWhenProxyStopped() {
        assertEquals(QueueState.Blocked, QueueState.resolve(count = 3, caching = false, proxyRunning = false, loginBlocked = false))
    }

    @Test
    fun queueState_blockedWithoutValidLogin() {
        assertEquals(QueueState.Blocked, QueueState.resolve(count = 3, caching = false, proxyRunning = true, loginBlocked = true))
    }

    @Test
    fun queueState_waitingBetweenBatches() {
        assertEquals(QueueState.Waiting, QueueState.resolve(count = 3, caching = false, proxyRunning = true, loginBlocked = false))
    }

    // ── Pending awards state ──

    @Test
    fun pendingAwards_syncingWinsWhileRunning() {
        assertEquals(
            PendingAwardsState.Syncing,
            PendingAwardsState.resolve(count = 3, syncing = true, proxyRunning = true, online = true, lastError = null)
        )
        assertEquals(
            PendingAwardsState.Syncing,
            PendingAwardsState.resolve(count = 0, syncing = true, proxyRunning = true, online = true, lastError = null)
        )
    }

    @Test
    fun pendingAwards_staleSyncFlagIgnoredWhenStopped() {
        assertEquals(
            PendingAwardsState.Blocked,
            PendingAwardsState.resolve(count = 3, syncing = true, proxyRunning = false, online = false, lastError = null)
        )
    }

    @Test
    fun pendingAwards_emptyIsIdle() {
        assertEquals(
            PendingAwardsState.Idle,
            PendingAwardsState.resolve(count = 0, syncing = false, proxyRunning = false, online = false, lastError = AwardSyncError.Auth)
        )
        assertEquals(
            PendingAwardsState.Idle,
            PendingAwardsState.resolve(count = 0, syncing = false, proxyRunning = true, online = true, lastError = null)
        )
    }

    @Test
    fun pendingAwards_blockedWhenProxyStopped() {
        assertEquals(
            PendingAwardsState.Blocked,
            PendingAwardsState.resolve(count = 2, syncing = false, proxyRunning = false, online = false, lastError = null)
        )
    }

    @Test
    fun pendingAwards_waitingWhileOfflineEvenAfterAFailure() {
        assertEquals(
            PendingAwardsState.Waiting,
            PendingAwardsState.resolve(count = 2, syncing = false, proxyRunning = true, online = false, lastError = null)
        )
        assertEquals(
            PendingAwardsState.Waiting,
            PendingAwardsState.resolve(count = 2, syncing = false, proxyRunning = true, online = false, lastError = AwardSyncError.UploadFailed)
        )
    }

    @Test
    fun pendingAwards_blockedOnlineAfterAFailedFlush() {
        AwardSyncError.entries.forEach { error ->
            assertEquals(
                PendingAwardsState.Blocked,
                PendingAwardsState.resolve(count = 2, syncing = false, proxyRunning = true, online = true, lastError = error)
            )
        }
    }

    @Test
    fun pendingAwards_waitingOnlineBeforeTheFirstFlush() {
        assertEquals(
            PendingAwardsState.Waiting,
            PendingAwardsState.resolve(count = 2, syncing = false, proxyRunning = true, online = true, lastError = null)
        )
    }

    // ── Status JSON ──

    @Test
    fun statusJson_carriesAllFields() {
        val json = JSONObject(
            ProxyStatus(
                running = true,
                shouldBeRunning = true,
                online = true,
                queueCount = 342,
                queueState = QueueState.Waiting,
                nextWindowAt = 1_759_230_000_000L,
                pendingAwardsCount = 4,
                pendingAwardsState = PendingAwardsState.Blocked,
                pendingAwardsError = AwardSyncError.Auth
            ).toJson()
        )
        assertEquals(PROXY_STATUS_VERSION, json.getInt("version"))
        assertTrue(json.getBoolean("running"))
        assertTrue(json.getBoolean("shouldBeRunning"))
        assertTrue(json.getBoolean("online"))
        val queue = json.getJSONObject("queue")
        assertEquals(342, queue.getInt("count"))
        assertEquals("waiting", queue.getString("state"))
        assertEquals(1_759_230_000_000L, queue.getLong("nextWindowAt"))
        val pendingAwards = json.getJSONObject("pendingAwards")
        assertEquals(4, pendingAwards.getInt("count"))
        assertEquals("blocked", pendingAwards.getString("state"))
        assertEquals("auth", pendingAwards.getString("error"))
    }

    @Test
    fun statusJson_nextWindowOnlyWhileWaiting() {
        val blocked = ProxyStatus(
            running = false,
            shouldBeRunning = false,
            online = false,
            queueCount = 3,
            queueState = QueueState.Blocked,
            nextWindowAt = 1_759_230_000_000L,
            pendingAwardsCount = 0,
            pendingAwardsState = PendingAwardsState.Idle,
            pendingAwardsError = null
        )
        assertTrue(JSONObject(blocked.toJson()).getJSONObject("queue").isNull("nextWindowAt"))

        val waitingWithoutWindow = blocked.copy(running = true, queueState = QueueState.Waiting, nextWindowAt = null)
        assertTrue(JSONObject(waitingWithoutWindow.toJson()).getJSONObject("queue").isNull("nextWindowAt"))
    }

    @Test
    fun statusJson_pendingAwardsErrorOnlyWhileBlocked() {
        val status = ProxyStatus(
            running = true,
            shouldBeRunning = true,
            online = false,
            queueCount = 0,
            queueState = QueueState.Idle,
            nextWindowAt = null,
            pendingAwardsCount = 2,
            pendingAwardsState = PendingAwardsState.Waiting,
            pendingAwardsError = AwardSyncError.UploadFailed
        )
        assertTrue(JSONObject(status.toJson()).getJSONObject("pendingAwards").isNull("error"))

        val blockedByStop = status.copy(running = false, pendingAwardsState = PendingAwardsState.Blocked, pendingAwardsError = null)
        assertTrue(JSONObject(blockedByStop.toJson()).getJSONObject("pendingAwards").isNull("error"))
    }

    @Test
    fun statusJson_usesWireNamesForStates() {
        assertEquals(listOf("idle", "caching", "waiting", "blocked"), QueueState.entries.map { it.wire })
        assertEquals(listOf("idle", "waiting", "syncing", "blocked"), PendingAwardsState.entries.map { it.wire })
        assertEquals(
            listOf("auth", "chain_broken", "refresh_failed", "upload_failed"),
            AwardSyncError.entries.map { it.wire }
        )
    }

    // ── Idempotent start/stop ──

    @Test
    fun start_isNoOpOnlyWhenRunningAndIntended() {
        assertFalse(needsStart(running = true, shouldBeRunning = true))
        assertTrue(needsStart(running = false, shouldBeRunning = false))
        assertTrue(needsStart(running = false, shouldBeRunning = true))
        assertTrue(needsStart(running = true, shouldBeRunning = false))
    }

    @Test
    fun stop_isNoOpOnlyWhenStoppedAndNotIntended() {
        assertFalse(needsStop(running = false, shouldBeRunning = false))
        assertTrue(needsStop(running = true, shouldBeRunning = true))
        assertTrue(needsStop(running = false, shouldBeRunning = true))
        assertTrue(needsStop(running = true, shouldBeRunning = false))
    }

    // ── Result codes ──

    @Test
    fun controlResult_mapsEveryHeadlessOutcome() {
        assertEquals(ControlResult.Ok, ControlResult.from(HeadlessStartResult.Started))
        assertEquals(ControlResult.NoEmulatorEnabled, ControlResult.from(HeadlessStartResult.NoEmulatorEnabled))
        assertEquals(ControlResult.PortUnavailable, ControlResult.from(HeadlessStartResult.PortUnavailable))
        assertEquals(ControlResult.PatchFailed, ControlResult.from(HeadlessStartResult.PatchFailed))
    }

    @Test
    fun controlResult_wireNamesAreStable() {
        assertEquals(
            listOf("ok", "no_emulator_enabled", "port_unavailable", "patch_failed", "foreground_service_not_allowed"),
            ControlResult.entries.map { it.wire }
        )
    }
}
