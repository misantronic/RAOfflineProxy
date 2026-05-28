package com.raofflineproxy.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raofflineproxy.BuildConfig
import com.raofflineproxy.MAX_CACHED_GAMES
import com.raofflineproxy.buildApiUrl
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.RequestFailureNotifier
import com.raofflineproxy.hasValidatedInternet
import com.raofflineproxy.isValidatedNetwork
import com.raofflineproxy.isRetroAchievementsReachable
import com.raofflineproxy.markRetroAchievementsUnreachable
import com.raofflineproxy.parseFormParams
import com.raofflineproxy.probeRetroAchievements
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.data.PENDING_AWARD_STATUS_DELETED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_FLUSHED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_PENDING
import com.raofflineproxy.data.UnlockedAchievement
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.FlushEvent
import com.raofflineproxy.proxy.LoginCredentials
import com.raofflineproxy.proxy.PasswordCredentials
import com.raofflineproxy.proxy.patchImageUrl
import com.raofflineproxy.proxy.cacheLoginCredentialsResponse
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.clearAllCachedImages
import com.raofflineproxy.proxy.deleteCachedImagesForGame
import com.raofflineproxy.proxy.HttpGetResult
import com.raofflineproxy.proxy.httpGet
import com.raofflineproxy.proxy.loginAndCacheToken
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.runSmartCache
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.compactCachedRawResponse
import com.raofflineproxy.proxy.normalizeCachedResponse
import com.raofflineproxy.proxy.resolveCachedGameIconPath
import com.raofflineproxy.proxy.scanRomFolder
import com.raofflineproxy.proxy.SmartCacheEmulator
import com.raofflineproxy.service.ProxyService
import com.raofflineproxy.update.AppUpdateChecker
import com.raofflineproxy.update.AppUpdateInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AuthState { Unknown, Valid, Invalid }

enum class SafGrantTarget { RetroArch, SmartCacheRetroArch, Dolphin, SmartCacheRom, AllFilesAccess }

sealed interface MainUiEvent {
    data object PromptSmartCacheAfterProxyStart : MainUiEvent
    data object PromptManualCredentials : MainUiEvent
    data class ShowAppUpdate(val update: AppUpdateInfo) : MainUiEvent
}

data class MainUiState(
    val proxyRunning: Boolean = false,
    val proxyToggleInProgress: Boolean = false,
    val isOnline: Boolean = false,
    val hasLoginCredentials: Boolean = false,
    val authState: AuthState = AuthState.Unknown,
    val autostartProxy: Boolean = false,
    val manualEmulatorPatchingEnabled: Boolean = false,
    val smartCachingEnabled: Boolean = true,
    val appUpdateCheckEnabled: Boolean = true,
    val proxyPort: Int = PrefsConstants.DEFAULT_PROXY_PORT,
    val retroArchInstalled: Boolean = false,
    val dolphinInstalled: Boolean = false,
    val retroArchEnabled: Boolean = false,
    val dolphinEnabled: Boolean = false,
    val pendingAwards: List<PendingAwardUi> = emptyList(),
    val awardHistory: List<PendingAwardUi> = emptyList(),
    val cachedGames: List<CachedGame> = emptyList(),
    val needsSafGrant: Boolean = false,
    val safGrantTarget: SafGrantTarget? = null,
    val pendingSafGrantTargets: List<SafGrantTarget> = emptyList(),
    val cfgCopyBackPath: String? = null,
    val cfgIsPatched: Boolean? = null,
    val scanInProgress: Boolean = false,
    val scanProgress: String? = null,
    val flushInProgress: Boolean = false,
    val availableAppUpdate: AppUpdateInfo? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }

    private val application = app
    private val db = AppDatabase.getInstance(app)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private val _cachedGames = MutableStateFlow<List<CachedGame>>(emptyList())
    val cachedGames: StateFlow<List<CachedGame>> = _cachedGames.asStateFlow()
    private val connectivityManager =
        app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var pendingProxyStart = false
    private var pendingSmartCacheStart = false
    private var pendingSmartCachePromptAfterProxyStart = false
    private var pendingSmartCacheRomGrantPaths = emptyList<String>()
    private var pendingSmartCacheGrantTargets = emptyList<SafGrantTarget>()
    private var smartCacheAllFilesRejectedThisRun = false
    private fun str(resId: Int): String = getApplication<Application>().getString(resId)
    private fun str(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)

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

    init {
        _state.value = _state.value.copy(
            isOnline = hasValidatedInternet(connectivityManager) && isRetroAchievementsReachable()
        )
        refreshReachability(forceProbe = true)

        recoverPatchedCfgIfProxyStopped()

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )

        checkCfgPatched(treeUri = loadSafUri())
        viewModelScope.launch(Dispatchers.IO) {
            compactCachedShadowPatches()
            compactCachedRawAchievementSets()
        }
        val emulatorSupport = loadEmulatorSupport(app)
        Log.i(
            "RAProxy/Emulators",
            "init support retroArchInstalled=${emulatorSupport.retroArchInstalled} dolphinInstalled=${emulatorSupport.dolphinInstalled} retroArchEnabled=${emulatorSupport.retroArchEnabled} dolphinEnabled=${emulatorSupport.dolphinEnabled}"
        )
        _state.value = _state.value.copy(
            autostartProxy = loadAutostartPref(),
            manualEmulatorPatchingEnabled = loadManualEmulatorPatchingEnabled(),
            smartCachingEnabled = loadSmartCachingEnabled(),
            appUpdateCheckEnabled = loadAppUpdateCheckEnabled(),
            proxyPort = PrefsConstants.loadProxyPort(app),
            retroArchInstalled = emulatorSupport.retroArchInstalled,
            dolphinInstalled = emulatorSupport.dolphinInstalled,
            retroArchEnabled = emulatorSupport.retroArchEnabled,
            dolphinEnabled = emulatorSupport.dolphinEnabled
        )
        exportManualSetupConfig()
        restoreDolphinCredentialsOnLaunch(emulatorSupport)
        validateToken()
        viewModelScope.launch {
            AwardFlusher.events.collect { event ->
                when (event) {
                    is FlushEvent.Started -> _state.value = _state.value.copy(flushInProgress = true)
                    is FlushEvent.Progress -> Unit
                    is FlushEvent.Completed -> {
                        SnackbarManager.showMessage(str(R.string.flush_completed_sent_only, event.flushed), SnackbarDuration.Indefinite)
                        _state.value = _state.value.copy(
                            flushInProgress = false
                        )
                    }
                    is FlushEvent.ChainBroken -> {
                        SnackbarManager.showMessage(str(R.string.flush_chain_broken, event.index + 1, event.reason), SnackbarDuration.Indefinite)
                        _state.value = _state.value.copy(flushInProgress = false)
                    }
                    is FlushEvent.RefreshFailed -> {
                        SnackbarManager.showMessage(str(R.string.flush_refresh_failed, event.reason), SnackbarDuration.Indefinite)
                        _state.value = _state.value.copy(flushInProgress = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                db.pendingAwardDao().observeByStatus(),
                db.pendingAwardDao().observeByStatus(PENDING_AWARD_STATUS_FLUSHED),
                db.cacheDao().observePatchEntries(),
                db.cacheDao().observeByPrefix(CacheKeys.PREFIX_LOGIN)
            ) { awards, historyAwards, entries, loginEntries ->
                val resolvedAwards = awards.map { award -> resolvePendingAward(award) }
                val resolvedHistoryAwards = historyAwards.map { award -> resolvePendingAward(award) }
                val hasLoginCredentials = loginEntries.isNotEmpty()
                val pendingAwardsByGameId = buildPendingAwardsByGameId(entries, awards)
                val games = run {
                    val sessionKeys = db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_UNLOCKS).map { it.cacheKey }
                    Log.d("RAProxy/Games", "patch entries=${entries.size}, unlocks keys in DB=${sessionKeys.size}")
                    entries.mapNotNull { entry ->
                        val parts = entry.cacheKey.split(":")
                        if (parts.size < 3) return@mapNotNull null
                        val gameId = parts[1]
                        val user = parts[2]
                        val patchData = runCatching {
                            JSONObject(entry.responseBody).getJSONObject("PatchData")
                        }.getOrNull()
                        val title = patchData?.optString("Title") ?: gameId
                        val imageIconUrl = gameId.toIntOrNull()?.let {
                            resolveCachedGameIconPath(application, it)
                        } ?: patchData?.let(::patchImageUrl)
                        val unlocksBody = db.cacheDao().get(CacheKeys.unlocks(gameId, user))?.responseBody
                        val unlockedIds = runCatching {
                            val json = JSONObject(unlocksBody ?: return@runCatching emptySet())
                            val unlocks = json.optJSONArray("UserUnlocks") ?: return@runCatching emptySet()
                            buildSet(unlocks.length()) {
                                for (i in 0 until unlocks.length()) {
                                    add(unlocks.optInt(i))
                                }
                            }
                        }.getOrDefault(emptySet())
                        val unlockedCount = unlockedIds.size
                        val totalAchievements = patchData?.optJSONArray("Achievements")?.length() ?: 0
                        val unlockedAchievements = buildUnlockedAchievements(patchData, unlockedIds)
                        CachedGame(
                            gameId = gameId,
                            title = title,
                            user = user,
                            sourceRomPath = entry.sourceRomPath,
                            cachedAt = entry.cachedAt,
                            imageIconUrl = imageIconUrl,
                            unlockedCount = unlockedCount,
                            pendingAwardCount = pendingAwardsByGameId[gameId] ?: 0,
                            totalAchievements = totalAchievements,
                            unlockedAchievements = unlockedAchievements
                        )
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
                Quadruple(resolvedAwards, resolvedHistoryAwards, games, hasLoginCredentials)
            }.collect { (resolvedAwards, resolvedHistoryAwards, games, hasLoginCredentials) ->
                    _state.value = _state.value.copy(
                        pendingAwards = resolvedAwards,
                        awardHistory = resolvedHistoryAwards
                    )
                    _cachedGames.value = games
                    _state.value = _state.value.copy(
                        cachedGames = games,
                        hasLoginCredentials = hasLoginCredentials
                    )
                    maybePromptSmartCacheAfterProxyStart()
                    if (_state.value.proxyRunning && games.isNotEmpty() && _state.value.authState != AuthState.Valid) {
                        validateToken()
                    }
                }
        }
    }

    private fun recoverPatchedCfgIfProxyStopped() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (loadManualEmulatorPatchingEnabled()) {
                _state.value = _state.value.copy(
                    proxyRunning = ProxyService.isRunning(app),
                    cfgIsPatched = null,
                    needsSafGrant = false,
                    safGrantTarget = null,
                    cfgCopyBackPath = null
                )
                return@launch
            }

            val retroArchTreeUri = loadSafUri()
            val dolphinTreeUri = loadDolphinSafUri()
            val retroArchPatched = withContext(Dispatchers.IO) { checkRetroArchIsPatched(app, retroArchTreeUri) }
            val dolphinPatched = withContext(Dispatchers.IO) { checkIsDolphinPatched(app, dolphinTreeUri) }
            val anyPatched = retroArchPatched || dolphinPatched
            val proxyRunning = ProxyService.isRunning(app)
            val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val retroArchPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, false)
            val dolphinPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, false)

            if ((!anyPatched && !retroArchPatchedThisRun && !dolphinPatchedThisRun) || proxyRunning) {
                _state.value = _state.value.copy(
                    proxyRunning = proxyRunning,
                    cfgIsPatched = anyPatched
                )
                return@launch
            }

            val retroArchResult = if (retroArchPatchedThisRun || retroArchPatched) {
                val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, false)
                withContext(Dispatchers.IO) {
                    revertRetroArchCfg(app, retroArchTreeUri, restoreHardcore)
                }
            } else {
                PatchResult(success = true, message = "RetroArch not patched this run.")
            }
            val retroArchRevertedTarget = retroArchResult.success && retroArchResult.copyBackPath == null

            val dolphinResult = if (dolphinPatchedThisRun || dolphinPatched) {
                val restoreDolphinHardcore = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, false)
                withContext(Dispatchers.IO) {
                    revertDolphinCfg(app, dolphinTreeUri, restoreDolphinHardcore)
                }
            } else {
                DolphinPatchResult(success = true, message = "Dolphin not patched this run.", skippedNotInstalled = true)
            }
            val dolphinRevertedTarget = dolphinResult.success && dolphinResult.copyBackPath == null

            if (retroArchRevertedTarget) {
                prefs.edit {
                    remove(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN)
                }
            }

            if (dolphinRevertedTarget) {
                prefs.edit {
                    remove(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN)
                }
            }

            val needsSafGrant = retroArchResult.needsSafGrant || dolphinResult.needsSafGrant
            val safGrantTarget = when {
                retroArchResult.needsSafGrant -> SafGrantTarget.RetroArch
                dolphinResult.needsSafGrant -> SafGrantTarget.Dolphin
                else -> null
            }
            val cfgCopyBackPath = retroArchResult.copyBackPath ?: dolphinResult.copyBackPath

            _state.value = _state.value.copy(
                proxyRunning = false,
                cfgIsPatched = !(retroArchRevertedTarget && dolphinRevertedTarget),
                needsSafGrant = needsSafGrant,
                safGrantTarget = safGrantTarget,
                cfgCopyBackPath = cfgCopyBackPath
            )

            if (!retroArchRevertedTarget) {
                SnackbarManager.showError(retroArchResult.message)
            } else if (!dolphinRevertedTarget && !dolphinResult.needsSafGrant) {
                SnackbarManager.showError(dolphinResult.message)
            }
        }
    }

    private fun restoreDolphinCredentialsOnLaunch(emulatorSupport: EmulatorSupport) {
        if (!emulatorSupport.dolphinEnabled) return

        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val storedCredentials = loadLoginCredentials(db) ?: return@withContext
                restoreDolphinCredentials(app, loadDolphinSafUri(), storedCredentials)
            }
        }
    }

    private suspend fun requireCredentials(): LoginCredentials? {
        val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
        if (credentials == null) {
            SnackbarManager.showError(str(R.string.scan_no_login))
        }
        return credentials
    }

    private fun refreshReachability(
        forceProbe: Boolean,
        capabilities: NetworkCapabilities? = null
    ) {
        val validated = capabilities?.let(::isValidatedNetwork)
            ?: hasValidatedInternet(connectivityManager)

        if (!validated) {
            markRetroAchievementsUnreachable()
            _state.value = _state.value.copy(isOnline = false)
            return
        }

        viewModelScope.launch {
            val userAgent = withContext(Dispatchers.IO) { loadUserAgent(db) }
            val reachable = withContext(Dispatchers.IO) {
                probeRetroAchievements(userAgent = userAgent, force = forceProbe)
            }
            _state.value = _state.value.copy(isOnline = reachable)
        }
    }

    fun clearTransientMessages() {
        _state.value = _state.value.copy(
            scanProgress = null,
            cfgCopyBackPath = null,
            needsSafGrant = false,
            safGrantTarget = null,
            pendingSafGrantTargets = emptyList()
        )
    }

    fun onSafGranted(target: SafGrantTarget) {
        var remaining = _state.value.pendingSafGrantTargets.drop(1)
        if (target == SafGrantTarget.SmartCacheRom && pendingSmartCacheRomGrantPaths.isNotEmpty()) {
            pendingSmartCacheRomGrantPaths = pendingSmartCacheRomGrantPaths.drop(1)
        }
        if (pendingSmartCacheGrantTargets.isNotEmpty()) {
            pendingSmartCacheGrantTargets = pendingSmartCacheGrantTargets.drop(1)
        }
        if (target == SafGrantTarget.AllFilesAccess) {
            pendingSmartCacheRomGrantPaths = emptyList()
            pendingSmartCacheGrantTargets = pendingSmartCacheGrantTargets.filterNot {
                it == SafGrantTarget.SmartCacheRom || it == SafGrantTarget.SmartCacheRetroArch
            }
            remaining = remaining.filterNot {
                it == SafGrantTarget.SmartCacheRom || it == SafGrantTarget.SmartCacheRetroArch
            }
            smartCacheAllFilesRejectedThisRun = false
        }
        _state.value = _state.value.copy(
            pendingSafGrantTargets = remaining,
            needsSafGrant = remaining.isNotEmpty(),
            safGrantTarget = remaining.firstOrNull()
        )
        if (pendingSmartCacheStart && remaining.isEmpty()) {
            pendingSmartCacheGrantTargets = emptyList()
            pendingSmartCacheStart = false
            startSmartCache()
            return
        }
        if (pendingProxyStart && remaining.isEmpty()) {
            startProxyInternal(loadSafUri())
        }
    }

    fun onSafRejected(target: SafGrantTarget) {
        val app = getApplication<Application>()
        if (!pendingProxyStart) {
            val remaining = _state.value.pendingSafGrantTargets.drop(1)
            if (pendingSmartCacheStart && target == SafGrantTarget.AllFilesAccess) {
                smartCacheAllFilesRejectedThisRun = true
                pendingSmartCacheGrantTargets = remaining
                _state.value = _state.value.copy(
                    pendingSafGrantTargets = remaining,
                    needsSafGrant = remaining.isNotEmpty(),
                    safGrantTarget = remaining.firstOrNull()
                )
                if (remaining.isEmpty()) {
                    pendingSmartCacheStart = false
                    SnackbarManager.showMessage(str(R.string.smart_cache_requires_rom_access), SnackbarDuration.Indefinite)
                }
                return
            }
            _state.value = _state.value.copy(
                pendingSafGrantTargets = remaining,
                needsSafGrant = remaining.isNotEmpty(),
                safGrantTarget = remaining.firstOrNull()
            )
            if (pendingSmartCacheStart) {
                pendingSmartCacheGrantTargets = emptyList()
                pendingSmartCacheStart = false
                if (target == SafGrantTarget.AllFilesAccess) {
                    smartCacheAllFilesRejectedThisRun = true
                }
                when (target) {
                    SafGrantTarget.RetroArch -> SnackbarManager.showMessage(str(R.string.smart_cache_requires_retroarch_access), SnackbarDuration.Indefinite)
                    SafGrantTarget.SmartCacheRetroArch -> {
                        PrefsConstants.clearRetroArchSmartCacheSafUri(app)
                        SnackbarManager.showMessage(str(R.string.smart_cache_requires_retroarch_access), SnackbarDuration.Indefinite)
                    }
                    SafGrantTarget.Dolphin -> {
                        PrefsConstants.clearDolphinSafUri(app)
                        SnackbarManager.showMessage(str(R.string.smart_cache_requires_dolphin_access), SnackbarDuration.Indefinite)
                    }
                    SafGrantTarget.AllFilesAccess -> {
                        SnackbarManager.showMessage(str(R.string.smart_cache_requires_all_files_access), SnackbarDuration.Indefinite)
                    }
                    SafGrantTarget.SmartCacheRom -> {
                        pendingSmartCacheGrantTargets = emptyList()
                        pendingSmartCacheRomGrantPaths = emptyList()
                        SnackbarManager.showMessage(str(R.string.smart_cache_requires_rom_access), SnackbarDuration.Indefinite)
                    }
                }
                return
            }
            if (target == SafGrantTarget.AllFilesAccess) {
                pendingSmartCacheStart = false
                pendingSmartCacheGrantTargets = emptyList()
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_all_files_access), SnackbarDuration.Indefinite)
                return
            }
            if (target == SafGrantTarget.SmartCacheRom) {
                pendingSmartCacheStart = false
                pendingSmartCacheGrantTargets = emptyList()
                pendingSmartCacheRomGrantPaths = emptyList()
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_rom_access), SnackbarDuration.Indefinite)
                return
            }
            if (target == SafGrantTarget.RetroArch) {
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_retroarch_access), SnackbarDuration.Indefinite)
            } else if (target == SafGrantTarget.SmartCacheRetroArch) {
                PrefsConstants.clearRetroArchSmartCacheSafUri(app)
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_retroarch_access), SnackbarDuration.Indefinite)
            }
            return
        }

        pendingProxyStart = false
        _state.value = _state.value.copy(
            pendingSafGrantTargets = emptyList(),
            needsSafGrant = false,
            safGrantTarget = null
        )

        when (target) {
            SafGrantTarget.RetroArch -> {
                PrefsConstants.clearSafUri(app)
                SnackbarManager.showMessage(str(R.string.proxy_start_aborted_retroarch_saf_rejected), SnackbarDuration.Indefinite)
            }
            SafGrantTarget.SmartCacheRetroArch -> {
                PrefsConstants.clearRetroArchSmartCacheSafUri(app)
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_retroarch_access), SnackbarDuration.Indefinite)
            }
            SafGrantTarget.Dolphin -> {
                PrefsConstants.clearDolphinSafUri(app)
                SnackbarManager.showMessage(str(R.string.proxy_start_aborted_dolphin_saf_rejected), SnackbarDuration.Indefinite)
            }
            SafGrantTarget.AllFilesAccess -> {
                pendingSmartCacheGrantTargets = emptyList()
                pendingSmartCacheRomGrantPaths = emptyList()
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_all_files_access), SnackbarDuration.Indefinite)
            }
            SafGrantTarget.SmartCacheRom -> {
                PrefsConstants.clearSmartCacheRomSafUri(app)
                pendingSmartCacheGrantTargets = emptyList()
                pendingSmartCacheRomGrantPaths = emptyList()
                SnackbarManager.showMessage(str(R.string.smart_cache_requires_rom_access), SnackbarDuration.Indefinite)
            }
        }
    }

    fun deletePendingAward(award: PendingAwardUi) {
        viewModelScope.launch(Dispatchers.IO) {
            db.pendingAwardDao().update(
                PendingAward(
                    id = award.id,
                    achievementId = award.achievementId,
                    queryString = award.queryString,
                    requestBody = award.requestBody,
                    userAgent = award.userAgent,
                    queuedAt = award.queuedAt,
                    retryCount = award.retryCount,
                    lastError = award.lastError,
                    status = PENDING_AWARD_STATUS_DELETED,
                    payloadHash = award.payloadHash,
                    prevHash = award.prevHash,
                    signature = award.signature,
                    signedAt = award.signedAt
                )
            )
        }
    }

    fun clearAwardHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            if (db.pendingAwardDao().existsByStatus(PENDING_AWARD_STATUS_PENDING)) {
                return@launch
            }
            db.pendingAwardDao().deleteByStatuses(listOf(PENDING_AWARD_STATUS_FLUSHED))
        }
    }

    fun validateToken() {
        viewModelScope.launch {
            val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
            if (credentials == null) {
                Log.i("RAProxy/Auth", "validateToken: no cached credentials found")
                _state.value = _state.value.copy(authState = AuthState.Invalid)
                return@launch
            }
            if (!_state.value.isOnline) {
                Log.i("RAProxy/Auth", "validateToken: offline — trusting cached credentials")
                _state.value = _state.value.copy(authState = AuthState.Valid)
                return@launch
            }
            val gameId = withContext(Dispatchers.IO) {
                db.cacheDao().getAllSummariesByPrefix(CacheKeys.PREFIX_PATCH).firstOrNull()
                    ?.let { CacheKeys.parseGameIdStringFromPatchKey(it.cacheKey) }
            }
            if (gameId == null) {
                Log.i("RAProxy/Auth", "validateToken: online but no cached games — trusting cached credentials")
                _state.value = _state.value.copy(authState = AuthState.Valid)
                return@launch
            }
            val valid = withContext(Dispatchers.IO) {
                val userAgent = proxyUserAgent(loadUserAgent(db))
                val url = buildApiUrl(
                    RA_HOST,
                    "patch",
                    mapOf(
                        "g" to gameId,
                        "u" to credentials.user,
                        "t" to credentials.token
                    )
                )
                when (val result = httpGet(url, userAgent)) {
                    is HttpGetResult.Success -> JSONObject(result.body).optBoolean("Success", false)
                    is HttpGetResult.Failure -> {
                        val logDetails = result.logMessage("patch", url)
                        RequestFailureNotifier.report(result.userMessage(getApplication(), "patch"), logDetails)
                        Log.w("RAProxy/Auth", "validateToken: live check failed — $logDetails")
                        false
                    }
                }
            }
            Log.i("RAProxy/Auth", "validateToken: live patch check valid=$valid")
            _state.value = _state.value.copy(authState = if (valid) AuthState.Valid else AuthState.Invalid)
        }
    }
    fun startProxy(treeUri: Uri? = null) {
        pendingProxyStart = true
        startProxyInternal(treeUri)
    }

    private fun startProxyInternal(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.proxyToggleInProgress) return@launch

            _state.value = _state.value.copy(proxyToggleInProgress = true)

            try {
                val alreadyRunning = ProxyService.isRunning(app)
                val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                val emulatorSupport = loadEmulatorSupport(app)
                prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }

                if (!emulatorSupport.hasAnyEnabled) {
                    pendingProxyStart = false
                    SnackbarManager.showError(str(R.string.proxy_start_requires_emulator))
                    return@launch
                }

                if (loadManualEmulatorPatchingEnabled()) {
                    ProxyService.start(app)
                    pendingProxyStart = false
                    _state.value = _state.value.copy(
                        proxyRunning = true,
                        cfgIsPatched = null,
                        needsSafGrant = false,
                        safGrantTarget = null,
                        pendingSafGrantTargets = emptyList(),
                        authState = AuthState.Unknown
                    )
                    pendingSmartCachePromptAfterProxyStart = true
                    if (!alreadyRunning) {
                        SnackbarManager.showMessage(str(R.string.proxy_started_success))
                    }
                    maybePromptSmartCacheAfterProxyStart()
                    validateToken()
                    return@launch
                }

                val retroArchTreeUri = treeUri ?: loadSafUri()
                val dolphinTreeUri = loadDolphinSafUri()

                val result = if (emulatorSupport.retroArchEnabled) {
                    withContext(Dispatchers.IO) { patchRetroArchCfg(app, retroArchTreeUri) }
                } else {
                    PatchResult(success = true, message = "RetroArch disabled.")
                }
                if (emulatorSupport.retroArchEnabled) {
                    if (result.needsSafGrant) {
                        PrefsConstants.clearSafUri(app)
                        _state.value = _state.value.copy(
                            needsSafGrant = true,
                            safGrantTarget = SafGrantTarget.RetroArch,
                            pendingSafGrantTargets = listOf(SafGrantTarget.RetroArch)
                        )
                        return@launch
                    }
                    if (result.invalidSafGrant) {
                        PrefsConstants.clearSafUri(app)
                        SnackbarManager.showError(result.message)
                        return@launch
                    }
                    if (result.copyBackPath != null) {
                        _state.value = _state.value.copy(cfgCopyBackPath = result.copyBackPath)
                        if (result.success) SnackbarManager.showMessage(result.message, SnackbarDuration.Indefinite)
                        else SnackbarManager.showError(result.message)
                        return@launch
                    }
                    if (!result.success) {
                        SnackbarManager.showError(result.message)
                        return@launch
                    }
                    prefs.edit {
                        putBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, result.hardcoreWasEnabled)
                        putBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, true)
                    }
                } else {
                    prefs.edit { remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN) }
                }

                val dolphinStoredCredentials = if (emulatorSupport.dolphinEnabled) {
                    withContext(Dispatchers.IO) { loadLoginCredentials(db) }
                } else {
                    null
                }
                val dolphinResult = if (emulatorSupport.dolphinEnabled) {
                    withContext(Dispatchers.IO) {
                        patchDolphinCfg(app, dolphinTreeUri, dolphinStoredCredentials)
                    }
                } else {
                    DolphinPatchResult(success = true, message = "Dolphin disabled.", skippedNotInstalled = true)
                }
                if (emulatorSupport.dolphinEnabled) {
                    if (dolphinResult.needsSafGrant) {
                        PrefsConstants.clearDolphinSafUri(app)
                        _state.value = _state.value.copy(
                            needsSafGrant = true,
                            safGrantTarget = SafGrantTarget.Dolphin,
                            pendingSafGrantTargets = listOf(SafGrantTarget.Dolphin)
                        )
                        return@launch
                    } else if (dolphinResult.invalidSafGrant) {
                        PrefsConstants.clearDolphinSafUri(app)
                        SnackbarManager.showError(dolphinResult.message)
                        pendingProxyStart = false
                        return@launch
                    } else if (!dolphinResult.success && !dolphinResult.skippedNotInstalled) {
                        SnackbarManager.showError(dolphinResult.message)
                        pendingProxyStart = false
                        return@launch
                    } else if (dolphinResult.success && !dolphinResult.skippedNotInstalled) {
                        prefs.edit {
                            putBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, dolphinResult.hardcoreWasEnabled)
                            putBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, true)
                        }
                    }
                } else {
                    prefs.edit { remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN) }
                }

                val credentialsToCache = selectImportedCredentials(
                    retroArch = result.credentials,
                    dolphin = dolphinResult.credentials
                )
                credentialsToCache?.let { credentials ->
                    withContext(Dispatchers.IO) { cacheImportedCredentials(credentials) }
                }

                ProxyService.start(app)
                pendingProxyStart = false
                _state.value = _state.value.copy(
                    proxyRunning = true,
                    cfgIsPatched = true,
                    needsSafGrant = false,
                    safGrantTarget = null,
                    pendingSafGrantTargets = emptyList(),
                    authState = AuthState.Unknown
                )
                pendingSmartCachePromptAfterProxyStart = true
                if (!alreadyRunning) {
                    SnackbarManager.showMessage(str(R.string.proxy_started_success))
                }
                maybePromptSmartCacheAfterProxyStart()
                validateToken()
            } finally {
                delay(250)
                _state.value = _state.value.copy(proxyToggleInProgress = false)
            }
        }
    }

    private fun maybePromptSmartCacheAfterProxyStart() {
        if (!pendingSmartCachePromptAfterProxyStart) return
        val currentState = _state.value
        if (!currentState.proxyRunning) return
        if (!currentState.smartCachingEnabled) {
            pendingSmartCachePromptAfterProxyStart = false
            return
        }
        if (currentState.cachedGames.isNotEmpty()) return
        if (!currentState.isOnline) return
        if (!currentState.hasLoginCredentials) return
        pendingSmartCachePromptAfterProxyStart = false
        _events.tryEmit(MainUiEvent.PromptSmartCacheAfterProxyStart)
    }

    fun stopProxy(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.proxyToggleInProgress) return@launch

            _state.value = _state.value.copy(proxyToggleInProgress = true)

            try {
                if (loadManualEmulatorPatchingEnabled()) {
                    ProxyService.stop(app)
                    _state.value = _state.value.copy(
                        proxyRunning = false,
                        cfgIsPatched = null,
                        needsSafGrant = false,
                        safGrantTarget = null,
                        cfgCopyBackPath = null
                    )
                    pendingSmartCachePromptAfterProxyStart = false
                    SnackbarManager.showMessage(str(R.string.proxy_stopped_success))
                    return@launch
                }

                val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                val retroArchTreeUri = treeUri ?: loadSafUri()
                val dolphinTreeUri = loadDolphinSafUri()
                val retroArchPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, false)
                val result = if (retroArchPatchedThisRun) {
                    val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, false)
                    withContext(Dispatchers.IO) { revertRetroArchCfg(app, retroArchTreeUri, restoreHardcore) }
                } else {
                    PatchResult(success = true, message = "RetroArch not patched this run.")
                }
                val revertedTarget = result.success && result.copyBackPath == null

                val dolphinPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, false)
                val dolphinResult = if (dolphinPatchedThisRun) {
                    val restoreDolphinHardcore = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, false)
                    withContext(Dispatchers.IO) {
                        revertDolphinCfg(app, dolphinTreeUri, restoreDolphinHardcore)
                    }
                } else {
                    DolphinPatchResult(success = true, message = "Dolphin not patched this run.", skippedNotInstalled = true)
                }

                if (revertedTarget) {
                    prefs.edit {
                        remove(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED)
                        remove(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN)
                        putBoolean(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT, true)
                    }
                }
                if (dolphinResult.success && dolphinResult.copyBackPath == null) {
                    prefs.edit {
                        remove(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED)
                        remove(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN)
                    }
                }

                ProxyService.stop(app)

                _state.value = _state.value.copy(
                    proxyRunning = false,
                    cfgIsPatched = if (revertedTarget) false else _state.value.cfgIsPatched,
                    needsSafGrant = result.needsSafGrant,
                    safGrantTarget = if (result.needsSafGrant) SafGrantTarget.RetroArch else null,
                    cfgCopyBackPath = result.copyBackPath
                )
                pendingSmartCachePromptAfterProxyStart = false

                if (result.needsSafGrant) {
                    PrefsConstants.clearSafUri(app)
                } else if (result.invalidSafGrant) {
                    PrefsConstants.clearSafUri(app)
                }

                if (revertedTarget) {
                    SnackbarManager.showMessage(str(R.string.proxy_stopped_success))
                } else if (!result.needsSafGrant) {
                    SnackbarManager.showError(result.message)
                }

                if (dolphinResult.needsSafGrant) {
                    PrefsConstants.clearDolphinSafUri(app)
                    _state.value = _state.value.copy(
                        needsSafGrant = true,
                        safGrantTarget = SafGrantTarget.Dolphin
                    )
                } else if (dolphinResult.invalidSafGrant) {
                    PrefsConstants.clearDolphinSafUri(app)
                    SnackbarManager.showError(dolphinResult.message)
                } else if (!dolphinResult.success && !dolphinResult.skippedNotInstalled) {
                    SnackbarManager.showError(dolphinResult.message)
                }
            } finally {
                delay(250)
                _state.value = _state.value.copy(proxyToggleInProgress = false)
            }
        }
    }

    override fun onCleared() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    fun checkCfgPatched(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (loadManualEmulatorPatchingEnabled()) {
                _state.value = _state.value.copy(cfgIsPatched = null)
                return@launch
            }

            val patched = withContext(Dispatchers.IO) {
                checkRetroArchIsPatched(app, treeUri) || checkIsDolphinPatched(app, loadDolphinSafUri())
            }
            _state.value = _state.value.copy(cfgIsPatched = patched)
        }
    }

    fun addRom(fileUris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES), SnackbarDuration.Indefinite)
                return@launch
            }
            val credentials = requireCredentials() ?: return@launch
            val total = fileUris.size
            var matched = 0
            var skipped = 0
            var limitReached = false
            val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
            for ((index, uri) in fileUris.withIndex()) {
                if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                    skipped += total - index
                    limitReached = true
                    break
                }
                val progressMessage = str(R.string.scan_hashing, index + 1, total)
                _state.value = _state.value.copy(scanInProgress = true, scanProgress = progressMessage)
                SnackbarManager.showProgress(progressMessage)
                val result = withContext(Dispatchers.IO) {
                    scanRomFolder(app, uri, credentials, userAgent, db, singleFile = true) { _, _, fileName ->
                        val lookupMessage = str(R.string.scan_looking_up, index + 1, total, fileName)
                        _state.value = _state.value.copy(scanProgress = lookupMessage)
                        SnackbarManager.showProgress(lookupMessage)
                    }
                }
                matched += result.matched
                skipped += result.skipped
                limitReached = false || result.limitReached
                if (result.limitReached) break
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = null
            )
            SnackbarManager.showProgress(null)
            SnackbarManager.showMessage(
                if (limitReached) {
                    str(R.string.scan_add_complete_limit, matched, total, skipped, MAX_CACHED_GAMES, SnackbarDuration.Indefinite)
                } else {
                    str(R.string.scan_add_complete, matched, total, skipped)
                },
                SnackbarDuration.Indefinite
            )
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_PATCH)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_ACHIEVEMENTSETS)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_GAMEID)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_UNLOCKS)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_STARTSESSION)
            clearAllCachedImages(application)
            PrefsConstants.clearAppUpdateLastCheckedAt(application)
            _state.value = _state.value.copy(
                scanProgress = null
            )
            SnackbarManager.showMessage(str(R.string.cache_cleared))
        }
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix("")
            db.pendingAwardDao().getAll().forEach { db.pendingAwardDao().delete(it) }
            clearAllCachedImages(application)
            PrefsConstants.clearAppUpdateLastCheckedAt(application)
            _state.value = _state.value.copy(
                scanProgress = null
            )
            SnackbarManager.showMessage(str(R.string.database_cleared))
        }
    }

    fun scanRoms(treeUri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES), SnackbarDuration.Indefinite)
                return@launch
            }
            val credentials = requireCredentials() ?: return@launch
            val startingMessage = str(R.string.scan_starting)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage)
            var completionMessage: String? = null
            try {
                val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
                val result = withContext(Dispatchers.IO) {
                    scanRomFolder(app, treeUri, credentials, userAgent, db) { current, total, fileName ->
                        val progressMessage = str(R.string.scan_progress, current, total, fileName)
                        _state.value = _state.value.copy(scanProgress = progressMessage)
                        SnackbarManager.showProgress(progressMessage)
                    }
                }
                completionMessage = if (result.limitReached) {
                    str(R.string.scan_complete_limit, result.matched, result.total, result.skipped, MAX_CACHED_GAMES)
                } else {
                    str(R.string.scan_complete, result.matched, result.total, result.skipped)
                }
            } catch (t: Throwable) {
                Log.e("RAProxy/Scan", "scanRoms failed for treeUri=$treeUri", t)
                SnackbarManager.showError(t.message ?: "ROM scan failed.")
            } finally {
                _state.value = _state.value.copy(
                    scanInProgress = false,
                    scanProgress = null
                )
                SnackbarManager.showProgress(null)
            }
            completionMessage?.let {
                SnackbarManager.showMessage(it, SnackbarDuration.Indefinite)
            }
        }
    }

    fun deleteCachedGame(game: CachedGame) {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.patchPrefix(game.gameId))
            deleteCachedImagesForGame(application, game.gameId)
        }
    }

    fun refreshGames() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val credentials = requireCredentials() ?: return@launch
            val games = _state.value.cachedGames.reversed()
            val startingMessage = str(R.string.refresh_progress, 0, games.size)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage)
            val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
            withContext(Dispatchers.IO) {
                for ((index, game) in games.withIndex()) {
                    val progressMessage = str(R.string.refresh_progress_named, index + 1, games.size, game.title)
                    _state.value = _state.value.copy(scanProgress = progressMessage)
                    SnackbarManager.showProgress(progressMessage)
                    val gameId = game.gameId.toIntOrNull() ?: continue
                    cacheGame(app, gameId, credentials, userAgent, db)
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = null
            )
            SnackbarManager.showProgress(null)
            SnackbarManager.showMessage(str(R.string.refresh_complete, games.size), SnackbarDuration.Indefinite)
        }
    }

    fun startSmartCache() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            Log.i("RAProxy/SmartCache", "startSmartCache invoked cachedGames=${_state.value.cachedGames.size}")
            if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES), SnackbarDuration.Indefinite)
                return@launch
            }
            val credentials = requireCredentials() ?: return@launch
            val romTreeUris = loadSmartCacheRomSafUris()
            val startingMessage = str(R.string.smart_cache_starting)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage)
            var completionMessage: String? = null
            try {
                val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
                val result = withContext(Dispatchers.IO) {
                    runSmartCache(
                        context = app,
                        credentials = credentials,
                        userAgent = userAgent,
                        db = db,
                        emulatorSupport = loadEmulatorSupport(app),
                        retroArchTreeUri = loadSafUri(),
                        dolphinTreeUri = loadDolphinSafUri(),
                        romTreeUris = romTreeUris
                    ) { current, total, label ->
                        val progressMessage = str(R.string.smart_cache_progress, current, total, label)
                        _state.value = _state.value.copy(scanProgress = progressMessage)
                        SnackbarManager.showProgress(progressMessage)
                    }
                }
                Log.i(
                    "RAProxy/SmartCache",
                    "startSmartCache result matched=${result.matched} total=${result.total} skipped=${result.skipped} limitReached=${result.limitReached} needsSafGrant=${result.needsSafGrant} message=${result.message}"
                )
                if (result.needsSafGrant) {
                    pendingSmartCacheStart = true
                    val safTargets = buildList {
                        result.requiredSafGrantTargets.forEach { emulator ->
                            when (emulator) {
                                SmartCacheEmulator.RetroArch -> add(SafGrantTarget.RetroArch)
                                SmartCacheEmulator.Dolphin -> add(SafGrantTarget.Dolphin)
                            }
                        }
                        if (result.message == "needs_retroarch_shared_access") {
                            clear()
                            if (!hasAllFilesAccess() && !smartCacheAllFilesRejectedThisRun) {
                                add(SafGrantTarget.AllFilesAccess)
                            }
                            add(SafGrantTarget.SmartCacheRetroArch)
                        }
                        if (result.requiredRomGrantPaths.isNotEmpty() && !hasAllFilesAccess()) {
                            if (!smartCacheAllFilesRejectedThisRun) {
                                add(SafGrantTarget.AllFilesAccess)
                            }
                            repeat(result.requiredRomGrantPaths.size) {
                                add(SafGrantTarget.SmartCacheRom)
                            }
                        }
                    }
                    pendingSmartCacheGrantTargets = if (safTargets.isNotEmpty()) {
                        safTargets
                    } else {
                        listOf(
                            when (result.message) {
                                "needs_saf_grant" -> SafGrantTarget.RetroArch
                                "needs_retroarch_shared_access" -> if (hasAllFilesAccess() || smartCacheAllFilesRejectedThisRun) SafGrantTarget.SmartCacheRetroArch else SafGrantTarget.AllFilesAccess
                                "needs_dolphin_saf_grant" -> SafGrantTarget.Dolphin
                                "needs_rom_saf_grant" -> if (hasAllFilesAccess()) SafGrantTarget.SmartCacheRom else SafGrantTarget.AllFilesAccess
                                else -> SafGrantTarget.SmartCacheRom
                            }
                        )
                    }
                    pendingSmartCacheRomGrantPaths = result.requiredRomGrantPaths
                    Log.i("RAProxy/SmartCache", "startSmartCache requesting SAF grants targets=$pendingSmartCacheGrantTargets romPaths=${result.requiredRomGrantPaths}")
                    _state.value = _state.value.copy(
                        needsSafGrant = true,
                        safGrantTarget = pendingSmartCacheGrantTargets.firstOrNull(),
                        pendingSafGrantTargets = pendingSmartCacheGrantTargets
                    )
                    return@launch
                }
                val message = when (result.message) {
                    "no_strategies" -> str(R.string.smart_cache_no_strategies)
                    "needs_saf_grant" -> str(R.string.smart_cache_requires_retroarch_access)
                    "needs_retroarch_shared_access" -> str(R.string.smart_cache_requires_retroarch_access)
                    "needs_dolphin_saf_grant" -> str(R.string.smart_cache_requires_dolphin_access)
                    "needs_rom_saf_grant" -> str(R.string.smart_cache_requires_rom_access)
                    "history_missing" -> str(R.string.smart_cache_history_missing)
                    "history_empty" -> str(R.string.smart_cache_history_empty)
                    "no_recent_games" -> str(R.string.smart_cache_no_recent_games)
                    "no_readable_candidates" -> str(R.string.smart_cache_no_readable_candidates)
                    "no_ra_matches" -> str(R.string.smart_cache_no_ra_matches)
                    else -> if (result.limitReached) {
                        str(R.string.smart_cache_complete_limit, result.matched, result.total, result.skipped, MAX_CACHED_GAMES)
                    } else {
                        str(R.string.smart_cache_complete, result.matched, result.total, result.skipped)
                    }
                }
                completionMessage = message
            } catch (t: Throwable) {
                Log.e("RAProxy/SmartCache", "startSmartCache failed", t)
                SnackbarManager.showError(t.message ?: "Smart Cache failed.")
            } finally {
                Log.i("RAProxy/SmartCache", "startSmartCache clearing progress UI")
                _state.value = _state.value.copy(scanInProgress = false, scanProgress = null)
                SnackbarManager.showProgress(null)
            }
            completionMessage?.let {
                SnackbarManager.showMessage(it, SnackbarDuration.Indefinite)
            }
        }
    }

    private suspend fun resolvePendingAward(award: PendingAward): PendingAwardUi {
        val params = parseFormParams(award.queryString.substringAfter("?", "") + "&" + award.requestBody)
        val achievementId = params["a"]?.toIntOrNull()
        val hardcore = params["h"] == "1"
        var gameTitle = str(R.string.unknown_game)
        var gameIconUrl: String? = null
        var achievementTitle = if (achievementId != null) str(R.string.achievement_fallback, achievementId) else str(R.string.unknown_game)
        var points = 0
        var badgeUrl: String? = null
        for (entry in db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH)) {
            runCatching {
                val gameId = CacheKeys.parseGameIdFromPatchKey(entry.cacheKey)
                val patchData = JSONObject(entry.responseBody).getJSONObject("PatchData")
                val arr = patchData.getJSONArray("Achievements")
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.getInt("ID") == achievementId) {
                        gameTitle = patchData.optString("Title", gameTitle)
                        gameIconUrl = gameId?.let {
                            resolveCachedGameIconPath(application, it)
                        } ?: patchData.let(::patchImageUrl)
                        achievementTitle = a.optString("Title", achievementTitle)
                        points = a.optInt("Points", 0)
                        val badgeName = a.optString("BadgeName").takeIf { it.isNotEmpty() }
                        badgeUrl = badgeName?.let { "https://i.retroachievements.org/Badge/$it.png" }
                        break
                    }
                }
            }
        }
        return PendingAwardUi(
            id = award.id,
            achievementId = award.achievementId,
            queryString = award.queryString,
            requestBody = award.requestBody,
            userAgent = award.userAgent,
            gameTitle = gameTitle,
            gameIconUrl = gameIconUrl,
            achievementTitle = achievementTitle,
            queuedAt = award.queuedAt,
            points = points,
            badgeUrl = badgeUrl,
            hardcore = hardcore,
            retryCount = award.retryCount,
            lastError = award.lastError,
            payloadHash = award.payloadHash,
            prevHash = award.prevHash,
            signature = award.signature,
            signedAt = award.signedAt
        )
    }

    private fun buildPendingAwardsByGameId(
        patchEntries: List<CacheEntry>,
        awards: List<PendingAward>
    ): Map<String, Int> {
        if (patchEntries.isEmpty() || awards.isEmpty()) return emptyMap()

        val achievementGameIds = buildAchievementGameIds(patchEntries)
        if (achievementGameIds.isEmpty()) return emptyMap()

        return buildMap {
            awards.forEach { award ->
                val achievementId = parsePendingAwardAchievementId(award) ?: return@forEach
                val gameId = achievementGameIds[achievementId] ?: return@forEach
                put(gameId, (get(gameId) ?: 0) + 1)
            }
        }
    }

    private fun buildAchievementGameIds(
        patchEntries: List<CacheEntry>
    ): Map<Int, String> = buildMap {
        patchEntries.forEach { entry ->
            val gameId = CacheKeys.parseGameIdStringFromPatchKey(entry.cacheKey) ?: return@forEach
            val patchData = runCatching {
                JSONObject(entry.responseBody).getJSONObject("PatchData")
            }.getOrNull() ?: return@forEach
            val achievements = patchData.optJSONArray("Achievements") ?: return@forEach
            for (i in 0 until achievements.length()) {
                val achievement = achievements.optJSONObject(i) ?: continue
                val achievementId = achievement.optInt("ID")
                if (achievementId != 0) {
                    putIfAbsent(achievementId, gameId)
                }
            }
        }
    }

    private fun parsePendingAwardAchievementId(award: PendingAward): Int? {
        val params = parseFormParams(award.queryString.substringAfter("?", "") + "&" + award.requestBody)
        return params["a"]?.toIntOrNull()
    }

    private fun buildUnlockedAchievements(
        patchData: JSONObject?,
        unlockedIds: Set<Int>
    ): List<UnlockedAchievement> {
        if (patchData == null || unlockedIds.isEmpty()) return emptyList()

        val achievements = patchData.optJSONArray("Achievements") ?: return emptyList()

        return buildList {
            for (i in 0 until achievements.length()) {
                val achievement = achievements.optJSONObject(i) ?: continue
                val achievementId = achievement.optInt("ID")
                if (!unlockedIds.contains(achievementId)) continue

                val badgeName = achievement.optString("BadgeName").takeIf { it.isNotEmpty() }
                add(
                    UnlockedAchievement(
                        id = achievementId,
                        title = achievement.optString(
                            "Title",
                            str(R.string.achievement_fallback, achievementId)
                        ),
                        description = achievement.optString("Description").takeIf { it.isNotEmpty() },
                        points = achievement.optInt("Points", 0),
                        badgeUrl = badgeName?.let { "https://i.retroachievements.org/Badge/$it.png" }
                    )
                )
            }
        }
    }

    fun setAutostartProxy(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, enabled) }
        _state.value = _state.value.copy(autostartProxy = enabled)
    }

    fun setSmartCachingEnabled(enabled: Boolean) {
        PrefsConstants.saveSmartCachingEnabled(getApplication(), enabled)
        _state.value = _state.value.copy(smartCachingEnabled = enabled)
        if (!enabled) {
            pendingSmartCachePromptAfterProxyStart = false
        }
    }

    fun setAppUpdateCheckEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        PrefsConstants.saveAppUpdateCheckEnabled(app, enabled)
        if (!enabled) {
            _state.value = _state.value.copy(
                appUpdateCheckEnabled = false,
                availableAppUpdate = null
            )
            return
        }

        _state.value = _state.value.copy(appUpdateCheckEnabled = true)
    }

    fun setManualEmulatorPatchingEnabled(enabled: Boolean) {
        val app = getApplication<Application>()

        if (enabled && !state.value.hasLoginCredentials) {
            _events.tryEmit(MainUiEvent.PromptManualCredentials)
            return
        }

        PrefsConstants.saveManualEmulatorPatchingEnabled(app, enabled)
        _state.value = _state.value.copy(
            manualEmulatorPatchingEnabled = enabled,
            needsSafGrant = false,
            safGrantTarget = null,
            pendingSafGrantTargets = emptyList(),
            cfgCopyBackPath = null,
            cfgIsPatched = if (enabled) null else _state.value.cfgIsPatched
        )
        exportManualSetupConfig()

        if (!enabled) {
            checkCfgPatched(treeUri = loadSafUri())
        }
    }

    fun saveManualLoginCredentials(username: String, password: String) {
        val app = getApplication<Application>()
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()

        viewModelScope.launch {
            if (normalizedUsername.isBlank() || normalizedPassword.isBlank()) {
                SnackbarManager.showError(
                    if (normalizedUsername.isBlank()) {
                        str(R.string.manual_credentials_username_required)
                    } else {
                        str(R.string.manual_credentials_password_required)
                    }
                )
                return@launch
            }

            val loginCredentials = withContext(Dispatchers.IO) {
                loginAndCacheToken(
                    db,
                    PasswordCredentials(normalizedUsername, normalizedPassword),
                    loadUserAgent(db)
                )
            }

            if (loginCredentials == null) {
                SnackbarManager.showError(str(R.string.manual_credentials_invalid))
                return@launch
            }

            withContext(Dispatchers.IO) {
                db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_LOGIN)
                db.cacheDao().upsert(
                    CacheEntry(
                        cacheKey = CacheKeys.login(loginCredentials.user),
                        responseBody = cacheLoginCredentialsResponse(loginCredentials.user, loginCredentials.token)
                    )
                )
            }

            PrefsConstants.saveManualEmulatorPatchingEnabled(app, true)
            _state.value = _state.value.copy(
                manualEmulatorPatchingEnabled = true,
                hasLoginCredentials = true,
                needsSafGrant = false,
                safGrantTarget = null,
                pendingSafGrantTargets = emptyList(),
                cfgCopyBackPath = null,
                cfgIsPatched = null,
                authState = AuthState.Unknown
            )
            exportManualSetupConfig()
            validateToken()
        }
    }

    fun setRetroArchEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        val support = loadEmulatorSupport(app)
        if (!support.retroArchInstalled || (support.installedCount == 1) || _state.value.proxyRunning) {
            return
        }

        app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(PrefsConstants.KEY_ENABLE_RETROARCH, enabled) }
        val updated = loadEmulatorSupport(app)
        _state.value = _state.value.copy(
            retroArchEnabled = updated.retroArchEnabled,
            dolphinEnabled = updated.dolphinEnabled
        )
        exportManualSetupConfig()
    }

    fun setDolphinEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        val support = loadEmulatorSupport(app)
        if (!support.dolphinInstalled || (support.installedCount == 1) || _state.value.proxyRunning) {
            return
        }
        setDolphinEnabledInternal(enabled)
    }

    private fun setDolphinEnabledInternal(enabled: Boolean) {
        val app = getApplication<Application>()
        app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(PrefsConstants.KEY_ENABLE_DOLPHIN, enabled) }
        val updated = loadEmulatorSupport(app)
        _state.value = _state.value.copy(
            retroArchEnabled = updated.retroArchEnabled,
            dolphinEnabled = updated.dolphinEnabled
        )
        exportManualSetupConfig()
    }

    fun setProxyPort(portText: String): Boolean {
        val port = portText.toIntOrNull()
            ?.takeIf(PrefsConstants::isValidProxyPort)
            ?: return false

        val app = getApplication<Application>()
        PrefsConstants.saveProxyPort(app, port)
        _state.value = _state.value.copy(proxyPort = port)
        exportManualSetupConfig()
        return true
    }

    private fun exportManualSetupConfig() {
        val app = getApplication<Application>()
        val currentState = _state.value

        viewModelScope.launch(Dispatchers.IO) {
            val enabledEmulators = buildList {
                if (currentState.retroArchEnabled) add("retroarch")
                if (currentState.dolphinEnabled) add("dolphin")
            }

            val content = JSONObject()
                .put("manualEmulatorPatchingEnabled", currentState.manualEmulatorPatchingEnabled)
                .put("proxyPort", currentState.proxyPort)
                .put("enabledEmulators", org.json.JSONArray(enabledEmulators))
                .toString(2)

            runCatching {
                val externalRoot = app.getExternalFilesDir(null)
                if (externalRoot != null) {
                    val externalDirectory = File(externalRoot, "manual-emulator-setup")
                    externalDirectory.mkdirs()
                    File(externalDirectory, "adb-config.json").writeText(content)
                }
            }.onFailure {
                Log.w("RAProxy/ManualSetup", "Failed to export external adb-config.json: ${it.message}", it)
            }

            runCatching {
                val internalDirectory = File(app.filesDir, "manual-emulator-setup")
                internalDirectory.mkdirs()
                File(internalDirectory, "adb-config.json").writeText(content)
            }.onFailure {
                Log.w("RAProxy/ManualSetup", "Failed to export internal adb-config.json: ${it.message}", it)
            }
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        val app = getApplication<Application>()
        if (!PrefsConstants.loadAppUpdateCheckEnabled(app)) {
            _state.value = _state.value.copy(availableAppUpdate = null)
            return
        }
        val currentVersionName = BuildConfig.VERSION_NAME
        val now = System.currentTimeMillis()
        if (!force && now - PrefsConstants.loadAppUpdateLastCheckedAt(app) < APP_UPDATE_CHECK_INTERVAL_MS) {
            Log.d("RAProxy/Updates", "Skipping app update check; last check was too recent")
            val cachedUpdate = PrefsConstants.loadAvailableAppUpdate(app)
                ?.takeIf { AppUpdateChecker.isUpdateNewerThanCurrent(currentVersionName, it.versionName) }
            _state.value = _state.value.copy(availableAppUpdate = cachedUpdate)
            return
        }
        if (!hasValidatedInternet(connectivityManager)) {
            Log.i("RAProxy/Updates", "Skipping app update check; validated internet not available")
            return
        }

        PrefsConstants.saveAppUpdateLastCheckedAt(app, now)
        Log.i("RAProxy/Updates", "Starting app update check force=$force")
        viewModelScope.launch {
            Log.i("RAProxy/Updates", "Using current version for update check: $currentVersionName")
            val update = withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatestUpdate(currentVersionName) } ?: run {
                Log.i("RAProxy/Updates", "App update check finished without available update")
                PrefsConstants.clearAvailableAppUpdate(app)
                _state.value = _state.value.copy(availableAppUpdate = null)
                return@launch
            }
            Log.i("RAProxy/Updates", "App update available: ${update.versionName}")
            PrefsConstants.saveAvailableAppUpdate(app, update)
            _state.value = _state.value.copy(availableAppUpdate = update)
            _events.emit(MainUiEvent.ShowAppUpdate(update))
        }
    }

    private fun loadAutostartPref(): Boolean =
        getApplication<Application>()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)

    private fun loadSmartCachingEnabled(): Boolean =
        PrefsConstants.loadSmartCachingEnabled(getApplication())

    private fun loadAppUpdateCheckEnabled(): Boolean =
        PrefsConstants.loadAppUpdateCheckEnabled(getApplication())

    private fun loadManualEmulatorPatchingEnabled(): Boolean =
        PrefsConstants.loadManualEmulatorPatchingEnabled(getApplication())

    private fun loadSafUri(): Uri? =
        PrefsConstants.loadSafUri(getApplication())

    private fun loadDolphinSafUri(): Uri? =
        PrefsConstants.loadDolphinSafUri(getApplication())

    internal fun consumePendingSmartCacheRomGrantPath(): String? =
        pendingSmartCacheRomGrantPaths.firstOrNull()

    internal fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()

    private fun loadSmartCacheRomSafUris(): List<Uri> =
        PrefsConstants.loadSmartCacheRomSafUris(getApplication())

    private suspend fun cacheImportedCredentials(credentials: ImportedCredentials) {
        val tokenCredentials = when (credentials) {
            is ImportedCredentials.Token -> LoginCredentials(
                credentials.username,
                credentials.token
            )

            is ImportedCredentials.Password -> loginAndCacheToken(
                db,
                PasswordCredentials(credentials.username, credentials.password),
                loadUserAgent(db)
            ) ?: return
        }
        val body = cacheLoginCredentialsResponse(tokenCredentials.user, tokenCredentials.token)
        db.cacheDao().upsert(
            CacheEntry(
                cacheKey = CacheKeys.login(tokenCredentials.user),
                responseBody = body
            )
        )
    }

    private suspend fun compactCachedShadowPatches() {
        val patchEntries = runCatching { db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH) }.getOrDefault(emptyList())
        patchEntries.forEach { entry ->
            val normalized = runCatching {
                normalizeCachedResponse("patch", "", "", entry.responseBody)
            }.getOrNull() ?: return@forEach
            db.cacheDao().upsert(
                entry.copy(
                    responseBody = normalized,
                    cachedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun compactCachedRawAchievementSets() {
        val rawEntries = runCatching { db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_ACHIEVEMENTSETS) }.getOrDefault(emptyList())
        rawEntries.forEach { entry ->
            val compacted = runCatching {
                compactCachedRawResponse("achievementsets", entry.responseBody)
            }.getOrNull() ?: return@forEach
            if (compacted == entry.responseBody) return@forEach
            db.cacheDao().upsert(
                entry.copy(
                    responseBody = compacted,
                    cachedAt = System.currentTimeMillis()
                )
            )
        }
    }
}

internal fun selectImportedCredentials(
    retroArch: ImportedCredentials?,
    dolphin: ImportedCredentials?
): ImportedCredentials? = when {
    retroArch is ImportedCredentials.Token -> retroArch
    dolphin is ImportedCredentials.Token -> dolphin
    retroArch is ImportedCredentials.Password -> retroArch
    else -> null
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
