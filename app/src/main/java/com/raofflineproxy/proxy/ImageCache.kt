package com.raofflineproxy.proxy

import android.content.Context
import android.util.Log
import com.raofflineproxy.sharedHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val IMAGE_CACHE_DIR = "image_cache"
private const val STATIC_DIR = "static"
private const val GAMES_DIR = "games"
private const val TAG = "RAProxy/ImageCache"
private const val IMAGE_DOWNLOAD_POOL_SIZE = 4

private val imageDownloadExecutor = Executors.newFixedThreadPool(IMAGE_DOWNLOAD_POOL_SIZE)

private val inFlightDownloads = ConcurrentHashMap.newKeySet<String>()

fun scheduleImageDownload(
    context: Context,
    url: String,
    imagePath: String,
    userAgent: String,
    gameId: Int? = null,
) {
    val dedupeKey = "$gameId|$imagePath"
    if (!inFlightDownloads.add(dedupeKey)) return
    runCatching {
        imageDownloadExecutor.submit {
            try {
                downloadStaticImage(context, url, imagePath, userAgent, gameId)
            } finally {
                inFlightDownloads.remove(dedupeKey)
            }
        }
    }.onFailure { inFlightDownloads.remove(dedupeKey) }
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

        if (target.length() == 0L) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            writeAtomically(target) { temp ->
                sharedHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        if (gameId != null && imagePath.startsWith("/Images/", ignoreCase = true) && target.length() > 0L) {
            val iconFile = File(gameImageDir(context, gameId), "icon.png")
            if (iconFile.length() == 0L) {
                writeAtomically(iconFile) { temp -> target.copyTo(temp, overwrite = true) }
            }
        }
    }.onFailure { e ->
        Log.d(TAG, "Failed to cache image path=$imagePath: ${e.message}")
    }
}

fun resolveCachedStaticAsset(context: Context, path: String): File? {
    val cleanPath = path.trimStart('/').substringBefore('?')
    return File(staticDir(context), cleanPath).takeIf { it.isFile && it.length() > 0L }
}

private fun writeAtomically(target: File, write: (File) -> Unit) {
    target.parentFile?.mkdirs()
    val temp = File.createTempFile("tmp_", ".part", target.parentFile)
    var renamed = false
    try {
        write(temp)
        renamed = temp.renameTo(target)
    } finally {
        if (!renamed) temp.delete()
    }
}

fun cachedBadgeFileNames(context: Context): Set<String> =
    File(staticDir(context), "Badge")
        .listFiles()
        ?.mapNotNullTo(mutableSetOf()) { file -> file.name.takeIf { file.isFile && file.length() > 0L } }
        .orEmpty()

fun cachedBadgePath(context: Context, badgeName: String): String =
    File(staticDir(context), "Badge/$badgeName.png").absolutePath

fun resolveCachedGameIconPath(context: Context, gameId: Int): String? =
    gameImageDir(context, gameId)
        .listFiles()
        ?.firstOrNull { it.isFile && it.length() > 0L }
        ?.absolutePath

fun deleteCachedImagesForGame(context: Context, gameId: String) {
    gameId.toIntOrNull()?.let { gameImageDir(context, it).deleteRecursively() }
}

fun clearAllCachedImages(context: Context) {
    imageCacheRoot(context).deleteRecursively()
}
