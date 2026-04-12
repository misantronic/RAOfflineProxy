package com.raofflineproxy.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raofflineproxy.buildApiUrl
import com.raofflineproxy.PrefsConstants
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.parseFormParams
import com.raofflineproxy.proxyUserAgent
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.FlushEvent
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.httpGet
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.scanRomFolder
import com.raofflineproxy.service.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.content.edit

enum class AuthState { Unknown, Valid, Invalid }

data class MainUiState(
    val proxyRunning: Boolean = false,
    val isOnline: Boolean = false,
    val authState: AuthState = AuthState.Unknown,
    val autostartProxy: Boolean = false,
    val proxyPort: Int = PrefsConstants.DEFAULT_PROXY_PORT,
    val pendingAwards: List<PendingAwardUi> = emptyList(),
    val cachedGames: List<CachedGame> = emptyList(),
    val cfgPatchMessage: String? = null,
    val cfgPatchSuccess: Boolean? = null,
    val needsSafGrant: Boolean = false,
    val cfgCopyBackPath: String? = null,
    val cfgIsPatched: Boolean? = null,
    val scanInProgress: Boolean = false,
    val scanProgress: String? = null,
    val flushInProgress: Boolean = false,
    val flushProgress: String? = null,
    val clearCacheMessage: String? = null,
    val clearDatabaseMessage: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
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
            _state.value = _state.value.copy(isOnline = true)
        }
        override fun onLost(network: Network) {
            _state.value = _state.value.copy(isOnline = false)
        }
    }

    init {
        val online = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _state.value = _state.value.copy(isOnline = online)

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
                    is FlushEvent.Started -> _state.value = _state.value.copy(
                        flushInProgress = true,
                        flushProgress = str(R.string.flush_started)
                    )
                    is FlushEvent.Progress -> _state.value = _state.value.copy(
                        flushProgress = str(R.string.flush_progress, event.current, event.total)
                    )
                    is FlushEvent.Completed -> {
                        val msg = if (event.skippedStale > 0) {
                            str(R.string.flush_completed_with_stale, event.flushed, event.total, event.skippedStale)
                        } else {
                            str(R.string.flush_completed, event.flushed, event.total)
                        }
                        _state.value = _state.value.copy(
                            flushInProgress = false,
                            flushProgress = msg
                        )
                    }
                    is FlushEvent.ChainBroken -> _state.value = _state.value.copy(
                        flushInProgress = false,
                        flushProgress = str(R.string.flush_chain_broken, event.index + 1, event.reason)
                    )
                    is FlushEvent.RefreshFailed -> _state.value = _state.value.copy(
                        flushInProgress = false,
                        flushProgress = str(R.string.flush_refresh_failed, event.reason)
                    )
                }
            }
        }
        viewModelScope.launch {
            db.pendingAwardDao().observe().collect { awards ->
                val resolved = awards.map { award -> resolvePendingAward(award) }
                _state.value = _state.value.copy(pendingAwards = resolved)
            }
        }
        viewModelScope.launch {
            db.cacheDao().observePatchEntries()
                .map { entries ->
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
                        val imageIconUrl = patchData?.optString("ImageIcon")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { "$RA_HOST$it" }
                        val unlocksBody = db.cacheDao().get(CacheKeys.unlocks(gameId, user))?.responseBody
                        val unlockedCount = runCatching {
                            val json = JSONObject(unlocksBody ?: return@runCatching 0)
                            json.optJSONArray("UserUnlocks")?.length() ?: 0
                        }.getOrDefault(0)
                        val totalAchievements = patchData?.optJSONArray("Achievements")?.length() ?: 0
                        Log.d("RAProxy/Games", "game=$gameId unlockedCount=$unlockedCount total=$totalAchievements")
                        CachedGame(gameId = gameId, title = title, user = user, cachedAt = entry.cachedAt, imageIconUrl = imageIconUrl, unlockedCount = unlockedCount, totalAchievements = totalAchievements)
                    }
                }
                .collect { games ->
                    _cachedGames.value = games
                    _state.value = _state.value.copy(cachedGames = games)
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
                cfgPatchMessage = if (revertedTarget) null else result.message,
                cfgPatchSuccess = if (revertedTarget) null else false,
                needsSafGrant = result.needsSafGrant,
                cfgCopyBackPath = result.copyBackPath
            )
        }
    }

    private suspend fun requireCredentials(): com.raofflineproxy.proxy.LoginCredentials? {
        val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
        if (credentials == null) {
            _state.value = _state.value.copy(scanProgress = str(R.string.scan_no_login))
        }
        return credentials
    }

    fun clearTransientMessages() {
        _state.value = _state.value.copy(
            scanProgress = null,
            cfgPatchMessage = null,
            cfgPatchSuccess = null,
            cfgCopyBackPath = null,
            needsSafGrant = false,
            clearCacheMessage = null,
            clearDatabaseMessage = null
        )
    }

    fun clearFlushProgress() {
        if (_state.value.flushInProgress) return
        _state.value = _state.value.copy(flushProgress = null)
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
                try {
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
                    val body = httpGet(url, userAgent)
                    JSONObject(body).optBoolean("Success", false)
                } catch (e: Exception) {
                    Log.w("RAProxy/Auth", "validateToken: live check failed — ${e.message}")
                    false
                }
            }
            Log.i("RAProxy/Auth", "validateToken: live patch check valid=$valid")
            _state.value = _state.value.copy(authState = if (valid) AuthState.Valid else AuthState.Invalid)
        }
    }
    fun startProxy(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { patchRetroArchCfg(app, treeUri) }
            if (result.needsSafGrant) {
                _state.value = _state.value.copy(
                    needsSafGrant = true,
                    cfgPatchMessage = result.message,
                    cfgPatchSuccess = false
                )
                return@launch
            }
            if (result.copyBackPath != null) {
                _state.value = _state.value.copy(
                    cfgPatchMessage = result.message,
                    cfgPatchSuccess = result.success,
                    cfgCopyBackPath = result.copyBackPath
                )
                return@launch
            }
            if (!result.success) {
                _state.value = _state.value.copy(
                    cfgPatchMessage = result.message,
                    cfgPatchSuccess = false
                )
                return@launch
            }
            val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean(
                    PrefsConstants.KEY_HARDCORE_WAS_ENABLED,
                    result.hardcoreWasEnabled
                )
            }
            ProxyService.start(app)
            _state.value = _state.value.copy(
                proxyRunning = true,
                cfgIsPatched = true,
                authState = AuthState.Unknown
            )
            validateToken()
        }
    }

    fun stopProxy(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val prefs = app.getSharedPreferences(PrefsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val restoreHardcore = prefs.getBoolean(PrefsConstants.KEY_HARDCORE_WAS_ENABLED, false)

            ProxyService.stop(app)

            val result = withContext(Dispatchers.IO) { revertRetroArchCfg(app, treeUri, restoreHardcore) }
            val revertedTarget = result.success && result.copyBackPath == null

            if (revertedTarget) {
                prefs.edit { remove(PrefsConstants.KEY_HARDCORE_WAS_ENABLED) }
            }

            _state.value = _state.value.copy(
                proxyRunning = false,
                cfgIsPatched = if (revertedTarget) false else _state.value.cfgIsPatched,
                cfgPatchMessage = if (revertedTarget) null else result.message,
                cfgPatchSuccess = if (revertedTarget) null else false,
                needsSafGrant = result.needsSafGrant,
                cfgCopyBackPath = result.copyBackPath
            )
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
            val credentials = requireCredentials() ?: return@launch
            val total = fileUris.size
            var matched = 0
            val userAgent = withContext(Dispatchers.IO) { loadUserAgent(db) }
            for ((index, uri) in fileUris.withIndex()) {
                _state.value = _state.value.copy(scanInProgress = true, scanProgress = str(R.string.scan_hashing, index + 1, total))
                val result = withContext(Dispatchers.IO) {
                    scanRomFolder(app, uri, credentials, userAgent, db, singleFile = true) { _, _, fileName ->
                        _state.value = _state.value.copy(scanProgress = str(R.string.scan_looking_up, index + 1, total, fileName))
                    }
                }
                matched += result.matched
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = str(R.string.scan_add_complete, matched, total)
            )
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_PATCH)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_GAMEID)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_UNLOCKS)
            db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_STARTSESSION)
            _state.value = _state.value.copy(scanProgress = null, clearCacheMessage = str(R.string.cache_cleared))
        }
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix("")
            db.pendingAwardDao().getAll().forEach { db.pendingAwardDao().delete(it) }
            _state.value = _state.value.copy(scanProgress = null, clearDatabaseMessage = str(R.string.database_cleared))
        }
    }

    fun scanRoms(treeUri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val credentials = requireCredentials() ?: return@launch
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = str(R.string.scan_starting))
            withContext(Dispatchers.IO) { db.cacheDao().deleteByKeyPrefix(CacheKeys.PREFIX_GAMEID) }
            val userAgent = withContext(Dispatchers.IO) { loadUserAgent(db) }
            val result = withContext(Dispatchers.IO) {
                scanRomFolder(app, treeUri, credentials, userAgent, db) { current, total, fileName ->
                    _state.value = _state.value.copy(
                        scanProgress = str(R.string.scan_progress, current, total, fileName)
                    )
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = str(R.string.scan_complete, result.matched, result.total)
            )
        }
    }

    fun deleteCachedGame(game: CachedGame) {
        viewModelScope.launch(Dispatchers.IO) {
            db.cacheDao().deleteByKeyPrefix(CacheKeys.patchPrefix(game.gameId))
        }
    }

    fun refreshGames() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val credentials = requireCredentials() ?: return@launch
            val games = _state.value.cachedGames.reversed()
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = str(R.string.refresh_progress, 0, games.size))
            val userAgent = withContext(Dispatchers.IO) { loadUserAgent(db) }
            withContext(Dispatchers.IO) {
                for ((index, game) in games.withIndex()) {
                    _state.value = _state.value.copy(scanProgress = str(R.string.refresh_progress_named, index + 1, games.size, game.title))
                    val gameId = game.gameId.toIntOrNull() ?: continue
                    cacheGame(app, gameId, credentials, userAgent, db)
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = str(R.string.refresh_complete, games.size)
            )
        }
    }

    private suspend fun resolvePendingAward(award: com.raofflineproxy.data.PendingAward): PendingAwardUi {
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
                val patchData = JSONObject(entry.responseBody).getJSONObject("PatchData")
                val arr = patchData.getJSONArray("Achievements")
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.getInt("ID") == achievementId) {
                        gameTitle = patchData.optString("Title", gameTitle)
                        gameIconUrl = patchData.optString("ImageIcon")
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
            gameTitle = gameTitle,
            gameIconUrl = gameIconUrl,
            achievementTitle = achievementTitle,
            queuedAt = award.queuedAt,
            points = points,
            badgeUrl = badgeUrl,
            hardcore = hardcore,
            lastError = award.lastError
        )
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
}
