package com.raofflineproxy.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

private const val CACHE_QUEUE_ALARM_REQUEST_CODE = 1002
// A batch of 100 games, images included, takes several minutes; the cap only matters if a drain hangs.
private const val CACHE_QUEUE_WAKE_LOCK_TIMEOUT_MS = 30L * 60 * 1000

/** The worker waits on the monotonic clock, which stops while the CPU sleeps. This alarm wakes
 *  the device when the next budget window opens, so a queue keeps moving with the screen off. */
internal object CacheQueueAlarm {
    fun schedule(context: Context, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        CACHE_QUEUE_ALARM_REQUEST_CODE,
        Intent(context, CacheQueueAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Keeps the CPU awake for one round of the queue worker. Not reference counted: the alarm
 *  receiver takes it and the worker releases it when its round ends. */
internal object CacheQueueWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun hold(context: Context) {
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RAOfflineProxy:cacheQueue")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(CACHE_QUEUE_WAKE_LOCK_TIMEOUT_MS)
    }

    @Synchronized
    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}

class CacheQueueAlarmReceiver : BroadcastReceiver() {
    // The system only holds a wake lock for onReceive, so take ours here: signalling the worker
    // alone could let the CPU fall asleep again before it runs.
    override fun onReceive(context: Context, intent: Intent) {
        if (!ProxyService.isRunningInProcess()) return
        CacheQueueWakeLock.hold(context)
        ProxyService.wakeCacheQueue()
    }
}
