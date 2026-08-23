package com.raofflineproxy.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import com.raofflineproxy.isLoopbackPortAvailable
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheEntry
import com.raofflineproxy.data.CacheEntrySummary
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.PendingAward
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.data.PENDING_AWARD_STATUS_DELETED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_FLUSHED
import com.raofflineproxy.data.PENDING_AWARD_STATUS_PENDING
import com.raofflineproxy.data.CachedAchievement
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.FlushEvent
import com.raofflineproxy.proxy.LoginCredentials
import com.raofflineproxy.proxy.PasswordCredentials
import com.raofflineproxy.proxy.patchImagePath
import com.raofflineproxy.proxy.patchImageUrl
import com.raofflineproxy.proxy.resolveCachedStaticAsset
import com.raofflineproxy.proxy.cachedBadgeFileNames
import com.raofflineproxy.proxy.cachedBadgePath
import com.raofflineproxy.proxy.cacheLoginCredentialsResponse
import com.raofflineproxy.proxy.clearAllCachedImages
import com.raofflineproxy.proxy.deleteCachedImagesForGame
import com.raofflineproxy.proxy.deleteAwardImages
import com.raofflineproxy.proxy.HttpGetResult
import com.raofflineproxy.proxy.httpGet
import com.raofflineproxy.proxy.loginAndCacheToken
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadCachedGameRefreshTargets
import com.raofflineproxy.proxy.refreshCachedGameOfflineBundle
import com.raofflineproxy.proxy.RefreshNotificationMode
import com.raofflineproxy.proxy.runSmartCache
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.compactCachedRawResponse
import com.raofflineproxy.proxy.normalizeCachedResponse
import com.raofflineproxy.proxy.resolveCachedGameIconPath
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.proxy.hash.hasExtension
import com.raofflineproxy.proxy.scanRomFolder
import com.raofflineproxy.proxy.shouldCompactAchievementSets
import com.raofflineproxy.proxy.WARNING_ACHIEVEMENT_ID
import com.raofflineproxy.proxy.RC_ACHIEVEMENT_FLAG_CORE
import com.raofflineproxy.proxy.SmartCacheEmulator
import com.raofflineproxy.service.ProxyService
import com.raofflineproxy.update.AppUpdateChecker
import com.raofflineproxy.update.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

private const val TOKEN_VALIDATION_COOLDOWN_MS = 60_000L

enum class AuthState { Unknown, Valid, Invalid }

enum class SafGrantTarget { RetroArch, SmartCacheRetroArch, Dolphin, Ppsspp, SmartCacheRom, AllFilesAccess }

sealed interface MainUiEvent {
    data object PromptSmartCacheAfterProxyStart : MainUiEvent
    data object PromptManualCredentials : MainUiEvent
    data object PromptCredentialsForCaching : MainUiEvent
    data object OpenShizukuGuide : MainUiEvent
    data class ShowAppUpdate(val update: AppUpdateInfo) : MainUiEvent
    data object RequestShizukuPermission : MainUiEvent
    data object PromptPpssppShizukuRootMode : MainUiEvent
}

private sealed interface PendingCredentialAction {
    data object SmartCache : PendingCredentialAction
    data object RefreshGames : PendingCredentialAction
    data class AddRom(val uris: List<Uri>) : PendingCredentialAction
    data class ScanRoms(val treeUri: Uri) : PendingCredentialAction
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
    val hideSupportButton: Boolean = false,
    val showLockedAchievements: Boolean = false,
    val proxyPort: Int = PrefsConstants.DEFAULT_PROXY_PORT,
    val emulators: EmulatorSupport = EmulatorSupport.NONE,
    val pendingAwards: List<PendingAwardUi> = emptyList(),
    val awardHistory: List<PendingAwardUi> = emptyList(),
    val cachedGames: List<CachedGame> = emptyList(),
    val needsSafGrant: Boolean = false,
    val safGrantTarget: SafGrantTarget? = null,
    val pendingSafGrantTargets: List<SafGrantTarget> = emptyList(),
    val cfgCopyBackPath: String? = null,
    val cfgIsPatched: Boolean? = null,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.Unsupported,
    val shizukuManualPatchingEnabled: Boolean = false,
    val shizukuOperationInProgress: Boolean = false,
    val showPpssppShizukuRootModePrompt: Boolean = false,
    val ppssppShizukuRootModeUnknown: Boolean = false,
    val scanInProgress: Boolean = false,
    val scanProgress: String? = null,
    val flushInProgress: Boolean = false,
    val availableAppUpdate: AppUpdateInfo? = null
) {
    val hasEnabledEmulator: Boolean = emulators.hasAnyEnabled
    val hasShizukuManagedEnabledEmulator: Boolean = emulators.hasAnyShizukuManagedEnabled

    fun clearedPermissions(): MainUiState = copy(
        manualEmulatorPatchingEnabled = false,
        needsSafGrant = false,
        safGrantTarget = null,
        pendingSafGrantTargets = emptyList(),
        cfgCopyBackPath = null,
        cfgIsPatched = null,
        shizukuManualPatchingEnabled = false,
        showPpssppShizukuRootModePrompt = false,
        ppssppShizukuRootModeUnknown = true
    )
}

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
    private val pendingDeletedGameIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val patchCache = mutableMapOf<Long, ParsedPatch>()
    private val unlockCache = mutableMapOf<Long, CachedUnlocks>()
    private val connectivityManager =
        app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var pendingProxyStart = false
    private var pendingSmartCacheStart = false
    private var pendingSmartCacheRomGrantPaths = emptyList<String>()
    private var pendingSmartCacheGrantTargets = emptyList<SafGrantTarget>()
    private var pendingPpssppShizukuRootModePrompt = false
    private var smartCacheAllFilesRejectedThisRun = false
    private var pendingAddRomUris = emptyList<Uri>()
    private var pendingCredentialAction: PendingCredentialAction? = null
    private var lastTokenValidationAttemptAt: Long = 0L
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
        PrefsConstants.resetHideSupportButtonOnAppUpdate(app, BuildConfig.VERSION_CODE.toLong())

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
            migrateUserCaseInCacheKeys()
            compactCachedShadowPatches()
            compactCachedRawAchievementSets()
        }
        val emulatorSupport = loadEmulatorSupport(app)
        Log.i(
            "RAProxy/Emulators",
            "init support " + emulatorSupport.states.joinToString(" ") { state ->
                "${state.emulator.name}=installed:${state.installed},enabled:${state.enabled}"
            }
        )
        _state.value = _state.value.copy(
            autostartProxy = loadAutostartPref(),
            manualEmulatorPatchingEnabled = loadManualEmulatorPatchingEnabled(),
            smartCachingEnabled = loadSmartCachingEnabled(),
            appUpdateCheckEnabled = loadAppUpdateCheckEnabled(),
            hideSupportButton = loadHideSupportButtonEnabled(),
            showLockedAchievements = loadShowLockedAchievementsEnabled(),
            proxyPort = PrefsConstants.loadProxyPort(app),
            emulators = emulatorSupport,
            shizukuStatus = resolveShizukuStatus(app),
            shizukuManualPatchingEnabled = loadShizukuManualPatchingEnabled(),
            ppssppShizukuRootModeUnknown = loadPpssppRootMode() == PrefsConstants.PpssppRootMode.Unknown
        )
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
                db.pendingAwardDao().observeByStatus().distinctUntilChanged(),
                db.pendingAwardDao().observeByStatus(PENDING_AWARD_STATUS_FLUSHED).distinctUntilChanged(),
                db.cacheDao().observePatchEntrySummaries(),
                db.cacheDao().observeUnlockSummaries().distinctUntilChanged(),
                db.cacheDao().observeByPrefix(CacheKeys.PREFIX_LOGIN)
                    .map { it.isNotEmpty() }
                    .distinctUntilChanged()
            ) { awards, historyAwards, patchSummaries, unlockSummaries, hasLoginCredentials ->
                val badgeNames = cachedBadgeFileNames(application)
                val patches = loadPatchViews(patchSummaries, badgeNames)
                val achievementIndex = buildAchievementIndex(patches)
                val resolvedAwards = awards.map { resolvePendingAward(it, achievementIndex) }
                val resolvedHistoryAwards = historyAwards.map { resolvePendingAward(it, achievementIndex) }
                val pendingAwardsByGameId = buildPendingAwardsByGameId(achievementIndex, awards)
                pendingDeletedGameIds.retainAll(patches.mapTo(HashSet()) { it.gameId })
                unlockCache.keys.retainAll(unlockSummaries.mapTo(HashSet()) { it.id })
                val unlockSummariesByKey = unlockSummaries.associateBy { it.cacheKey }
                val games = patches
                    .filter { it.gameId !in pendingDeletedGameIds }
                    .map { patch ->
                        buildCachedGame(
                            patch = patch,
                            unlockSummary = unlockSummariesByKey[CacheKeys.unlocks(patch.gameId, patch.user)],
                            pendingAwardCount = pendingAwardsByGameId[patch.gameId] ?: 0
                        )
                    }
                    .sortedWith(
                        compareBy(
                            { com.raofflineproxy.data.ConsoleNames.nameForId(it.consoleId) },
                            { it.title.lowercase() }
                        )
                    )
                Quadruple(resolvedAwards, resolvedHistoryAwards, games, hasLoginCredentials)
            }.flowOn(Dispatchers.Default)
                .collect { (resolvedAwards, resolvedHistoryAwards, games, hasLoginCredentials) ->
                    _state.value = _state.value.copy(
                        pendingAwards = resolvedAwards,
                        awardHistory = resolvedHistoryAwards
                    )
                    _cachedGames.value = games
                    _state.value = _state.value.copy(
                        cachedGames = games,
                        hasLoginCredentials = hasLoginCredentials
                    )
                    if (_state.value.proxyRunning && games.isNotEmpty() && _state.value.authState != AuthState.Valid) {
                        validateToken(force = false)
                    }
                }
        }
    }

    private fun recoverPatchedCfgIfProxyStopped() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val shouldKeepRunning = ProxyService.shouldKeepRunning(app)
            if (loadManualEmulatorPatchingEnabled()) {
                val proxyRunning = ProxyService.isRunning(app)
                if (!proxyRunning) {
                    if (shouldKeepRunning) {
                        ProxyService.start(app)
                        _state.value = _state.value.copy(
                            proxyRunning = true,
                            cfgIsPatched = null,
                            needsSafGrant = false,
                            safGrantTarget = null,
                            cfgCopyBackPath = null
                        )
                        return@launch
                    }

                    val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                    for (emulator in Emulator.BROADCAST_MANAGED) {
                        if (!prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) continue
                        val result = withContext(Dispatchers.IO) { revertBroadcastCfg(app, emulator) }
                        if (result.success) {
                            prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
                        } else {
                            SnackbarManager.showError(result.message)
                        }
                    }
                }
                _state.value = _state.value.copy(
                    proxyRunning = proxyRunning,
                    cfgIsPatched = null,
                    needsSafGrant = false,
                    safGrantTarget = null,
                    cfgCopyBackPath = null
                )
                return@launch
            }

            val retroArchTreeUri = loadSafUri()
            val dolphinTreeUri = loadDolphinSafUri()
            val ppssppTreeUri = loadPpssppSafUri()
            val retroArchPatched = withContext(Dispatchers.IO) { checkRetroArchIsPatched(app, retroArchTreeUri) }
            val dolphinPatched = withContext(Dispatchers.IO) { checkIsDolphinPatched(app, dolphinTreeUri) }
            val ppssppPatched = withContext(Dispatchers.IO) { checkIsPpssppPatched(app, ppssppTreeUri) }
            val anyBroadcastPatched = Emulator.BROADCAST_MANAGED.any { checkIsBroadcastPatched(app, it) }
            val anyPatched = retroArchPatched || dolphinPatched || ppssppPatched || anyBroadcastPatched
            val proxyRunning = ProxyService.isRunning(app)
            val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val retroArchPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, false)
            val dolphinPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, false)
            val ppssppPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN, false)

            if (!proxyRunning && shouldKeepRunning) {
                ProxyService.start(app)
                _state.value = _state.value.copy(
                    proxyRunning = true,
                    cfgIsPatched = anyPatched,
                    needsSafGrant = false,
                    safGrantTarget = null,
                    cfgCopyBackPath = null
                )
                return@launch
            }

            if ((!anyPatched && !retroArchPatchedThisRun && !dolphinPatchedThisRun && !ppssppPatchedThisRun) || proxyRunning) {
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
                ConfigPatchResult(success = true, message = "RetroArch not patched this run.")
            }
            val retroArchRevertedTarget = retroArchResult.success && retroArchResult.copyBackPath == null

            val dolphinResult = if (dolphinPatchedThisRun || dolphinPatched) {
                val restoreDolphinHardcore = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, false)
                withContext(Dispatchers.IO) {
                    revertDolphinCfg(app, dolphinTreeUri, restoreDolphinHardcore)
                }
            } else {
                ConfigPatchResult(success = true, message = "Dolphin not patched this run.", skippedNotInstalled = true)
            }
            val dolphinRevertedTarget = dolphinResult.success && dolphinResult.copyBackPath == null

            val ppssppResult = if (ppssppPatchedThisRun || ppssppPatched) {
                val restorePpssppHardcore = prefs.getBoolean(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED, false)
                withContext(Dispatchers.IO) {
                    revertPpssppCfg(app, ppssppTreeUri, restorePpssppHardcore)
                }
            } else {
                ConfigPatchResult(success = true, message = "PPSSPP not patched this run.", skippedNotInstalled = true)
            }
            val ppssppRevertedTarget = ppssppResult.success && ppssppResult.copyBackPath == null

            val broadcastResults = revertBroadcastEmulators(app, prefs)
            val failedBroadcastRevert = broadcastResults.values.firstOrNull { !it.success }

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

            if (ppssppRevertedTarget) {
                prefs.edit {
                    remove(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED)
                    remove(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN)
                }
            }

            val needsSafGrant = retroArchResult.needsSafGrant || dolphinResult.needsSafGrant || ppssppResult.needsSafGrant
            val safGrantTarget = when {
                retroArchResult.needsSafGrant -> SafGrantTarget.RetroArch
                dolphinResult.needsSafGrant -> SafGrantTarget.Dolphin
                ppssppResult.needsSafGrant -> SafGrantTarget.Ppsspp
                else -> null
            }
            val cfgCopyBackPath = retroArchResult.copyBackPath ?: dolphinResult.copyBackPath ?: ppssppResult.copyBackPath

            _state.value = _state.value.copy(
                proxyRunning = false,
                cfgIsPatched = !(retroArchRevertedTarget && dolphinRevertedTarget && ppssppRevertedTarget && failedBroadcastRevert == null),
                needsSafGrant = needsSafGrant,
                safGrantTarget = safGrantTarget,
                cfgCopyBackPath = cfgCopyBackPath
            )

            if (!retroArchRevertedTarget) {
                SnackbarManager.showError(retroArchResult.message)
            } else if (!dolphinRevertedTarget && !dolphinResult.needsSafGrant) {
                SnackbarManager.showError(dolphinResult.message)
            } else if (!ppssppRevertedTarget && !ppssppResult.needsSafGrant) {
                SnackbarManager.showError(ppssppResult.message)
            } else if (failedBroadcastRevert != null) {
                SnackbarManager.showError(failedBroadcastRevert.message)
            }
        }
    }

    private fun restoreDolphinCredentialsOnLaunch(emulatorSupport: EmulatorSupport) {
        if (!emulatorSupport.isEnabled(Emulator.Dolphin)) return

        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val storedCredentials = loadLoginCredentials(db) ?: return@withContext
                restoreDolphinCredentials(app, loadDolphinSafUri(), storedCredentials)
            }
        }
    }

    private suspend fun requireCredentials(pendingAction: PendingCredentialAction): LoginCredentials? {
        val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
        if (credentials == null) {
            pendingCredentialAction = pendingAction
            _events.tryEmit(MainUiEvent.PromptCredentialsForCaching)
        }
        return credentials
    }

    // Used to optionally tie a monthly donation to the logged-in RA account (for the
    // manage-subscription feature), and to look up/manage that subscription from Settings.
    // Unlike requireCredentials, this doesn't prompt for login — both donating and checking
    // subscription status are meant to work fine even when logged out.
    suspend fun currentRaCredentials(): LoginCredentials? =
        withContext(Dispatchers.IO) { loadLoginCredentials(db) }

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

    fun refreshShizukuStatus() {
        val app = getApplication<Application>()
        _state.value = _state.value.copy(shizukuStatus = resolveShizukuStatus(app))
    }

    fun requestShizukuPermission() {
        when (_state.value.shizukuStatus) {
            ShizukuStatus.PermissionDenied -> _events.tryEmit(MainUiEvent.RequestShizukuPermission)
            ShizukuStatus.NotInstalled,
            ShizukuStatus.NotRunning -> _events.tryEmit(MainUiEvent.OpenShizukuGuide)
            else -> Unit
        }
    }

    fun onShizukuPermissionGranted() {
        val app = getApplication<Application>()
        refreshShizukuStatus()

        if (!_state.value.manualEmulatorPatchingEnabled || _state.value.proxyRunning) {
            return
        }

        if (_state.value.shizukuStatus != ShizukuStatus.Ready || _state.value.shizukuManualPatchingEnabled) {
            return
        }

        PrefsConstants.saveShizukuManualPatchingEnabled(app, true)
        _state.value = _state.value.copy(shizukuManualPatchingEnabled = true)
        SnackbarManager.showMessage(str(R.string.manual_patching_shizuku_enabled), SnackbarDuration.Indefinite)
    }

    fun toggleShizukuManualPatchingEnabled() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.shizukuOperationInProgress) return@launch

            val enable = !_state.value.shizukuManualPatchingEnabled
            _state.value = _state.value.copy(shizukuOperationInProgress = true)
            try {
                if (enable && _state.value.shizukuStatus != ShizukuStatus.Ready) {
                    requestShizukuPermission()
                    return@launch
                }

                refreshShizukuStatus()

                PrefsConstants.saveShizukuManualPatchingEnabled(app, enable)
                _state.value = _state.value.copy(shizukuManualPatchingEnabled = enable)
                if (enable) {
                    SnackbarManager.showMessage(str(R.string.manual_patching_shizuku_enabled), SnackbarDuration.Long)
                } else {
                    SnackbarManager.showMessage(str(R.string.manual_patching_shizuku_disabled), SnackbarDuration.Long)
                }
            } finally {
                _state.value = _state.value.copy(shizukuOperationInProgress = false)
            }
        }
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
        if (pendingAddRomUris.isNotEmpty() && remaining.isEmpty()) {
            val uris = pendingAddRomUris
            pendingAddRomUris = emptyList()
            addRom(uris)
            return
        }
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

    fun onPpssppShizukuRootModeSelected(usesCustomRoot: Boolean) {
        val app = getApplication<Application>()
        pendingPpssppShizukuRootModePrompt = false
        _state.value = _state.value.copy(showPpssppShizukuRootModePrompt = false)

        if (usesCustomRoot) {
            PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.CustomRoot)
            _state.value = _state.value.copy(
                ppssppShizukuRootModeUnknown = false,
                needsSafGrant = true,
                safGrantTarget = SafGrantTarget.Ppsspp,
                pendingSafGrantTargets = listOf(SafGrantTarget.Ppsspp)
            )
            return
        }

        PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.DefaultPackagePath)
        _state.value = _state.value.copy(ppssppShizukuRootModeUnknown = false)
        if (pendingProxyStart) {
            startProxyInternal(loadSafUri())
        }
    }

    fun resetPpssppShizukuLocationChoice() {
        val app = getApplication<Application>()
        PrefsConstants.clearPpssppSafUri(app)
        PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.Unknown)
        _state.value = _state.value.copy(ppssppShizukuRootModeUnknown = true)
        SnackbarManager.showMessage(str(R.string.ppsspp_shizuku_root_mode_reset))
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
                    SafGrantTarget.Ppsspp -> {
                        PrefsConstants.clearPpssppSafUri(app)
                        PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.Unknown)
                        _state.value = _state.value.copy(ppssppShizukuRootModeUnknown = true)
                        SnackbarManager.showMessage(str(R.string.smart_cache_requires_ppsspp_access), SnackbarDuration.Indefinite)
                    }
                    SafGrantTarget.AllFilesAccess -> {
                        pendingAddRomUris = emptyList()
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
        pendingPpssppShizukuRootModePrompt = false
        _state.value = _state.value.copy(
            pendingSafGrantTargets = emptyList(),
            needsSafGrant = false,
            safGrantTarget = null,
            showPpssppShizukuRootModePrompt = false
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
            SafGrantTarget.Ppsspp -> {
                PrefsConstants.clearPpssppSafUri(app)
                PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.Unknown)
                _state.value = _state.value.copy(ppssppShizukuRootModeUnknown = true)
                setEmulatorEnabledInternal(Emulator.Ppsspp, false)
                SnackbarManager.showMessage(str(R.string.proxy_start_aborted_ppsspp_saf_rejected), SnackbarDuration.Indefinite)
            }
            SafGrantTarget.AllFilesAccess -> {
                pendingAddRomUris = emptyList()
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
            val flushed = db.pendingAwardDao().getAllByStatus(PENDING_AWARD_STATUS_FLUSHED)
            db.pendingAwardDao().deleteByStatuses(listOf(PENDING_AWARD_STATUS_FLUSHED))
            flushed.forEach { deleteAwardImages(application, it.achievementId) }
        }
    }

    fun validateToken(force: Boolean = true) {
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
            val now = System.currentTimeMillis()
            if (!force && now - lastTokenValidationAttemptAt < TOKEN_VALIDATION_COOLDOWN_MS) {
                Log.i("RAProxy/Auth", "validateToken: skipped — within cooldown of previous attempt")
                return@launch
            }
            lastTokenValidationAttemptAt = now
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

    private fun prepareProxyPortForStart(): Int? {
        val app = getApplication<Application>()
        val port = PrefsConstants.loadProxyPort(app)
        if (!isLoopbackPortAvailable(port)) {
            SnackbarManager.showError(str(R.string.proxy_port_unavailable, port))
            return null
        }

        return port
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

                if (prepareProxyPortForStart() == null) {
                    pendingProxyStart = false
                    return@launch
                }

                if (loadManualEmulatorPatchingEnabled()) {
                    if (loadShizukuManualPatchingEnabled() && emulatorSupport.hasAnyShizukuManagedEnabled) {
                        if (shouldPromptForPpssppShizukuRootMode(emulatorSupport)) {
                            pendingPpssppShizukuRootModePrompt = true
                            _state.value = _state.value.copy(
                                proxyToggleInProgress = false,
                                showPpssppShizukuRootModePrompt = true
                            )
                            _events.tryEmit(MainUiEvent.PromptPpssppShizukuRootMode)
                            return@launch
                        }

                        val shizukuResult = withContext(Dispatchers.IO) {
                            executeShizukuManualPatch(app, emulatorSupport, "patch")
                        }
                        refreshShizukuStatus()
                        if (shizukuResult.success) {
                            saveShizukuHardcoreWasEnabled(app, shizukuResult.hardcoreWasEnabled)
                        }
                        if (!shizukuResult.success) {
                            if (shizukuResult.needsPpssppSafGrant) {
                                PrefsConstants.savePpssppRootMode(app, PrefsConstants.PpssppRootMode.CustomRoot)
                                PrefsConstants.clearPpssppSafUri(app)
                                _state.value = _state.value.copy(
                                    needsSafGrant = true,
                                    safGrantTarget = SafGrantTarget.Ppsspp,
                                    pendingSafGrantTargets = listOf(SafGrantTarget.Ppsspp),
                                    ppssppShizukuRootModeUnknown = false
                                )
                                return@launch
                            }

                            pendingProxyStart = false
                            SnackbarManager.showError(shizukuResult.message)
                            return@launch
                        }
                    }

                    patchBroadcastEmulators(app, prefs, emulatorSupport)?.let { failure ->
                        pendingProxyStart = false
                        SnackbarManager.showError(failure.message)
                        return@launch
                    }

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
                    if (!alreadyRunning) {
                        SnackbarManager.showMessage(str(R.string.proxy_started_success))
                    }
                    maybeShowSmartCachePrompt()
                    validateToken()
                    return@launch
                }

                val retroArchTreeUri = treeUri ?: loadSafUri()
                val dolphinTreeUri = loadDolphinSafUri()
                val ppssppTreeUri = loadPpssppSafUri()

                val result = if (emulatorSupport.isEnabled(Emulator.RetroArch)) {
                    withContext(Dispatchers.IO) { patchRetroArchCfg(app, retroArchTreeUri) }
                } else {
                    ConfigPatchResult(success = true, message = "RetroArch disabled.")
                }
                if (emulatorSupport.isEnabled(Emulator.RetroArch)) {
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

                val dolphinStoredCredentials = if (emulatorSupport.isEnabled(Emulator.Dolphin)) {
                    withContext(Dispatchers.IO) { loadLoginCredentials(db) }
                } else {
                    null
                }
                val dolphinResult = if (emulatorSupport.isEnabled(Emulator.Dolphin)) {
                    withContext(Dispatchers.IO) {
                        patchDolphinCfg(app, dolphinTreeUri, dolphinStoredCredentials)
                    }
                } else {
                    ConfigPatchResult(success = true, message = "Dolphin disabled.", skippedNotInstalled = true)
                }
                if (emulatorSupport.isEnabled(Emulator.Dolphin)) {
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

                val ppssppResult = if (emulatorSupport.isEnabled(Emulator.Ppsspp)) {
                    withContext(Dispatchers.IO) {
                        patchPpssppCfg(app, ppssppTreeUri)
                    }
                } else {
                    ConfigPatchResult(success = true, message = "PPSSPP disabled.", skippedNotInstalled = true)
                }
                if (emulatorSupport.isEnabled(Emulator.Ppsspp)) {
                    if (ppssppResult.needsSafGrant) {
                        PrefsConstants.clearPpssppSafUri(app)
                        _state.value = _state.value.copy(
                            needsSafGrant = true,
                            safGrantTarget = SafGrantTarget.Ppsspp,
                            pendingSafGrantTargets = listOf(SafGrantTarget.Ppsspp)
                        )
                        return@launch
                    } else if (ppssppResult.invalidSafGrant) {
                        PrefsConstants.clearPpssppSafUri(app)
                        SnackbarManager.showError(ppssppResult.message)
                        pendingProxyStart = false
                        return@launch
                    } else if (!ppssppResult.success && !ppssppResult.skippedNotInstalled) {
                        SnackbarManager.showError(ppssppResult.message)
                        pendingProxyStart = false
                        return@launch
                    } else if (ppssppResult.success && !ppssppResult.skippedNotInstalled) {
                        prefs.edit {
                            putBoolean(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED, ppssppResult.hardcoreWasEnabled)
                            putBoolean(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN, true)
                        }
                    }
                } else {
                    prefs.edit { remove(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN) }
                }

                patchBroadcastEmulators(app, prefs, emulatorSupport)?.let { failure ->
                    SnackbarManager.showError(failure.message)
                    pendingProxyStart = false
                    return@launch
                }

                val credentialsToCache = selectImportedCredentials(
                    retroArch = result.credentials,
                    dolphin = dolphinResult.credentials,
                    ppsspp = ppssppResult.credentials
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
                if (!alreadyRunning) {
                    SnackbarManager.showMessage(str(R.string.proxy_started_success))
                }
                maybeShowSmartCachePrompt()
                validateToken()
            } finally {
                delay(250.milliseconds)
                _state.value = _state.value.copy(proxyToggleInProgress = false)
            }
        }
    }

    /**
     * Called once, synchronously, right after the user actively starts the proxy. Shows the
     * smart cache prompt only when it makes sense to seed the cache for this session. No state
     * is retained: if the conditions aren't met at start time, the prompt simply doesn't appear.
     */
    private fun maybeShowSmartCachePrompt() {
        val currentState = _state.value
        if (!currentState.proxyRunning) return
        if (isSmartCacheDisabledForShizuku(currentState)) return
        if (!currentState.smartCachingEnabled) return
        if (currentState.cachedGames.isNotEmpty()) return
        if (!currentState.isOnline) return
        if (!currentState.hasLoginCredentials) return
        _events.tryEmit(MainUiEvent.PromptSmartCacheAfterProxyStart)
    }

    fun isSmartCacheDisabledForShizuku(state: MainUiState = _state.value): Boolean =
        state.manualEmulatorPatchingEnabled && state.shizukuManualPatchingEnabled

    fun stopProxy(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.proxyToggleInProgress) return@launch

            _state.value = _state.value.copy(proxyToggleInProgress = true)

            try {
                if (loadManualEmulatorPatchingEnabled()) {
                    val shizukuEnabled = loadShizukuManualPatchingEnabled()
                    val shizukuResult = if (shizukuEnabled && loadEmulatorSupport(app).hasAnyShizukuManagedEnabled) {
                        withContext(Dispatchers.IO) {
                            executeShizukuManualPatch(
                                context = app,
                                support = loadEmulatorSupport(app),
                                action = "revert",
                                restoreHardcore = loadShizukuHardcoreWasEnabled(app)
                            )
                        }.also {
                            refreshShizukuStatus()
                            if (it.success) clearShizukuHardcoreWasEnabled(app)
                        }
                    } else {
                        null
                    }
                    val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                    val broadcastResults = revertBroadcastEmulators(app, prefs)
                    val failedBroadcastRevert = broadcastResults.values.firstOrNull { !it.success }

                    ProxyService.stop(app)
                    _state.value = _state.value.copy(
                        proxyRunning = false,
                        cfgIsPatched = null,
                        needsSafGrant = false,
                        safGrantTarget = null,
                        cfgCopyBackPath = null
                    )
                    if ((shizukuResult == null || shizukuResult.success) && failedBroadcastRevert == null) {
                        SnackbarManager.showMessage(str(R.string.proxy_stopped_success))
                    } else if (failedBroadcastRevert != null) {
                        SnackbarManager.showError(failedBroadcastRevert.message)
                    } else {
                        SnackbarManager.showError(shizukuResult?.message ?: "Failed to stop proxy.")
                    }
                    return@launch
                }

                val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                val retroArchTreeUri = treeUri ?: loadSafUri()
                val dolphinTreeUri = loadDolphinSafUri()
                val ppssppTreeUri = loadPpssppSafUri()
                val retroArchPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_PATCHED_THIS_RUN, false)
                val result = if (retroArchPatchedThisRun) {
                    val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_RETROARCH_HARDCORE_WAS_ENABLED, false)
                    withContext(Dispatchers.IO) { revertRetroArchCfg(app, retroArchTreeUri, restoreHardcore) }
                } else {
                    ConfigPatchResult(success = true, message = "RetroArch not patched this run.")
                }
                val revertedTarget = result.success && result.copyBackPath == null

                val dolphinPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_PATCHED_THIS_RUN, false)
                val dolphinResult = if (dolphinPatchedThisRun) {
                    val restoreDolphinHardcore = prefs.getBoolean(PrefsConstants.KEY_DOLPHIN_HARDCORE_WAS_ENABLED, false)
                    withContext(Dispatchers.IO) {
                        revertDolphinCfg(app, dolphinTreeUri, restoreDolphinHardcore)
                    }
                } else {
                    ConfigPatchResult(success = true, message = "Dolphin not patched this run.", skippedNotInstalled = true)
                }

                val ppssppPatchedThisRun = prefs.getBoolean(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN, false)
                val ppssppResult = if (ppssppPatchedThisRun) {
                    val restorePpssppHardcore = prefs.getBoolean(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED, false)
                    withContext(Dispatchers.IO) {
                        revertPpssppCfg(app, ppssppTreeUri, restorePpssppHardcore)
                    }
                } else {
                    ConfigPatchResult(success = true, message = "PPSSPP not patched this run.", skippedNotInstalled = true)
                }

                val broadcastResults = revertBroadcastEmulators(app, prefs)
                val failedBroadcastRevert = broadcastResults.values.firstOrNull { !it.success }

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
                if (ppssppResult.success && ppssppResult.copyBackPath == null) {
                    prefs.edit {
                        remove(PrefsConstants.KEY_PPSSPP_HARDCORE_WAS_ENABLED)
                        remove(PrefsConstants.KEY_PPSSPP_PATCHED_THIS_RUN)
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

                if (ppssppResult.needsSafGrant) {
                    PrefsConstants.clearPpssppSafUri(app)
                    _state.value = _state.value.copy(
                        needsSafGrant = true,
                        safGrantTarget = SafGrantTarget.Ppsspp
                    )
                } else if (ppssppResult.invalidSafGrant) {
                    PrefsConstants.clearPpssppSafUri(app)
                    SnackbarManager.showError(ppssppResult.message)
                } else if (!ppssppResult.success && !ppssppResult.skippedNotInstalled) {
                    SnackbarManager.showError(ppssppResult.message)
                } else if (failedBroadcastRevert != null && !failedBroadcastRevert.skippedNotInstalled) {
                    SnackbarManager.showError(failedBroadcastRevert.message)
                }
            } finally {
                delay(250.milliseconds)
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
                checkRetroArchIsPatched(app, treeUri) ||
                    checkIsDolphinPatched(app, loadDolphinSafUri()) ||
                    checkIsPpssppPatched(app, loadPpssppSafUri()) ||
                    Emulator.BROADCAST_MANAGED.any { checkIsBroadcastPatched(app, it) }
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

            val hasPlaylistFile = fileUris.any { uri ->
                val name = DocumentFile.fromSingleUri(app, uri)?.name ?: ""
                hasExtension(name, "cue", "m3u")
            }
            if (hasPlaylistFile && !hasAllFilesAccess()) {
                pendingAddRomUris = fileUris
                _state.value = _state.value.copy(
                    needsSafGrant = true,
                    safGrantTarget = SafGrantTarget.AllFilesAccess,
                    pendingSafGrantTargets = listOf(SafGrantTarget.AllFilesAccess)
                )
                return@launch
            }

            val credentials = requireCredentials(PendingCredentialAction.AddRom(fileUris)) ?: return@launch
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

    fun clearPermissions() {
        val app = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            app.contentResolver.persistedUriPermissions.toList().forEach { permission ->
                val flags =
                    (if (permission.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (permission.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

                if (flags == 0) return@forEach

                runCatching {
                    app.contentResolver.releasePersistableUriPermission(permission.uri, flags)
                }
            }

            PrefsConstants.clearPermissions(app)
            pendingPpssppShizukuRootModePrompt = false

            _state.value = _state.value.clearedPermissions()

            checkCfgPatched(treeUri = loadSafUri())
            SnackbarManager.showMessage(str(R.string.permissions_cleared))
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

    private var scanJob: Job? = null

    fun cancelScan() {
        if (scanJob?.isActive != true) return
        val abortingMessage = str(R.string.scan_aborting)
        _state.value = _state.value.copy(scanProgress = abortingMessage)
        SnackbarManager.showProgress(abortingMessage)
        scanJob?.cancel()
    }

    fun scanRoms(treeUri: Uri) {
        val app = getApplication<Application>()
        scanJob = viewModelScope.launch {
            if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES), SnackbarDuration.Indefinite)
                return@launch
            }
            val credentials = requireCredentials(PendingCredentialAction.ScanRoms(treeUri)) ?: return@launch
            val startingMessage = str(R.string.scan_starting)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage, onAbort = ::cancelScan)
            var completionMessage: String? = null
            var completionDuration = SnackbarDuration.Indefinite
            try {
                val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
                val result = withContext(Dispatchers.IO) {
                    scanRomFolder(app, treeUri, credentials, userAgent, db) { current, total, fileName ->
                        val progressMessage = str(R.string.scan_progress, current, total, fileName)
                        _state.value = _state.value.copy(scanProgress = progressMessage)
                        SnackbarManager.showProgress(progressMessage, onAbort = ::cancelScan)
                    }
                }
                completionMessage = if (result.limitReached) {
                    str(R.string.scan_complete_limit, result.matched, result.total, result.skipped, MAX_CACHED_GAMES)
                } else {
                    str(R.string.scan_complete, result.matched, result.total, result.skipped)
                }
            } catch (c: CancellationException) {
                Log.i("RAProxy/Scan", "scanRoms aborted for treeUri=$treeUri")
                completionMessage = str(R.string.scan_aborted)
                completionDuration = SnackbarDuration.Short
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
                SnackbarManager.showMessage(it, completionDuration)
            }
        }
    }

    fun deleteCachedGame(game: CachedGame) {
        removeCachedGamesFromState(setOf(game.gameId))
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.patchPrefix(game.gameId))
            deleteCachedImagesForGame(application, game.gameId)
        }
    }

    fun deleteConsoleGames(consoleId: Int) {
        val games = _state.value.cachedGames.filter { it.consoleId == consoleId }
        if (games.isEmpty()) return
        val gameIds = games.map { it.gameId }.toSet()
        removeCachedGamesFromState(gameIds)
        viewModelScope.launch(Dispatchers.IO) {
            games.forEach { game ->
                db.cacheDao().deleteByKeyPrefix(CacheKeys.patchPrefix(game.gameId))
                deleteCachedImagesForGame(application, game.gameId)
            }
        }
    }

    private fun removeCachedGamesFromState(gameIds: Set<String>) {
        pendingDeletedGameIds.addAll(gameIds)
        val remaining = _state.value.cachedGames.filter { it.gameId !in gameIds }
        _cachedGames.value = remaining
        _state.value = _state.value.copy(cachedGames = remaining)
    }

    fun refreshGames() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val credentials = requireCredentials(PendingCredentialAction.RefreshGames) ?: return@launch
            val refreshTargets = withContext(Dispatchers.IO) { loadCachedGameRefreshTargets(db) }
            val startingMessage = str(R.string.refresh_progress, 0, refreshTargets.size)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage)
            val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
            withContext(Dispatchers.IO) {
                for ((index, target) in refreshTargets.withIndex()) {
                    val title = _state.value.cachedGames.firstOrNull { it.gameId == target.gameId.toString() }?.title
                        ?: target.gameId.toString()
                    val progressMessage = str(R.string.refresh_progress_named, index + 1, refreshTargets.size, title)
                    _state.value = _state.value.copy(scanProgress = progressMessage)
                    SnackbarManager.showProgress(progressMessage)
                    refreshCachedGameOfflineBundle(
                        context = app,
                        target = target,
                        creds = credentials,
                        userAgent = userAgent,
                        db = db,
                        notificationMode = RefreshNotificationMode.Foreground
                    )
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = null
            )
            SnackbarManager.showProgress(null)
            SnackbarManager.showMessage(str(R.string.refresh_complete, refreshTargets.size), SnackbarDuration.Indefinite)
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
            val credentials = requireCredentials(PendingCredentialAction.SmartCache) ?: return@launch
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
                        ppssppTreeUri = loadPpssppSafUri(),
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
                                SmartCacheEmulator.Ppsspp -> add(SafGrantTarget.Ppsspp)
                                SmartCacheEmulator.WatermelonDs -> Unit
                                SmartCacheEmulator.Armsx1 -> Unit
                                SmartCacheEmulator.Armsx2 -> Unit
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
                                "needs_ppsspp_saf_grant" -> SafGrantTarget.Ppsspp
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
                    "needs_ppsspp_saf_grant" -> str(R.string.smart_cache_requires_ppsspp_access)
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

    private class ParsedAchievement(
        val id: Int,
        val title: String?,
        val description: String?,
        val points: Int,
        val badgeName: String?,
        val core: Boolean
    )

    private class ParsedPatch(
        val cacheKey: String,
        val cachedAt: Long,
        val gameId: String,
        val user: String,
        val title: String,
        val consoleId: Int,
        val imagePath: String?,
        val imageUrl: String?,
        val achievements: List<ParsedAchievement>
    )

    private class CachedUnlocks(val cachedAt: Long, val ids: Set<Int>)

    private inner class PatchView(
        val summary: CacheEntrySummary,
        val parsed: ParsedPatch,
        private val badgeNames: Set<String>
    ) {
        val gameId: String get() = parsed.gameId
        val user: String get() = parsed.user
        val achievements: List<ParsedAchievement> get() = parsed.achievements

        val imageIconUrl: String? by lazy {
            parsed.gameId.toIntOrNull()?.let { resolveCachedGameIconPath(application, it) }
                ?: parsed.imagePath?.let { resolveCachedStaticAsset(application, it)?.absolutePath }
                ?: parsed.imageUrl
        }

        fun badgeUrl(achievement: ParsedAchievement): String? = achievement.badgeName?.let { name ->
            if ("$name.png" in badgeNames) cachedBadgePath(application, name)
            else "https://i.retroachievements.org/Badge/$name.png"
        }
    }

    private class AchievementRef(val patch: PatchView, val achievement: ParsedAchievement)

    private fun parsePatch(summary: CacheEntrySummary, body: String): ParsedPatch? {
        val parts = summary.cacheKey.split(":")
        if (parts.size < 3) return null
        val patchData = runCatching { JSONObject(body).getJSONObject("PatchData") }.getOrNull()
        return ParsedPatch(
            cacheKey = summary.cacheKey,
            cachedAt = summary.cachedAt,
            gameId = parts[1],
            user = parts[2],
            title = patchData?.optString("Title") ?: parts[1],
            consoleId = patchData?.optInt("ConsoleID", 0) ?: 0,
            imagePath = patchData?.let(::patchImagePath),
            imageUrl = patchData?.let(::patchImageUrl),
            achievements = parseAchievements(patchData?.optJSONArray("Achievements"))
        )
    }

    private fun parseAchievements(achievements: JSONArray?): List<ParsedAchievement> {
        if (achievements == null) return emptyList()
        return buildList(achievements.length()) {
            for (i in 0 until achievements.length()) {
                val achievement = achievements.optJSONObject(i) ?: continue
                val achievementId = achievement.optInt("ID", 0)
                if (achievementId == 0) continue
                add(
                    ParsedAchievement(
                        id = achievementId,
                        title = achievement.optString("Title").takeIf { it.isNotEmpty() },
                        description = achievement.optString("Description").takeIf { it.isNotEmpty() },
                        points = achievement.optInt("Points", 0),
                        badgeName = achievement.optString("BadgeName").takeIf { it.isNotEmpty() },
                        core = achievement.optInt("Flags", RC_ACHIEVEMENT_FLAG_CORE) == RC_ACHIEVEMENT_FLAG_CORE
                    )
                )
            }
        }
    }

    private suspend fun loadPatchViews(
        summaries: List<CacheEntrySummary>,
        badgeNames: Set<String>
    ): List<PatchView> {
        val views = ArrayList<PatchView>(summaries.size)
        for (summary in summaries) {
            val parsed = patchCache[summary.id]
                ?.takeIf { it.cachedAt == summary.cachedAt && it.cacheKey == summary.cacheKey }
                ?: db.cacheDao().bodyForSummary(summary)
                    ?.let { body -> parsePatch(summary, body) }
                    ?.also { patchCache[summary.id] = it }
                ?: continue
            views += PatchView(summary, parsed, badgeNames)
        }
        patchCache.keys.retainAll(summaries.mapTo(HashSet()) { it.id })
        return views
    }

    private fun buildAchievementIndex(patches: List<PatchView>): Map<Int, AchievementRef> = buildMap {
        patches.forEach { patch ->
            patch.achievements.forEach { achievement ->
                putIfAbsent(achievement.id, AchievementRef(patch, achievement))
            }
        }
    }

    private suspend fun unlockedIdsFor(summary: CacheEntrySummary?): Set<Int> {
        if (summary == null) return emptySet()
        unlockCache[summary.id]?.let { if (it.cachedAt == summary.cachedAt) return it.ids }
        val body = db.cacheDao().bodyForSummary(summary) ?: return emptySet()
        val ids = runCatching {
            val unlocks = JSONObject(body).optJSONArray("UserUnlocks") ?: return@runCatching emptySet()
            buildSet(unlocks.length()) {
                for (i in 0 until unlocks.length()) add(unlocks.optInt(i))
            }
        }.getOrDefault(emptySet())
        unlockCache[summary.id] = CachedUnlocks(summary.cachedAt, ids)
        return ids
    }

    private suspend fun buildCachedGame(
        patch: PatchView,
        unlockSummary: CacheEntrySummary?,
        pendingAwardCount: Int
    ): CachedGame {
        val unlocked = unlockedIdsFor(unlockSummary)
        val coreAchievements = patch.achievements.filter { it.id > 0 && it.id != WARNING_ACHIEVEMENT_ID && it.core }
        return CachedGame(
            gameId = patch.gameId,
            title = patch.parsed.title,
            user = patch.user,
            consoleId = patch.parsed.consoleId,
            sourceRomPath = patch.summary.sourceRomPath,
            cachedAt = patch.summary.cachedAt,
            imageIconUrl = patch.imageIconUrl,
            unlockedCount = unlocked.size,
            pendingAwardCount = pendingAwardCount,
            totalAchievements = coreAchievements.size,
            achievements = coreAchievements
                .map { achievement ->
                    CachedAchievement(
                        id = achievement.id,
                        title = achievement.title ?: str(R.string.achievement_fallback, achievement.id),
                        description = achievement.description,
                        points = achievement.points,
                        badgeUrl = patch.badgeUrl(achievement),
                        unlocked = unlocked.contains(achievement.id)
                    )
                }
                .sortedByDescending { it.unlocked }
        )
    }

    private fun resolvePendingAward(
        award: PendingAward,
        achievementIndex: Map<Int, AchievementRef>
    ): PendingAwardUi {
        val params = parseFormParams(award.queryString.substringAfter("?", "") + "&" + award.requestBody)
        val achievementId = params["a"]?.toIntOrNull()
        val hardcore = params["h"] == "1"
        val ref = achievementId?.let(achievementIndex::get)

        var gameTitle = str(R.string.unknown_game)
        var gameIconUrl: String? = null
        var achievementTitle =
            if (achievementId != null) str(R.string.achievement_fallback, achievementId) else str(R.string.unknown_game)
        var points = 0
        var badgeUrl: String? = null

        if (ref != null) {
            gameTitle = ref.patch.parsed.title.takeIf { it.isNotEmpty() } ?: gameTitle
            gameIconUrl = ref.patch.imageIconUrl
            achievementTitle = ref.achievement.title ?: achievementTitle
            points = ref.achievement.points
            badgeUrl = ref.patch.badgeUrl(ref.achievement)
        } else {
            gameTitle = award.snapshotGameTitle ?: gameTitle
            achievementTitle = award.snapshotAchievementTitle ?: achievementTitle
            points = award.snapshotPoints
            badgeUrl = award.snapshotBadgeUrl
            gameIconUrl = award.snapshotGameIconUrl
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
        achievementIndex: Map<Int, AchievementRef>,
        awards: List<PendingAward>
    ): Map<String, Int> {
        if (achievementIndex.isEmpty() || awards.isEmpty()) return emptyMap()

        return buildMap {
            awards.forEach { award ->
                val achievementId = parsePendingAwardAchievementId(award) ?: return@forEach
                val gameId = achievementIndex[achievementId]?.patch?.gameId ?: return@forEach
                put(gameId, (get(gameId) ?: 0) + 1)
            }
        }
    }

    private fun parsePendingAwardAchievementId(award: PendingAward): Int? {
        val params = parseFormParams(award.queryString.substringAfter("?", "") + "&" + award.requestBody)
        return params["a"]?.toIntOrNull()
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
    }

    fun setHideSupportButtonEnabled(enabled: Boolean) {
        PrefsConstants.saveHideSupportButtonEnabled(getApplication(), enabled)
        _state.value = _state.value.copy(hideSupportButton = enabled)
    }

    fun setShowLockedAchievementsEnabled(enabled: Boolean) {
        PrefsConstants.saveShowLockedAchievementsEnabled(getApplication(), enabled)
        _state.value = _state.value.copy(showLockedAchievements = enabled)
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
            cfgIsPatched = if (enabled) null else _state.value.cfgIsPatched,
            shizukuManualPatchingEnabled = if (enabled) _state.value.shizukuManualPatchingEnabled else false,
            showPpssppShizukuRootModePrompt = false,
            ppssppShizukuRootModeUnknown = if (enabled) loadPpssppRootMode() == PrefsConstants.PpssppRootMode.Unknown else _state.value.ppssppShizukuRootModeUnknown
        )
        if (!enabled) {
            PrefsConstants.saveShizukuManualPatchingEnabled(app, false)
            pendingPpssppShizukuRootModePrompt = false
        }
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
                    app,
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
            validateToken()
        }
    }

    fun saveCredentialsForCaching(username: String, password: String) {
        val app = getApplication<Application>()
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()

        viewModelScope.launch {
            val loginCredentials = withContext(Dispatchers.IO) {
                loginAndCacheToken(
                    app,
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

            _state.value = _state.value.copy(hasLoginCredentials = true, authState = AuthState.Unknown)
            validateToken()

            val action = pendingCredentialAction
            pendingCredentialAction = null
            when (action) {
                PendingCredentialAction.SmartCache -> startSmartCache()
                PendingCredentialAction.RefreshGames -> refreshGames()
                is PendingCredentialAction.AddRom -> addRom(action.uris)
                is PendingCredentialAction.ScanRoms -> scanRoms(action.treeUri)
                null -> Unit
            }
        }
    }

    private suspend fun patchBroadcastEmulators(
        app: Application,
        prefs: SharedPreferences,
        emulatorSupport: EmulatorSupport
    ): BroadcastPatchResult? {
        for (emulator in Emulator.BROADCAST_MANAGED) {
            if (!emulatorSupport.isEnabled(emulator)) {
                prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
                continue
            }

            val result = withContext(Dispatchers.IO) { patchBroadcastCfg(app, emulator) }
            if (result.skippedNotInstalled) continue
            if (!result.success) return result
            prefs.edit { putBoolean(emulator.patchedThisRunPrefsKey, true) }
        }
        return null
    }

    private suspend fun revertBroadcastEmulators(
        app: Application,
        prefs: SharedPreferences
    ): Map<Emulator, BroadcastPatchResult> = Emulator.BROADCAST_MANAGED.associateWith { emulator ->
        val result = if (prefs.getBoolean(emulator.patchedThisRunPrefsKey, false)) {
            withContext(Dispatchers.IO) { revertBroadcastCfg(app, emulator) }
        } else {
            notPatchedThisRun(emulator)
        }
        if (result.success) {
            prefs.edit { remove(emulator.patchedThisRunPrefsKey) }
        }
        result
    }

    fun setEmulatorEnabled(emulator: Emulator, enabled: Boolean) {
        val support = loadEmulatorSupport(getApplication())
        if (!support.isInstalled(emulator) || support.installedCount == 1 || _state.value.proxyRunning) {
            return
        }
        setEmulatorEnabledInternal(emulator, enabled)
    }

    private fun setEmulatorEnabledInternal(emulator: Emulator, enabled: Boolean) {
        val app = getApplication<Application>()
        app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(emulator.enabledPrefsKey, enabled) }
        _state.value = _state.value.copy(emulators = loadEmulatorSupport(app))
    }

    fun setProxyPort(portText: String): Boolean {
        val port = portText.toIntOrNull()
            ?.takeIf(PrefsConstants::isValidProxyPort)
            ?: return false

        val app = getApplication<Application>()
        PrefsConstants.saveProxyPort(app, port)
        _state.value = _state.value.copy(proxyPort = port)
        return true
    }

    fun checkForAppUpdate(force: Boolean = false) {
        val app = getApplication<Application>()
        if (!PrefsConstants.loadAppUpdateCheckEnabled(app)) {
            _state.value = _state.value.copy(availableAppUpdate = null)
            return
        }
        val currentVersionName = BuildConfig.VERSION_NAME
        if (!hasValidatedInternet(connectivityManager)) {
            Log.i("RAProxy/Updates", "Skipping app update check; validated internet not available")
            return
        }

        val now = System.currentTimeMillis()
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
            if (shouldShowAppUpdateDialog(now)) {
                PrefsConstants.saveAppUpdateLastPromptedAt(app, now)
                _events.emit(MainUiEvent.ShowAppUpdate(update))
            }
        }
    }

    private fun shouldShowAppUpdateDialog(now: Long): Boolean {
        val app = getApplication<Application>()
        val lastPromptedAt = PrefsConstants.loadAppUpdateLastPromptedAt(app)
        return now - lastPromptedAt >= APP_UPDATE_CHECK_INTERVAL_MS
    }

    private fun loadAutostartPref(): Boolean =
        getApplication<Application>()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)

    private fun loadSmartCachingEnabled(): Boolean =
        PrefsConstants.loadSmartCachingEnabled(getApplication())

    private fun loadAppUpdateCheckEnabled(): Boolean =
        PrefsConstants.loadAppUpdateCheckEnabled(getApplication())

    private fun loadHideSupportButtonEnabled(): Boolean =
        PrefsConstants.loadHideSupportButtonEnabled(getApplication())

    private fun loadShowLockedAchievementsEnabled(): Boolean =
        PrefsConstants.loadShowLockedAchievementsEnabled(getApplication())

    private fun loadManualEmulatorPatchingEnabled(): Boolean =
        PrefsConstants.loadManualEmulatorPatchingEnabled(getApplication())

    private fun loadShizukuManualPatchingEnabled(): Boolean =
        PrefsConstants.loadShizukuManualPatchingEnabled(getApplication())

    private fun loadSafUri(): Uri? =
        PrefsConstants.loadSafUri(getApplication())

    private fun loadDolphinSafUri(): Uri? =
        PrefsConstants.loadDolphinSafUri(getApplication())

    private fun loadPpssppSafUri(): Uri? =
        PrefsConstants.loadPpssppSafUri(getApplication())

    private fun loadPpssppRootMode(): PrefsConstants.PpssppRootMode =
        PrefsConstants.loadPpssppRootMode(getApplication())

    private fun shouldPromptForPpssppShizukuRootMode(emulatorSupport: EmulatorSupport): Boolean {
        if (!emulatorSupport.isEnabled(Emulator.Ppsspp)) {
            return false
        }

        if (pendingPpssppShizukuRootModePrompt) {
            return false
        }

        return loadPpssppRootMode() == PrefsConstants.PpssppRootMode.Unknown
    }

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
                getApplication(),
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

    private suspend fun migrateUserCaseInCacheKeys() {
        val prefixes = listOf(
            CacheKeys.PREFIX_PATCH,
            CacheKeys.PREFIX_ACHIEVEMENTSETS,
            CacheKeys.PREFIX_UNLOCKS,
            CacheKeys.PREFIX_STARTSESSION,
        )
        for (prefix in prefixes) {
            val summaries = runCatching { db.cacheDao().getAllSummariesByPrefix(prefix) }.getOrDefault(emptyList())
            for (summary in summaries) {
                val newKey = lowercasedUserKey(summary.cacheKey, prefix) ?: continue
                if (newKey == summary.cacheKey) continue
                if (db.cacheDao().getSummary(newKey) != null) {
                    db.cacheDao().deleteByKey(summary.cacheKey)
                } else {
                    db.cacheDao().updateCacheKey(summary.cacheKey, newKey)
                }
            }
        }
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
            if (!shouldCompactAchievementSets("achievementsets", entry.responseBody)) return@forEach
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

private fun lowercasedUserKey(key: String, prefix: String): String? {
    return when (prefix) {
        CacheKeys.PREFIX_PATCH -> {
            val rest = key.removePrefix(prefix)
            val parts = rest.split(":")
            val gameId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
            val user = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
            val suffix = if (parts.size > 2) ":${parts.drop(2).joinToString(":")}" else ""
            "${prefix}$gameId:${user.lowercase()}$suffix"
        }
        CacheKeys.PREFIX_ACHIEVEMENTSETS -> {
            val hash = CacheKeys.parseAchievementSetsHash(key) ?: return null
            val user = CacheKeys.parseUserFromAchievementSetsKey(key) ?: return null
            "${prefix}$hash:${user.lowercase()}"
        }
        CacheKeys.PREFIX_UNLOCKS, CacheKeys.PREFIX_STARTSESSION -> {
            val rest = key.removePrefix(prefix)
            val parts = rest.split(":")
            val gameId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
            val user = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
            val suffix = if (parts.size > 2) ":${parts.drop(2).joinToString(":")}" else ""
            "${prefix}$gameId:${user.lowercase()}$suffix"
        }
        else -> null
    }
}

internal fun selectImportedCredentials(
    retroArch: ImportedCredentials?,
    dolphin: ImportedCredentials?,
    ppsspp: ImportedCredentials?
): ImportedCredentials? = when {
    retroArch is ImportedCredentials.Token -> retroArch
    dolphin is ImportedCredentials.Token -> dolphin
    ppsspp is ImportedCredentials.Token -> ppsspp
    retroArch is ImportedCredentials.Password -> retroArch
    else -> null
}

private fun notPatchedThisRun(emulator: Emulator): BroadcastPatchResult = BroadcastPatchResult(
    success = true,
    message = "${emulator.displayName} not patched this run.",
    skippedNotInstalled = true
)

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
