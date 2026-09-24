package com.raofflineproxy.data

object CacheKeys {
    const val USER_AGENT = "ua::last"

    const val PREFIX_LOGIN = "login2::"
    const val PREFIX_PATCH = "patch:"
    const val PREFIX_ACHIEVEMENTSETS = "achievementsets:"
    const val PREFIX_UNLOCKS = "unlocks:"
    const val PREFIX_STARTSESSION = "startsession:"
    const val PREFIX_GAMEID = "gameid:"
    const val PREFIX_LAST_PLAYED = "lastplayed:"
    const val PREFIX_CACHE_QUEUE = "cachequeue:"
    const val CACHE_BUDGET = "cachebudget"

    fun login(user: String) = "$PREFIX_LOGIN$user"
    fun gameId(hash: String) = "$PREFIX_GAMEID$hash"
    fun patch(gameId: Int, user: String) = "$PREFIX_PATCH$gameId:${user.lowercase()}"
    fun patchPrefix(gameId: String) = "$PREFIX_PATCH$gameId:"
    fun unlocksPrefix(gameId: String) = "$PREFIX_UNLOCKS$gameId:"
    fun startSessionPrefix(gameId: String) = "$PREFIX_STARTSESSION$gameId:"
    fun achievementSets(hash: String, user: String) = "$PREFIX_ACHIEVEMENTSETS$hash:${user.lowercase()}"
    fun unlocks(gameId: Int, user: String) = "$PREFIX_UNLOCKS$gameId:${user.lowercase()}:0"
    fun unlocks(gameId: String, user: String) = "$PREFIX_UNLOCKS$gameId:${user.lowercase()}:0"
    fun startSession(gameId: Int, user: String) = "$PREFIX_STARTSESSION$gameId:${user.lowercase()}:0"
    fun startSession(gameId: String, user: String) = "$PREFIX_STARTSESSION$gameId:${user.lowercase()}:0"
    fun lastPlayed(gameId: Int) = "$PREFIX_LAST_PLAYED$gameId"
    fun cacheQueue(hash: String) = "$PREFIX_CACHE_QUEUE${hash.lowercase()}"

    fun parseGameIdFromPatchKey(cacheKey: String): Int? =
        cacheKey.removePrefix(PREFIX_PATCH).split(":").firstOrNull()?.toIntOrNull()

    fun parseGameIdStringFromPatchKey(cacheKey: String): String? =
        cacheKey.removePrefix(PREFIX_PATCH).split(":").firstOrNull()?.takeIf { it.isNotEmpty() }

    fun parseUserFromPatchKey(cacheKey: String): String? =
        cacheKey.removePrefix(PREFIX_PATCH).split(":").getOrNull(1)?.takeIf { it.isNotEmpty() }

    fun parseAchievementSetsHash(cacheKey: String): String? =
        cacheKey.removePrefix(PREFIX_ACHIEVEMENTSETS).substringBeforeLast(":", "")
            .takeIf { it.isNotEmpty() }

    fun parseGameIdFromLastPlayedKey(cacheKey: String): Int? =
        cacheKey.removePrefix(PREFIX_LAST_PLAYED).toIntOrNull()

    fun parseUserFromAchievementSetsKey(cacheKey: String): String? =
        cacheKey.removePrefix(PREFIX_ACHIEVEMENTSETS).substringAfterLast(':', "")
            .takeIf { it.isNotEmpty() }
}
