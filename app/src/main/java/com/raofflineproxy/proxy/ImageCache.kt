package com.raofflineproxy.proxy

import android.content.Context
import android.util.Log
import com.raofflineproxy.RA_HOST
import com.raofflineproxy.sharedHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val IMAGE_CACHE_DIR = "image_cache"
private const val GAMES_DIR = "games"
private const val STATIC_DIR = "static"
private const val TAG = "RAProxy/ImageCache"

private fun imageCacheRoot(context: Context): File =
    File(context.filesDir, IMAGE_CACHE_DIR)

private fun gameImageDir(context: Context, gameId: Int): File =
    File(imageCacheRoot(context), "$GAMES_DIR/$gameId")

private fun staticAssetFile(context: Context, imagePath: String): File =
    File(imageCacheRoot(context), "$STATIC_DIR/${imagePath.trimStart('/').substringBefore('?')}")

private fun originalImageFile(context: Context, gameId: Int, imagePath: String): File =
    File(gameImageDir(context, gameId), imagePath.substringAfterLast('/').substringBefore('?'))

private fun legacyGameIconFile(context: Context, gameId: Int, imagePath: String): File {
    val extension = imagePath.substringAfterLast('.', "png").substringBefore('?')
    return File(gameImageDir(context, gameId), "icon.$extension")
}

private fun fetchFile(url: String, userAgent: String, target: File) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .build()

    sharedHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val body = response.body
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            body.byteStream().use { input -> input.copyTo(output) }
        }
    }
}

private fun mirrorFile(source: File, target: File) {
    if (target.exists()) return
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
}

private fun trimStaleImages(context: Context, gameId: Int, keepNames: Set<String>) {
    val dir = gameImageDir(context, gameId)
    if (!dir.exists()) return
    dir.listFiles()?.forEach { file ->
        if (file.name !in keepNames) {
            file.delete()
        }
    }
}

fun resolveCachedGameIconPath(context: Context, gameId: Int): String? =
    gameImageDir(context, gameId)
        .listFiles()
        ?.firstOrNull { (it.name.startsWith("icon.") || !it.name.startsWith("badge_")) && it.isFile }
        ?.absolutePath

fun resolveCachedStaticAsset(context: Context, path: String): File? {
    val cleanPath = path.substringBefore('?')
    val asset = staticAssetFile(context, cleanPath)
    return asset.takeIf { it.isFile }
}

fun deleteCachedImagesForGame(context: Context, gameId: String) {
    gameId.toIntOrNull()?.let { gameImageDir(context, it).deleteRecursively() }
}

fun clearAllCachedImages(context: Context) {
    imageCacheRoot(context).deleteRecursively()
}

fun cachePatchImages(context: Context, gameId: Int, userAgent: String, patchResponseBody: String) {
    runCatching {
        val patchData = JSONObject(patchResponseBody).optJSONObject("PatchData") ?: return
        val keepNames = mutableSetOf<String>()

        val imagePath = patchImagePath(patchData)
        val imageUrl = patchImageUrl(patchData)
        if (imagePath != null) {
            val target = originalImageFile(context, gameId, imagePath)
            val legacyTarget = legacyGameIconFile(context, gameId, imagePath)
            val staticTarget = staticAssetFile(context, imagePath)
            val fetchUrl = imageUrl ?: absolutePatchImageUrl(imagePath)
            keepNames += target.name
            keepNames += legacyTarget.name
            Log.d(TAG, "Caching game icon for gameId=$gameId path=$imagePath url=$fetchUrl target=${target.absolutePath}")
            if (!target.exists()) {
                fetchFile(fetchUrl, userAgent, target)
                Log.d(TAG, "Cached game icon for gameId=$gameId file=${target.absolutePath}")
            } else {
                Log.d(TAG, "Game icon already cached for gameId=$gameId file=${target.absolutePath}")
            }
            mirrorFile(target, staticTarget)
        }

        val achievements = patchData.optJSONArray("Achievements")
        if (achievements != null) {
            for (index in 0 until achievements.length()) {
                val achievement = achievements.optJSONObject(index) ?: continue
                val badgeName = achievement.optString("BadgeName").takeIf { it.isNotEmpty() } ?: continue
                val badgeTarget = File(gameImageDir(context, gameId), "badge_$badgeName.png")
                keepNames += badgeTarget.name
                if (!badgeTarget.exists()) {
                    fetchFile("$RA_HOST/Badge/$badgeName.png", userAgent, badgeTarget)
                }
                mirrorFile(badgeTarget, staticAssetFile(context, "/Badge/$badgeName.png"))
            }
        }

        trimStaleImages(context, gameId, keepNames)
    }.onFailure { e ->
        Log.w(TAG, "Failed to cache patch images for gameId=$gameId: ${e.message}")
    }
}
