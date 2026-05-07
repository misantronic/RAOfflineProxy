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
import com.raofflineproxy.hasValidatedInternet
import com.raofflineproxy.isValidatedNetwork
import com.raofflineproxy.isRetroAchievementsReachable
import com.raofflineproxy.markRetroAchievementsUnreachable
import com.raofflineproxy.probeRetroAchievements
import com.raofflineproxy.proxyPort
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.GameActivity
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
import org.json.JSONObject

private const val TAG = "ProxyService"
private const val CHANNEL_ID = "proxy_service"
private const val NOTIFICATION_ID = 1
private const val REFRESH_INTERVAL_MS = 60L * 60 * 1000 // 1 hour
private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
private const val OFFLINE_PING_IDLE_TIMEOUT_MS = 150_000L
private const val ONLINE_REFRESH_IDLE_DELAY_MS = 5L * 60 * 1000

class ProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var proxyServer: ProxyServer
    private lateinit var awardFlusher: AwardFlusher
    private lateinit var connectivityManager: ConnectivityManager

    private var hasInternet = false
    private var networkCallbackRegistered = false
    private var refreshJob: Job? = null
    private var flushJob: Job? = null
    private var pendingObserverJob: Job? = null
    private var offlineIdleTimeoutJob: Job? = null
    private var pendingCount = 0
    private var cfgCleanupAttempted = false
    @Volatile private var recentGameId: String? = null
    @Volatile private var recentGameTitle: String? = null
    @Volatile private var lastGameActivityAt = 0L
    @Volatile private var lastProxyActivityAt = 0L
    @Volatile private var lastOfflinePingAt = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshReachability(forceProbe = false)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshReachability(forceProbe = false, capabilities = networkCapabilities)
        }

        override fun onLost(network: Network) {
            refreshReachability(forceProbe = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInProcess = true
        db = AppDatabase.getInstance(this)
        awardFlusher = AwardFlusher(this, db)
        proxyServer = ProxyServer(
            context = this,
            db = db,
            scope = serviceScope,
            port = proxyPort(this),
            isOnline = ::isServerReachable,
            onGameActivity = ::onGameActivity
        )
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

        refreshReachability(forceProbe = true)

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

        if (isServerReachable()) {
            requestFlush()
        }

        if (refreshJob?.isActive != true) {
            refreshJob = serviceScope.launch { periodicRefreshLoop() }
        }

        if (pendingObserverJob?.isActive != true) {
            pendingObserverJob = serviceScope.launch {
                db.pendingAwardDao().observeByStatus().collect { awards ->
                    val newCount = awards.size
                    if (newCount != pendingCount) {
                        pendingCount = newCount
                        updateNotification()
                    }
                }
            }
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
            if (!isServerReachable()) continue
            val idleDelayMs = onlineRefreshIdleDelayMs()
            if (idleDelayMs > 0) {
                Log.i(TAG, "Periodic refresh deferred; proxy active recently")
                delay(idleDelayMs)
                if (!isServerReachable() || onlineRefreshIdleDelayMs() > 0) continue
            }
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
                if (onlineRefreshIdleDelayMs() > 0) {
                    Log.i(TAG, "Periodic refresh paused; proxy became active")
                    break
                }
                cacheGame(this@ProxyService, gameId, credentials, userAgent, db, cacheImages = false)
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
        val (title, text) = if (isServerReachable())
            getString(R.string.notification_online_title) to getString(R.string.notification_online_text)
        else {
            val offlineText = buildString {
                append(resolveOfflineStatusText())
                if (pendingCount > 0) {
                    append(" · ")
                    append(resources.getQuantityString(R.plurals.notification_pending_awards, pendingCount, pendingCount))
                }
            }
            getString(R.string.notification_offline_title) to offlineText
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun onGameActivity(activity: GameActivity) {
        val gameId = activity.gameId
        lastGameActivityAt = System.currentTimeMillis()
        lastProxyActivityAt = lastGameActivityAt
        if (gameId != recentGameId) {
            recentGameId = gameId
            recentGameTitle = null
            serviceScope.launch {
                val title = loadCachedGameTitle(gameId)
                if (recentGameId == gameId) {
                    recentGameTitle = title
                    if (!isServerReachable()) {
                        updateNotification()
                    }
                }
            }
        }

        if (!isServerReachable() && activity.action == "ping") {
            lastOfflinePingAt = lastGameActivityAt
        }

        if (!isServerReachable()) {
            scheduleOfflineIdleTimeout(currentOfflineActivityAt())
            updateNotification()
        }
    }

    private fun resolveOfflineStatusText(): String {
        val lastActivityAt = currentOfflineActivityAt()
        if (lastActivityAt == 0L) return getString(R.string.notification_offline_idle_text)
        if (System.currentTimeMillis() - lastActivityAt > OFFLINE_PING_IDLE_TIMEOUT_MS) {
            recentGameId = null
            recentGameTitle = null
            lastGameActivityAt = 0L
            lastOfflinePingAt = 0L
            return getString(R.string.notification_offline_idle_text)
        }

        val gameTitle = recentGameTitle?.takeIf { it.isNotBlank() }
        return if (gameTitle != null) {
            getString(R.string.notification_offline_active_text, gameTitle)
        } else {
            getString(R.string.notification_offline_text)
        }
    }

    private suspend fun loadCachedGameTitle(gameId: String): String? {
        val patchEntry = db.cacheDao().getByPrefix(CacheKeys.patchPrefix(gameId)) ?: return null
        return runCatching {
            val json = JSONObject(patchEntry.responseBody)
            json.optJSONObject("PatchData")
                ?.optString("Title")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("Title").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun scheduleOfflineIdleTimeout(expectedActivityAt: Long) {
        if (expectedActivityAt == 0L) return
        offlineIdleTimeoutJob?.cancel()
        offlineIdleTimeoutJob = serviceScope.launch {
            delay(OFFLINE_PING_IDLE_TIMEOUT_MS)
            if (!isServerReachable() && currentOfflineActivityAt() == expectedActivityAt) {
                updateNotification()
            }
        }
    }

    private fun currentOfflineActivityAt(): Long = maxOf(lastGameActivityAt, lastOfflinePingAt)

    private fun isServerReachable(): Boolean = hasInternet && isRetroAchievementsReachable()

    private fun refreshReachability(
        forceProbe: Boolean,
        capabilities: NetworkCapabilities? = null
    ) {
        val validated = capabilities?.let(::isValidatedNetwork)
            ?: hasValidatedInternet(connectivityManager)
        val wasReachable = isServerReachable()
        hasInternet = validated

        if (!validated) {
            markRetroAchievementsUnreachable()
        } else {
            serviceScope.launch {
                val userAgent = loadUserAgent(db)
                val reachable = probeRetroAchievements(userAgent = userAgent, force = forceProbe)
                val isReachableNow = hasInternet && reachable
                if (isReachableNow && !wasReachable) {
                    Log.i(TAG, "RetroAchievements reachable")
                    lastOfflinePingAt = 0L
                    offlineIdleTimeoutJob?.cancel()
                    requestFlush()
                } else if (!isReachableNow && wasReachable) {
                    Log.i(TAG, "RetroAchievements unreachable")
                    if (recentGameId != null) {
                        scheduleOfflineIdleTimeout(currentOfflineActivityAt())
                    }
                }
                updateNotification()
            }
            return
        }

        if (!isServerReachable() && recentGameId != null) {
            scheduleOfflineIdleTimeout(currentOfflineActivityAt())
        }
        updateNotification()
    }

    private fun onlineRefreshIdleDelayMs(): Long {
        val lastActivityAt = maxOf(lastGameActivityAt, lastProxyActivityAt)
        if (lastActivityAt == 0L) return 0L
        return (ONLINE_REFRESH_IDLE_DELAY_MS - (System.currentTimeMillis() - lastActivityAt)).coerceAtLeast(0L)
    }

    private fun revertPatchedCfgIfNeeded() {
        if (cfgCleanupAttempted) return

        cfgCleanupAttempted = true
        val prefs = getSharedPreferences(PrefsConstants.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT, false)) {
            prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }
            Log.i(TAG, "Skipping RetroArch cfg revert; UI already handled it")
            return
        }

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
