package com.raofflineproxy.data

object CacheKeys {
    const val USER_AGENT = "ua::last"

    const val PREFIX_LOGIN = "login2::"
    const val PREFIX_PATCH = "patch:"
    const val PREFIX_UNLOCKS = "unlocks:"
    const val PREFIX_STARTSESSION = "startsession:"
    const val PREFIX_GAMEID = "gameid:"

    fun login(user: String) = "login2::$user"
    fun patch(gameId: Int, user: String) = "patch:$gameId:$user"
    fun patchPrefix(gameId: String) = "patch:$gameId:"
    fun unlocks(gameId: Int, user: String) = "unlocks:$gameId:$user:0"
    fun unlocks(gameId: String, user: String) = "unlocks:$gameId:$user:0"
    fun startSession(gameId: Int, user: String) = "startsession:$gameId:$user:0"

    fun parseGameIdFromPatchKey(cacheKey: String): Int? =
        cacheKey.removePrefix(PREFIX_PATCH).split(":").firstOrNull()?.toIntOrNull()

    fun parseGameIdStringFromPatchKey(cacheKey: String): String? =
        cacheKey.removePrefix(PREFIX_PATCH).split(":").firstOrNull()?.takeIf { it.isNotEmpty() }
}
