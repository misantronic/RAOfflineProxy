package com.raofflineproxy.service

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
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
import android.os.SystemClock
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
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadCachedGameRefreshTargets
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.refreshCachedGameOfflineBundle
import com.raofflineproxy.proxy.RefreshNotificationMode
import com.raofflineproxy.ui.MainActivity
import com.raofflineproxy.ui.Emulator
import com.raofflineproxy.ui.broadcastNotPatchedResult
import com.raofflineproxy.ui.configNotPatchedResult
import com.raofflineproxy.ui.loadConfigSafUri
import com.raofflineproxy.ui.requireConfigOverride
import com.raofflineproxy.ui.revertBroadcastCfg
import com.raofflineproxy.ui.revertConfigCfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "RAProxy/ProxyService"
private const val CHANNEL_ID = "proxy_service"
private const val NOTIFICATION_ID = 1
private const val REFRESH_INTERVAL_MS = 60L * 60 * 1000 // 1 hour
private const val OFFLINE_REPROBE_INTERVAL_MS = 60_000L // self-heal cadence while offline
private const val CACHE_TTL_MS = 60L * 24 * 60 * 60 * 1000 // 60 days
private const val OFFLINE_PING_IDLE_TIMEOUT_MS = 150_000L
private const val ONLINE_REFRESH_IDLE_DELAY_MS = 5L * 60 * 1000
private const val RESTART_DELAY_MS = 5_000L

class ProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var proxyServer: ProxyServer
    private lateinit var awardFlusher: AwardFlusher
    private lateinit var connectivityManager: ConnectivityManager

    private var hasInternet = false
    private var networkCallbackRegistered = false
    private var refreshJob: Job? = null
    private var reachabilityWatchdogJob: Job? = null
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
        if (!shouldKeepRunning(this)) {
            Log.i(TAG, "Ignoring start request because proxy is not marked active")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        cancelRestart(this)
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

        try {
            proxyServer.start()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start proxy server: ${error.message}", error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServerReachable()) {
            requestFlush()
        }

        if (refreshJob?.isActive != true) {
            refreshJob = serviceScope.launch { periodicRefreshLoop() }
        }

        if (reachabilityWatchdogJob?.isActive != true) {
            reachabilityWatchdogJob = serviceScope.launch { reachabilityWatchdogLoop() }
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

        return START_STICKY
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
            delay(REFRESH_INTERVAL_MS.milliseconds)
            if (!isServerReachable()) continue
            val idleDelayMs = onlineRefreshIdleDelayMs()
            if (idleDelayMs > 0) {
                Log.i(TAG, "Periodic refresh deferred; proxy active recently")
                delay(idleDelayMs.milliseconds)
                if (!isServerReachable() || onlineRefreshIdleDelayMs() > 0) continue
            }
            Log.i(TAG, "Periodic refresh started")
            val credentials = loadLoginCredentials(db)
            if (credentials == null) {
                Log.w(TAG, "Periodic refresh skipped — no credentials")
                continue
            }
            val userAgent = loadUserAgent(db)
            val refreshTargets = loadCachedGameRefreshTargets(db)
            Log.i(TAG, "Periodic refresh: ${refreshTargets.size} game(s)")
            for (target in refreshTargets) {
                if (onlineRefreshIdleDelayMs() > 0) {
                    Log.i(TAG, "Periodic refresh paused; proxy became active")
                    break
                }
                refreshCachedGameOfflineBundle(
                    context = this@ProxyService,
                    target = target,
                    creds = credentials,
                    userAgent = userAgent,
                    db = db,
                    notificationMode = RefreshNotificationMode.Background,
                    cacheImages = false,
                )
            }
            db.cacheDao().evictOlderThan(System.currentTimeMillis() - CACHE_TTL_MS)
            Log.i(TAG, "Periodic refresh complete")
        }
    }

    private suspend fun reachabilityWatchdogLoop() {
        while (true) {
            delay(OFFLINE_REPROBE_INTERVAL_MS.milliseconds)
            if (isServerReachable()) continue
            refreshReachability(forceProbe = true)
        }
    }

    override fun onDestroy() {
        if (shouldKeepRunning(this)) {
            Log.w(TAG, "Proxy service destroyed unexpectedly; scheduling restart")
            scheduleRestart(this)
        } else {
            revertPatchedCfgIfNeeded()
        }
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
        if (shouldKeepRunning(this)) {
            Log.i(TAG, "Task removed; keeping proxy alive and scheduling restart fallback")
            scheduleRestart(this)
        } else {
            Log.i(TAG, "Task removed after explicit stop")
        }
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
            .setSmallIcon(R.mipmap.ic_notification)
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
            delay(OFFLINE_PING_IDLE_TIMEOUT_MS.milliseconds)
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
            val effectiveWasReachable = if (forceProbe) {
                markRetroAchievementsUnreachable()
                updateNotification()
                false
            } else {
                wasReachable
            }
            serviceScope.launch {
                val userAgent = loadUserAgent(db)
                val reachable = probeRetroAchievements(userAgent = userAgent, force = forceProbe)
                val isReachableNow = hasInternet && reachable
                if (isReachableNow && !effectiveWasReachable) {
                    Log.i(TAG, "RetroAchievements reachable")
                    lastOfflinePingAt = 0L
                    offlineIdleTimeoutJob?.cancel()
                    requestFlush()
                } else if (!isReachableNow && effectiveWasReachable) {
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

        val configResults = Emulator.SHIZUKU_MANAGED.associateWith { emulator ->
            val config = requireConfigOverride(emulator)
            if (prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) {
                revertConfigCfg(
                    context = this,
                    emulator = emulator,
                    treeUri = loadConfigSafUri(this, emulator),
                    restoreHardcore = prefs.getBoolean(config.hardcoreWasEnabledPrefsKey, false)
                )
            } else {
                configNotPatchedResult(emulator)
            }
        }
        val broadcastResults = Emulator.BROADCAST_MANAGED.associateWith { emulator ->
            if (prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) {
                revertBroadcastCfg(this, emulator)
            } else {
                broadcastNotPatchedResult(emulator)
            }
        }

        configResults.forEach { (emulator, result) ->
            if (!result.success || result.copyBackPath != null) return@forEach
            prefs.edit {
                remove(requireConfigOverride(emulator).hardcoreWasEnabledPrefsKey)
                remove(emulator.patchedThisRunPrefsKey)
            }
            Log.i(TAG, "${emulator.displayName} config reverted during service shutdown")
        }
        broadcastResults.forEach { (emulator, result) ->
            if (!result.success) return@forEach
            prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
            Log.i(TAG, "${emulator.displayName} host override reverted during service shutdown")
        }

        val failedConfig = configResults.values.firstOrNull { !it.success || it.copyBackPath != null }
        val failedBroadcast = broadcastResults.values.firstOrNull { !it.success }
        val reason = when {
            failedConfig != null -> failedConfig.copyBackPath
                ?.let { "${failedConfig.message} copyBackPath=$it" }
                ?: failedConfig.message
            failedBroadcast != null -> failedBroadcast.message
            else -> return
        }
        Log.w(TAG, "Failed to revert emulator config during service shutdown: $reason")
    }

    companion object {
        private const val RESTART_REQUEST_CODE = 1001

        @Volatile
        private var runningInProcess = false

        private fun restartPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            RESTART_REQUEST_CODE,
            Intent(context, ProxyRestartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun shouldKeepRunning(context: Context): Boolean =
            context.getSharedPreferences(PrefsConstants.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PrefsConstants.KEY_PROXY_SHOULD_BE_RUNNING, false)

        private fun setShouldKeepRunning(context: Context, shouldRun: Boolean) {
            context.getSharedPreferences(PrefsConstants.PREFS_NAME, MODE_PRIVATE)
                .edit { putBoolean(PrefsConstants.KEY_PROXY_SHOULD_BE_RUNNING, shouldRun) }
        }

        fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
            if (!shouldKeepRunning(context)) return

            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                restartPendingIntent(context)
            )
        }

        fun cancelRestart(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(restartPendingIntent(context))
        }

        fun start(context: Context) {
            setShouldKeepRunning(context, true)
            cancelRestart(context)
            context.startForegroundService(Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context) {
            setShouldKeepRunning(context, false)
            cancelRestart(context)
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
