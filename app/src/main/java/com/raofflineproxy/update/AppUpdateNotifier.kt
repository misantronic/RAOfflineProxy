package com.raofflineproxy.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.hasValidatedInternet
import com.raofflineproxy.service.openAppIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RAProxy/Updates"
private const val APP_UPDATE_CHANNEL_ID = "app_update"
private const val APP_UPDATE_NOTIFICATION_ID = 3
private const val APP_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

internal fun isAppUpdatePromptDue(lastPromptedAt: Long, now: Long): Boolean =
    now - lastPromptedAt >= APP_UPDATE_INTERVAL_MS

internal object AppUpdateNotifier {
    suspend fun notifyIfDue(context: Context, connectivityManager: ConnectivityManager) {
        if (!PrefsConstants.loadAppUpdateCheckEnabled(context)) return
        if (!hasValidatedInternet(connectivityManager)) return

        val now = System.currentTimeMillis()
        if (!isAppUpdatePromptDue(PrefsConstants.loadAppUpdateLastPromptedAt(context), now)) return

        PrefsConstants.saveAppUpdateLastCheckedAt(context, now)
        val update = fetchUpdateOrNull()
        if (update == null) {
            PrefsConstants.clearAvailableAppUpdate(context)
            return
        }

        Log.i(TAG, "App update available in background: ${update.versionName}")
        PrefsConstants.saveAvailableAppUpdate(context, update)
        if (post(context, update)) {
            PrefsConstants.saveAppUpdateLastPromptedAt(context, now)
        }
    }

    private suspend fun fetchUpdateOrNull(): AppUpdateInfo? =
        try {
            withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatestUpdate(BuildConfig.VERSION_NAME) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background update check failed: ${e.message ?: e::class.java.simpleName}")
            null
        }

    private fun post(context: Context, update: AppUpdateInfo): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                APP_UPDATE_CHANNEL_ID,
                context.getString(R.string.app_update_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.notify(APP_UPDATE_NOTIFICATION_ID, buildNotification(context, update))
        return true
    }

    private fun buildNotification(context: Context, update: AppUpdateInfo): Notification =
        Notification.Builder(context, APP_UPDATE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_update_dialog_title))
            .setContentText(
                context.getString(R.string.app_update_dialog_message, update.versionName, BuildConfig.VERSION_NAME)
            )
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
}
