package com.raofflineproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.raofflineproxy.R
import com.raofflineproxy.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val PROXY_NOTIFICATION_CHANNEL_ID = "proxy_service"
private const val STANDALONE_NOTIFICATION_ID = 2
private const val MIN_UPDATE_INTERVAL_MS = 500L
// Re-posted on every update, so it only lapses when the process died mid-run and could no
// longer remove it.
private const val STANDALONE_TIMEOUT_MS = 2L * 60 * 1000

enum class CachingPhase { Hashing, Caching }

data class CachingProgress(val phase: CachingPhase, val current: Int, val total: Int, val label: String)

internal fun CachingProgress.shortText(context: Context): String = when (phase) {
    CachingPhase.Hashing -> context.getString(R.string.notification_hashing_short, current, total)
    CachingPhase.Caching -> context.getString(R.string.notification_caching_short, current, total)
}

internal fun CachingProgress.text(context: Context): String = when (phase) {
    CachingPhase.Hashing -> context.getString(R.string.caching_hashing_progress, current, total, label)
    CachingPhase.Caching -> context.getString(R.string.caching_caching_progress, current, total, label)
}

internal fun ensureProxyNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        PROXY_NOTIFICATION_CHANNEL_ID,
        context.getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW
    ).apply { description = context.getString(R.string.notification_channel_description) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal fun openAppIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

/** Progress of a bulk caching run started from the app. The proxy notification shows it while
 *  the service runs; otherwise it gets a notification of its own for the length of the run. */
object CachingNotifications {
    private val _progress = MutableStateFlow<CachingProgress?>(null)
    val progress: StateFlow<CachingProgress?> = _progress.asStateFlow()
    @Volatile private var lastPostedAt = 0L

    fun report(context: Context, progress: CachingProgress?) {
        _progress.value = progress
        if (progress == null) {
            clearStandalone(context)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < MIN_UPDATE_INTERVAL_MS && progress.current < progress.total) return
        lastPostedAt = now
        if (ProxyService.isRunning(context)) {
            clearStandalone(context)
            return
        }
        ensureProxyNotificationChannel(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(STANDALONE_NOTIFICATION_ID, buildStandalone(context, progress))
    }

    fun clearStandalone(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(STANDALONE_NOTIFICATION_ID)
    }

    private fun buildStandalone(context: Context, progress: CachingProgress): Notification =
        Notification.Builder(context, PROXY_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_caching_title))
            .setContentText(progress.text(context))
            .setProgress(progress.total, progress.current, false)
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(STANDALONE_TIMEOUT_MS)
            .build()
}
