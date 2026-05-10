package com.raofflineproxy.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.raofflineproxy.proxy.cacheLoginCredentialsResponse
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.clearAllCachedImages
import com.raofflineproxy.proxy.deleteCachedImagesForGame
import com.raofflineproxy.proxy.HttpGetResult
import com.raofflineproxy.proxy.httpGet
import com.raofflineproxy.proxy.loginAndCacheToken
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.resolveCachedGameIconPath
import com.raofflineproxy.proxy.scanRomFolder
import com.raofflineproxy.service.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AuthState { Unknown, Valid, Invalid }

data class MainUiState(
    val proxyRunning: Boolean = false,
    val proxyToggleInProgress: Boolean = false,
    val isOnline: Boolean = false,
    val hasLoginCredentials: Boolean = false,
    val authState: AuthState = AuthState.Unknown,
    val autostartProxy: Boolean = false,
    val proxyPort: Int = PrefsConstants.DEFAULT_PROXY_PORT,
    val pendingAwards: List<PendingAwardUi> = emptyList(),
    val awardHistory: List<PendingAwardUi> = emptyList(),
    val cachedGames: List<CachedGame> = emptyList(),
    val needsSafGrant: Boolean = false,
    val cfgCopyBackPath: String? = null,
    val cfgIsPatched: Boolean? = null,
    val scanInProgress: Boolean = false,
    val scanProgress: String? = null,
    val flushInProgress: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val db = AppDatabase.getInstance(app)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private val _cachedGames = MutableStateFlow<List<CachedGame>>(emptyList())
    val cachedGames: StateFlow<List<CachedGame>> = _cachedGames.asStateFlow()
    private val connectivityManager =
        app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
        _state.value = _state.value.copy(
            autostartProxy = loadAutostartPref(),
            proxyPort = PrefsConstants.loadProxyPort(app)
        )
        validateToken()
        viewModelScope.launch {
            AwardFlusher.events.collect { event ->
                when (event) {
                    is FlushEvent.Started -> _state.value = _state.value.copy(flushInProgress = true)
                    is FlushEvent.Progress -> Unit
                    is FlushEvent.Completed -> {
                        SnackbarManager.showMessage(str(R.string.flush_completed_sent_only, event.flushed))
                        _state.value = _state.value.copy(
                            flushInProgress = false
                        )
                    }
                    is FlushEvent.ChainBroken -> {
                        SnackbarManager.showMessage(str(R.string.flush_chain_broken, event.index + 1, event.reason))
                        _state.value = _state.value.copy(flushInProgress = false)
                    }
                    is FlushEvent.RefreshFailed -> {
                        SnackbarManager.showMessage(str(R.string.flush_refresh_failed, event.reason))
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
                        } ?: patchData?.optString("ImageIcon")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { "$RA_HOST$it" }
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
                    if (_state.value.proxyRunning && games.isNotEmpty() && _state.value.authState != AuthState.Valid) {
                        validateToken()
                    }
                }
        }
    }

    private fun recoverPatchedCfgIfProxyStopped() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val treeUri = loadSafUri()
            val patched = withContext(Dispatchers.IO) { checkIsPatched(app, treeUri) }
            val proxyRunning = ProxyService.isRunning(app)

            if (!patched || proxyRunning) {
                _state.value = _state.value.copy(
                    proxyRunning = proxyRunning,
                    cfgIsPatched = patched
                )
                return@launch
            }

            val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_HARDCORE_WAS_ENABLED, false)
            val result = withContext(Dispatchers.IO) {
                revertRetroArchCfg(app, treeUri, restoreHardcore)
            }
            val revertedTarget = result.success && result.copyBackPath == null

            if (revertedTarget) {
                prefs.edit { remove(PrefsConstants.KEY_HARDCORE_WAS_ENABLED) }
            }

            _state.value = _state.value.copy(
                proxyRunning = false,
                cfgIsPatched = !revertedTarget,
                needsSafGrant = result.needsSafGrant,
                cfgCopyBackPath = result.copyBackPath
            )

            if (!revertedTarget) {
                SnackbarManager.showError(result.message)
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
            needsSafGrant = false
        )
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
                db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH).firstOrNull()
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
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.proxyToggleInProgress) return@launch

            _state.value = _state.value.copy(proxyToggleInProgress = true)

            try {
                val alreadyRunning = ProxyService.isRunning(app)
                val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit { remove(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT) }

                val result = withContext(Dispatchers.IO) { patchRetroArchCfg(app, treeUri) }
                if (result.needsSafGrant) {
                    PrefsConstants.clearSafUri(app)
                    _state.value = _state.value.copy(
                        needsSafGrant = true
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
                    if (result.success) SnackbarManager.showMessage(result.message)
                    else SnackbarManager.showError(result.message)
                    return@launch
                }
                if (!result.success) {
                    SnackbarManager.showError(result.message)
                    return@launch
                }
                prefs.edit {
                    putBoolean(
                        PrefsConstants.KEY_HARDCORE_WAS_ENABLED,
                        result.hardcoreWasEnabled
                    )
                }
                result.credentials?.let { credentials ->
                    withContext(Dispatchers.IO) { cacheRetroArchCredentials(credentials) }
                }
                ProxyService.start(app)
                _state.value = _state.value.copy(
                    proxyRunning = true,
                    cfgIsPatched = true,
                    authState = AuthState.Unknown
                )
                if (!alreadyRunning) {
                    SnackbarManager.showMessage(str(R.string.proxy_started_success))
                }
                validateToken()
            } finally {
                delay(250)
                _state.value = _state.value.copy(proxyToggleInProgress = false)
            }
        }
    }

    fun stopProxy(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.proxyToggleInProgress) return@launch

            _state.value = _state.value.copy(proxyToggleInProgress = true)

            try {
                val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_HARDCORE_WAS_ENABLED, false)
                val result = withContext(Dispatchers.IO) { revertRetroArchCfg(app, treeUri, restoreHardcore) }
                val revertedTarget = result.success && result.copyBackPath == null

                if (revertedTarget) {
                    prefs.edit {
                        remove(PrefsConstants.KEY_HARDCORE_WAS_ENABLED)
                        putBoolean(PrefsConstants.KEY_SKIP_NEXT_CFG_REVERT, true)
                    }
                }

                ProxyService.stop(app)

                _state.value = _state.value.copy(
                    proxyRunning = false,
                    cfgIsPatched = if (revertedTarget) false else _state.value.cfgIsPatched,
                    needsSafGrant = result.needsSafGrant,
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
            val patched = withContext(Dispatchers.IO) { checkIsPatched(app, treeUri) }
            _state.value = _state.value.copy(cfgIsPatched = patched)
        }
    }

    fun addRom(fileUris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (_state.value.cachedGames.size >= MAX_CACHED_GAMES) {
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES))
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
                    str(R.string.scan_add_complete_limit, matched, total, skipped, MAX_CACHED_GAMES)
                } else {
                    str(R.string.scan_add_complete, matched, total, skipped)
                }
            )
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_PATCH)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_GAMEID)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_UNLOCKS)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_STARTSESSION)
            clearAllCachedImages(application)
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
                SnackbarManager.showMessage(str(R.string.cached_games_limit_reached, MAX_CACHED_GAMES))
                return@launch
            }
            val credentials = requireCredentials() ?: return@launch
            val startingMessage = str(R.string.scan_starting)
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = startingMessage)
            SnackbarManager.showProgress(startingMessage)
            val userAgent = withContext(Dispatchers.IO) { proxyUserAgent(loadUserAgent(db)) }
            val result = withContext(Dispatchers.IO) {
                scanRomFolder(app, treeUri, credentials, userAgent, db) { current, total, fileName ->
                    val progressMessage = str(R.string.scan_progress, current, total, fileName)
                    _state.value = _state.value.copy(scanProgress = progressMessage)
                    SnackbarManager.showProgress(progressMessage)
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = null
            )
            SnackbarManager.showProgress(null)
            SnackbarManager.showMessage(
                if (result.limitReached) {
                    str(R.string.scan_complete_limit, result.matched, result.total, result.skipped, MAX_CACHED_GAMES)
                } else {
                    str(R.string.scan_complete, result.matched, result.total, result.skipped)
                }
            )
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
            SnackbarManager.showMessage(str(R.string.refresh_complete, games.size))
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
                        } ?: patchData.optString("ImageIcon")
                            .takeIf { it.isNotEmpty() }
                            ?.let { "$RA_HOST$it" }
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

    fun setProxyPort(portText: String): Boolean {
        val port = portText.toIntOrNull()
            ?.takeIf(PrefsConstants::isValidProxyPort)
            ?: return false

        val app = getApplication<Application>()
        PrefsConstants.saveProxyPort(app, port)
        _state.value = _state.value.copy(proxyPort = port)
        return true
    }

    private fun loadAutostartPref(): Boolean =
        getApplication<Application>()
            .getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PrefsConstants.KEY_AUTOSTART_PROXY, false)

    private fun loadSafUri(): Uri? =
        PrefsConstants.loadSafUri(getApplication())

    private suspend fun cacheRetroArchCredentials(credentials: RetroArchCfgCredentials) {
        val tokenCredentials = when (credentials) {
            is RetroArchCfgCredentials.Token -> LoginCredentials(
                credentials.username,
                credentials.token
            )

            is RetroArchCfgCredentials.Password -> loginAndCacheToken(
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
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
