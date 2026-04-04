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
import com.raofflineproxy.R
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.data.AppDatabase
import com.raofflineproxy.data.CacheKeys
import com.raofflineproxy.data.CachedGame
import com.raofflineproxy.data.PendingAwardUi
import com.raofflineproxy.proxy.AwardFlusher
import com.raofflineproxy.proxy.FlushEvent
import com.raofflineproxy.proxy.cacheGame
import com.raofflineproxy.proxy.loadLoginCredentials
import com.raofflineproxy.proxy.loadUserAgent
import com.raofflineproxy.proxy.scanRomFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AuthState { Unknown, Valid, Invalid }

data class MainUiState(
    val proxyRunning: Boolean = false,
    val isOnline: Boolean = false,
    val authState: AuthState = AuthState.Unknown,
    val autostartProxy: Boolean = false,
    val pendingAwards: List<PendingAwardUi> = emptyList(),
    val cachedGames: List<CachedGame> = emptyList(),
    val cfgPatchMessage: String? = null,
    val cfgPatchSuccess: Boolean? = null,
    val needsSafGrant: Boolean = false,
    val cfgCopyBackPath: String? = null,
    val cfgIsPatched: Boolean? = null,
    val cfgHardcoreWasEnabled: Boolean = false,
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

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )

        checkCfgPatched(treeUri = loadSafUri())
        _state.value = _state.value.copy(autostartProxy = loadAutostartPref())
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
                    is FlushEvent.Completed -> _state.value = _state.value.copy(
                        flushInProgress = false,
                        flushProgress = str(R.string.flush_completed, event.flushed, event.total)
                    )
                    is FlushEvent.ChainBroken -> _state.value = _state.value.copy(
                        flushInProgress = false,
                        flushProgress = str(R.string.flush_chain_broken, event.index + 1, event.reason)
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
                        Log.d("RAProxy/Games", "patch entries=${entries.size}, unlocks keys in DB=$sessionKeys")
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
                        Log.d("RAProxy/Games", "game=$gameId user=$user unlocksBody=${unlocksBody?.take(200)} unlockedCount=$unlockedCount total=$totalAchievements")
                        CachedGame(gameId = gameId, title = title, user = user, cachedAt = entry.cachedAt, imageIconUrl = imageIconUrl, unlockedCount = unlockedCount, totalAchievements = totalAchievements)
                    }
                }
                .collect { games ->
                    _cachedGames.value = games
                    _state.value = _state.value.copy(cachedGames = games)
                }
        }
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

    fun validateToken() {
        viewModelScope.launch {
            val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
            if (credentials == null) {
                Log.i("RAProxy/Auth", "validateToken: no cached credentials found")
                _state.value = _state.value.copy(authState = AuthState.Invalid)
                return@launch
            }
            if (!_state.value.isOnline) {
                Log.i("RAProxy/Auth", "validateToken: offline — trusting cache for user=${credentials.user}")
                _state.value = _state.value.copy(authState = AuthState.Valid)
                return@launch
            }
            val gameId = withContext(Dispatchers.IO) {
                db.cacheDao().getAllByPrefix(CacheKeys.PREFIX_PATCH).firstOrNull()
                    ?.cacheKey?.split(":")?.getOrNull(1)
            }
            if (gameId == null) {
                Log.i("RAProxy/Auth", "validateToken: online but no cached games — trusting cache for user=${credentials.user}")
                _state.value = _state.value.copy(authState = AuthState.Valid)
                return@launch
            }
            val valid = withContext(Dispatchers.IO) {
                try {
                    val userAgent = loadUserAgent(db)
                    val url = "$RA_HOST/dorequest.php?r=patch&g=$gameId&u=${credentials.user}&t=${credentials.token}"
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body).optBoolean("Success", false)
                } catch (e: Exception) {
                    Log.w("RAProxy/Auth", "validateToken: live check failed — ${e.message}")
                    false
                }
            }
            Log.i("RAProxy/Auth", "validateToken: live patch check for user=${credentials.user} valid=$valid")
            _state.value = _state.value.copy(authState = if (valid) AuthState.Valid else AuthState.Invalid)
        }
    }
    fun onProxyStarted() {
        _state.value = _state.value.copy(proxyRunning = true)
    }

    fun onProxyStopped() {
        _state.value = _state.value.copy(proxyRunning = false)
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

    fun patchCfg(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { patchRetroArchCfg(app, treeUri) }
            _state.value = _state.value.copy(
                cfgPatchMessage = result.message,
                cfgPatchSuccess = result.success,
                needsSafGrant = result.needsSafGrant,
                cfgCopyBackPath = result.copyBackPath,
                cfgIsPatched = if (result.success) true else _state.value.cfgIsPatched
            )
        }
    }

    fun revertCfg(treeUri: Uri? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { revertRetroArchCfg(app, treeUri) }
            _state.value = _state.value.copy(
                cfgPatchMessage = result.message,
                cfgPatchSuccess = result.success,
                needsSafGrant = result.needsSafGrant,
                cfgCopyBackPath = result.copyBackPath,
                cfgIsPatched = if (result.success) false else _state.value.cfgIsPatched
            )
        }
    }

    fun addRom(fileUris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
            if (credentials == null) {
                _state.value = _state.value.copy(
                    scanProgress = str(R.string.scan_no_login)
                )
                return@launch
            }
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
            val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
            if (credentials == null) {
                _state.value = _state.value.copy(
                    scanProgress = str(R.string.scan_no_login)
                )
                return@launch
            }
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
        viewModelScope.launch {
            val credentials = withContext(Dispatchers.IO) { loadLoginCredentials(db) }
            if (credentials == null) {
                _state.value = _state.value.copy(
                    scanProgress = str(R.string.scan_no_login)
                )
                return@launch
            }
            val games = _state.value.cachedGames.reversed()
            _state.value = _state.value.copy(scanInProgress = true, scanProgress = str(R.string.refresh_progress, 0, games.size))
            val userAgent = withContext(Dispatchers.IO) { loadUserAgent(db) }
            withContext(Dispatchers.IO) {
                for ((index, game) in games.withIndex()) {
                    _state.value = _state.value.copy(scanProgress = str(R.string.refresh_progress_named, index + 1, games.size, game.title))
                    val gameId = game.gameId.toIntOrNull() ?: continue
                    cacheGame(gameId, credentials, userAgent, db)
                }
            }
            _state.value = _state.value.copy(
                scanInProgress = false,
                scanProgress = str(R.string.refresh_complete, games.size)
            )
        }
    }

    private suspend fun resolvePendingAward(award: com.raofflineproxy.data.PendingAward): PendingAwardUi {
        val params = (award.queryString + "&" + award.requestBody)
            .split("&")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) null else part.substring(0, eq) to java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.toMap()
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
            points = points,
            badgeUrl = badgeUrl,
            hardcore = hardcore,
            lastError = award.lastError
        )
    }

    fun setAutostartProxy(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences("ra_proxy_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("autostart_proxy", enabled).apply()
        _state.value = _state.value.copy(autostartProxy = enabled)
    }

    private fun loadAutostartPref(): Boolean =
        getApplication<Application>()
            .getSharedPreferences("ra_proxy_prefs", Context.MODE_PRIVATE)
            .getBoolean("autostart_proxy", false)

    private fun loadSafUri(): Uri? =
        getApplication<Application>()
            .getSharedPreferences("ra_proxy_prefs", Context.MODE_PRIVATE)
            .getString("saf_tree_uri", null)
            ?.let { Uri.parse(it) }
}
