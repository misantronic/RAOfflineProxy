package com.raofflineproxy.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.raofflineproxy.R
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.ProxyServer
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            isOnline = true
            serviceScope.launch { awardFlusher.flush() }
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
        db = AppDatabase.getInstance(this)
        awardFlusher = AwardFlusher(db)
        proxyServer = ProxyServer(db, serviceScope) { isOnline }
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        isOnline = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )

        proxyServer.start()

        if (isOnline) {
            serviceScope.launch { awardFlusher.flush() }
        }

        serviceScope.launch { periodicRefreshLoop() }

        return START_STICKY
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
                cacheGame(gameId, credentials, userAgent, db)
            }
            db.cacheDao().evictOlderThan(System.currentTimeMillis() - CACHE_TTL_MS)
            Log.i(TAG, "Periodic refresh complete")
        }
    }

    override fun onDestroy() {
        proxyServer.stop()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        serviceScope.cancel()
        super.onDestroy()
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

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
