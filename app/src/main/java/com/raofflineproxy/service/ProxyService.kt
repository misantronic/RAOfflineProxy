package com.raofflineproxy.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.proxyPort
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.ProxyServer
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.ui.MainActivity
import com.raofflineproxy.ui.revertRetroArchCfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ProxyService"
private const val CHANNEL_ID = "proxy_service"
private const val NOTIFICATION_ID = 1
private const val REFRESH_INTERVAL_MS = 60L * 60 * 1000 // 1 hour
private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

class ProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var proxyServer: ProxyServer
    private lateinit var awardFlusher: AwardFlusher
    private lateinit var connectivityManager: ConnectivityManager

    private var isOnline = false
    private var networkCallbackRegistered = false
    private var refreshJob: Job? = null
    private var flushJob: Job? = null
    private var cfgCleanupAttempted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            isOnline = true
            requestFlush()
            updateNotification()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            isOnline = false
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInProcess = true
        db = AppDatabase.getInstance(this)
        awardFlusher = AwardFlusher(this, db)
        proxyServer = ProxyServer(this, db, serviceScope, proxyPort(this)) { isOnline }
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        isOnline = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!networkCallbackRegistered) {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
            networkCallbackRegistered = true
        }

        proxyServer.start()

        if (isOnline) {
            requestFlush()
        }

        if (refreshJob?.isActive != true) {
            refreshJob = serviceScope.launch { periodicRefreshLoop() }
        }

        return START_NOT_STICKY
    }

    private fun requestFlush() {
        if (flushJob?.isActive == true) return
        flushJob = serviceScope.launch {
            try {
                awardFlusher.flush()
            } finally {
                flushJob = null
            }
        }
    }

    private suspend fun periodicRefreshLoop() {
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            if (!isOnline) continue
            Log.i(TAG, "Periodic refresh started")
            val credentials = loadLoginCredentials(db)
            if (credentials == null) {
                Log.w(TAG, "Periodic refresh skipped — no credentials")
                continue
            }
            val userAgent = loadUserAgent(db)
            val gameIds = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)
                .mapNotNull { entry -> CacheKeys.parseGameIdFromPatchKey(entry.cacheKey) }
                .distinct()
            Log.i(TAG, "Periodic refresh: ${gameIds.size} game(s)")
            for (gameId in gameIds) {
                cacheGame(this@ProxyService, gameId, credentials, userAgent, db)
            }
            db.cacheDao().evictOlderThan(System.currentTimeMillis() - CACHE_TTL_MS)
            Log.i(TAG, "Periodic refresh complete")
        }
    }

    override fun onDestroy() {
        revertPatchedCfgIfNeeded()
        runningInProcess = false
        proxyServer.stop()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; stopping proxy service")
        revertPatchedCfgIfNeeded()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val (title, text) = if (isOnline)
            getString(R.string.notification_online_title) to getString(R.string.notification_online_text)
        else
            getString(R.string.notification_offline_title) to getString(R.string.notification_offline_text)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun revertPatchedCfgIfNeeded() {
        if (cfgCleanupAttempted) return

        cfgCleanupAttempted = true
        val prefs = getSharedPreferences(PrefsConstants.PREFS_NAME, MODE_PRIVATE)
        val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_HARDCORE_WAS_ENABLED, false)
        val result = revertRetroArchCfg(this, PrefsConstants.loadSafUri(this), restoreHardcore)
        val revertedTarget = result.success && result.copyBackPath == null

        if (revertedTarget) {
            prefs.edit { remove(PrefsConstants.KEY_HARDCORE_WAS_ENABLED) }
            Log.i(TAG, "RetroArch cfg reverted during service shutdown")
            return
        }

        val reason = if (result.copyBackPath != null) {
            "${result.message} copyBackPath=${result.copyBackPath}"
        } else {
            result.message
        }
        Log.w(TAG, "Failed to revert RetroArch cfg during service shutdown: $reason")
    }

    companion object {
        @Volatile
        private var runningInProcess = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            if (runningInProcess) return true

            val manager = context.getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                ?: return false

            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ProxyService::class.java.name }
        }
    }
}
