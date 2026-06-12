package com.raofflineproxy.proxy

import android.content.Context
import android.util.Log
import com.raofflineproxy.sharedHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors

private const val IMAGE_CACHE_DIR = "image_cache"
private const val STATIC_DIR = "static"
private const val GAMES_DIR = "games"
private const val TAG = "RAProxy/ImageCache"
private const val IMAGE_DOWNLOAD_POOL_SIZE = 4

private val imageDownloadExecutor = Executors.newFixedThreadPool(IMAGE_DOWNLOAD_POOL_SIZE)

fun scheduleImageDownload(
    context: Context,
    url: String,
    imagePath: String,
    userAgent: String,
    gameId: Int? = null,
) {
    imageDownloadExecutor.submit {
        downloadStaticImage(context, url, imagePath, userAgent, gameId)
    }
}

private fun imageCacheRoot(context: Context): File = File(context.filesDir, IMAGE_CACHE_DIR)
private fun staticDir(context: Context): File = File(imageCacheRoot(context), STATIC_DIR)
private fun gameImageDir(context: Context, gameId: Int): File =
    File(imageCacheRoot(context), "$GAMES_DIR/$gameId")

fun downloadStaticImage(
    context: Context,
    url: String,
    imagePath: String,
    userAgent: String,
    gameId: Int? = null,
) {
    runCatching {
        val cleanPath = imagePath.trimStart('/').substringBefore('?')
        val target = File(staticDir(context), cleanPath)

        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            val temp = File(target.parent, "${target.name}.tmp")
            var renamed = false
            try {
                sharedHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                renamed = temp.renameTo(target)
            } finally {
                if (!renamed) temp.delete()
            }
        }

        if (gameId != null && imagePath.startsWith("/Images/", ignoreCase = true) && target.exists()) {
            val iconFile = File(gameImageDir(context, gameId), "icon.png")
            if (!iconFile.exists()) {
                iconFile.parentFile?.mkdirs()
                target.copyTo(iconFile, overwrite = false)
            }
        }
    }.onFailure { e ->
        Log.d(TAG, "Failed to cache image path=$imagePath: ${e.message}")
    }
}

fun resolveCachedStaticAsset(context: Context, path: String): File? {
    val cleanPath = path.trimStart('/').substringBefore('?')
    return File(staticDir(context), cleanPath).takeIf { it.isFile }
}

fun resolveCachedGameIconPath(context: Context, gameId: Int): String? =
    gameImageDir(context, gameId)
        .listFiles()
        ?.firstOrNull { it.isFile }
        ?.absolutePath

fun deleteCachedImagesForGame(context: Context, gameId: String) {
    gameId.toIntOrNull()?.let { gameImageDir(context, it).deleteRecursively() }
}

fun clearAllCachedImages(context: Context) {
    imageCacheRoot(context).deleteRecursively()
}
