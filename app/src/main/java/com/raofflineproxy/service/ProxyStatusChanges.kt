package com.raofflineproxy.service

import android.content.Context
import android.util.Log
import com.raofflineproxy.ProxyConfigProvider
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.AwardSyncState
import com.raofflineproxy.proxy.CacheQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val TAG = "RAProxy/StatusChanges"

private data class QueueInputs(
    val runtime: ServiceRuntime,
    val queueCount: Int,
    val caching: Boolean,
    val nextQueueBatchAt: Long?
)

private data class StatusInputs(
    val queue: QueueInputs,
    val pendingAwards: Int,
    val awardSync: AwardSyncState
)

/** Tells observers of [ProxyConfigProvider] whenever something in its status changes, so other
 *  apps don't have to poll. Changes to the intended state are announced where they're written. */
internal fun observeProxyStatusChanges(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext
    val db = AppDatabase.getInstance(app)
    scope.launch {
        combine(
            queueInputs(db),
            db.pendingAwardDao().observeCountByStatus(),
            AwardFlusher.syncState,
            ::StatusInputs
        )
            .distinctUntilChanged()
            .drop(1)
            // Runs in every process start, so a failure here must never take the app down.
            .catch { error -> Log.w(TAG, "Status change notifications stopped", error) }
            .collect { ProxyConfigProvider.notifyStatusChanged(app) }
    }
}

private fun queueInputs(db: AppDatabase): Flow<QueueInputs> = combine(
    ProxyService.runtime,
    CacheQueue.observeCount(db),
    CachingNotifications.progress,
    CachingNotifications.queueProgress,
    CachingNotifications.nextQueueBatchAt
) { runtime, count, progress, queueProgress, nextBatchAt ->
    QueueInputs(runtime, count, progress != null || queueProgress != null, nextBatchAt)
}
