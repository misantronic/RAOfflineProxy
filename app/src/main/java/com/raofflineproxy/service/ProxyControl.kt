package com.raofflineproxy.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import android.util.Log
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.CacheQueue
import kotlinx.coroutines.runBlocking

private const val TAG = "RAProxy/ProxyControl"

/** Start, stop and status for other apps. Blocking: callers run it off the main thread. Start
 *  and stop are idempotent and never touch the autostart setting. */
internal object ProxyControl {
    private val lock = Any()

    fun start(context: Context): ControlResult = synchronized(lock) {
        if (!needsStart(ProxyService.isRunning(context), ProxyService.shouldKeepRunning(context))) {
            return ControlResult.Ok
        }
        try {
            ControlResult.from(HeadlessProxy.start(context))
        } catch (error: IllegalStateException) {
            if (!isForegroundServiceStartBlocked(error)) throw error
            Log.w(TAG, "Android blocked starting the proxy from the background", error)
            HeadlessProxy.stop(context)
            ControlResult.ForegroundServiceNotAllowed
        }
    }

    fun stop(context: Context): ControlResult = synchronized(lock) {
        if (needsStop(ProxyService.isRunning(context), ProxyService.shouldKeepRunning(context))) {
            HeadlessProxy.stop(context)
        }
        ControlResult.Ok
    }

    fun status(context: Context): ProxyStatus {
        val running = ProxyService.isRunning(context)
        val runtime = ProxyService.runtime.value
        val online = running && runtime.online
        val db = AppDatabase.getInstance(context)
        val count = runBlocking { CacheQueue.count(db) }
        val pendingAwards = runBlocking { db.pendingAwardDao().countByStatus() }
        val sync = AwardFlusher.syncState.value
        val caching = CachingNotifications.progress.value != null || CachingNotifications.queueProgress.value != null
        return ProxyStatus(
            running = running,
            shouldBeRunning = ProxyService.shouldKeepRunning(context),
            online = online,
            queueCount = count,
            queueState = QueueState.resolve(count, caching, running, runtime.queueLoginBlocked),
            nextWindowAt = CachingNotifications.nextQueueBatchAt.value,
            pendingAwardsCount = pendingAwards,
            pendingAwardsState = PendingAwardsState.resolve(pendingAwards, sync.syncing, running, online, sync.lastError),
            pendingAwardsError = sync.lastError
        )
    }

    private fun isForegroundServiceStartBlocked(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException
}
