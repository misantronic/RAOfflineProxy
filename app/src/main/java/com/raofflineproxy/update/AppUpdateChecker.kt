package com.raofflineproxy.update

import android.util.Log
import com.raofflineproxy.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val RELEASES_URL = "https://api.github.com/repos/misantronic/RAOfflineProxy/releases"
private const val TAG = "RAProxy/AppUpdateChecker"

data class AppUpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val releaseUrl: String
)

internal object AppUpdateChecker {
    fun fetchLatestUpdate(currentVersionName: String = BuildConfig.VERSION_NAME): AppUpdateInfo? {
        Log.i(TAG, "Checking for updates from $RELEASES_URL using currentVersion=$currentVersionName")
        val releases = fetchReleases() ?: return null
            .also { Log.w(TAG, "Update check failed; could not fetch or parse releases") }

        Log.i(TAG, "Fetched ${releases.size} Android release candidates")

        return selectLatestUpdate(currentVersionName, releases)
            ?.also { Log.i(TAG, "Found newer update version=${it.versionName} apkUrl=${it.apkUrl}") }
            ?: run {
                Log.i(TAG, "No newer Android update found for currentVersion=$currentVersionName")
                null
            }
    }

    private fun fetchReleases(): List<ReleaseInfo>? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RAOfflineProxy/${BuildConfig.VERSION_NAME}")
        }

        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "GitHub releases request failed with HTTP $statusCode: ${errorBody.take(512)}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseReleases(body)
        } catch (e: IOException) {
            Log.w(TAG, "GitHub releases request failed: ${e.message ?: e::class.java.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleases(body: String): List<ReleaseInfo> {
        val releases = JSONArray(body)

        val accepted = buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft")) continue

                val versionName = release.optString("tag_name")
                    .trim()
                    .removePrefix("v")
                    .takeIf { it.isNotBlank() }
                    ?: continue
                val version = parseVersion(versionName) ?: continue
                val releaseUrl = release.optString("html_url").takeIf { it.isNotBlank() } ?: continue
                val apkUrl = release.optJSONArray("assets")?.let(::findApkUrl) ?: continue

                add(
                    ReleaseInfo(
                        versionName = versionName,
                        version = version,
                        apkUrl = apkUrl,
                        releaseUrl = releaseUrl
                    )
                )
            }
        }
        Log.d(TAG, "Parsed releases: total=${releases.length()} accepted=${accepted.size}")
        return accepted
    }

    private fun findApkUrl(assets: JSONArray): String? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val assetName = asset.optString("name")
            val contentType = asset.optString("content_type")
            if (!assetName.endsWith(".apk", ignoreCase = true) &&
                contentType != "application/vnd.android.package-archive"
            ) {
                continue
            }

            val downloadUrl = asset.optString("browser_download_url")
            if (downloadUrl.isNotBlank()) {
                return downloadUrl
            }
        }

        return null
    }

    internal fun selectLatestUpdate(
        currentVersionName: String,
        releases: List<ReleaseInfo>
    ): AppUpdateInfo? {
        val currentVersion = parseVersion(currentVersionName) ?: return null

        return releases
            .filter { it.version > currentVersion }
            .maxByOrNull { it.version }
            ?.let {
                AppUpdateInfo(
                    versionName = it.versionName,
                    apkUrl = it.apkUrl,
                    releaseUrl = it.releaseUrl
                )
            }
    }

    internal fun isUpdateNewerThanCurrent(
        currentVersionName: String,
        updateVersionName: String
    ): Boolean {
        val currentVersion = parseVersion(currentVersionName) ?: return false
        val updateVersion = parseVersion(updateVersionName) ?: return false

        return updateVersion > currentVersion
    }
}

internal data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val channelRank: Int,
    val channelNumber: Int
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch, AppVersion::channelRank, AppVersion::channelNumber)
}

internal data class ReleaseInfo(
    val versionName: String,
    val version: AppVersion,
    val apkUrl: String,
    val releaseUrl: String
)

internal fun releaseInfo(
    versionName: String,
    apkUrl: String = "https://example.com/$versionName.apk",
    releaseUrl: String = "https://example.com/releases/$versionName"
): ReleaseInfo? = parseVersion(versionName)?.let { version ->
    ReleaseInfo(
        versionName = versionName,
        version = version,
        apkUrl = apkUrl,
        releaseUrl = releaseUrl
    )
}

private fun parseVersion(raw: String): AppVersion? {
    val normalized = raw.trim().removePrefix("v")
    val match = VERSION_REGEX.matchEntire(normalized) ?: return null
    val channel = match.groupValues[4]

    return AppVersion(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: return null,
        channelRank = when (channel) {
            "alpha" -> 0
            "beta" -> 1
            else -> 2
        },
        channelNumber = if (channel.isEmpty()) {
            Int.MAX_VALUE
        } else {
            match.groupValues[5].toIntOrNull() ?: return null
        }
    )
}

private val VERSION_REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta)(\\d+))?$")
