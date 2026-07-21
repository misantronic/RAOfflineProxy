package com.raofflineproxy.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

private const val TAG = "RAProxy/ApkDownloader"
private const val APK_DOWNLOADS_DIR = "apk_downloads"
private const val DOWNLOAD_FILENAME = "RAOfflineProxy-update.apk"

internal object ApkDownloader {
    suspend fun download(
        context: Context,
        url: String,
        onProgress: suspend (percent: Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, APK_DOWNLOADS_DIR).also { it.mkdirs() }
        val dest = File(dir, DOWNLOAD_FILENAME)

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")

                val body = response.body ?: throw IOException("Download failed: empty body")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastReportedPercent = -1

                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "APK downloaded to ${dest.path}")
        return dest
    }
}
