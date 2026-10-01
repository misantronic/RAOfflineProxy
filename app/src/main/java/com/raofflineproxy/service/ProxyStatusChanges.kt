package com.raofflineproxy.service

import android.content.Context
import com.raofflineproxy.ProxyConfigProvider
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.proxy.CacheQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private data class StatusInputs(
    val runtime: ServiceRuntime,
    val queueCount: Int,
    val caching: Boolean,
    val nextQueueBatchAt: Long?
)

/** Tells observers of [ProxyConfigProvider] whenever something in its status changes, so other
 *  apps don't have to poll. Changes to the intended state are announced where they're written. */
internal fun observeProxyStatusChanges(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext
    scope.launch {
        combine(
            ProxyService.runtime,
            CacheQueue.observeCount(AppDatabase.getInstance(app)),
            CachingNotifications.progress,
            CachingNotifications.queueProgress,
            CachingNotifications.nextQueueBatchAt
        ) { runtime, count, progress, queueProgress, nextBatchAt ->
            StatusInputs(runtime, count, progress != null || queueProgress != null, nextBatchAt)
        }
            .distinctUntilChanged()
            .drop(1)
            .collect { ProxyConfigProvider.notifyStatusChanged(app) }
    }
}
