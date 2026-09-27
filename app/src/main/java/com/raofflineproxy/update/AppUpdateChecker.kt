package com.raofflineproxy.update

import android.util.Log
import com.raofflineproxy.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val RELEASES_URL = "https://api.github.com/repos/misantronic/RAOfflineProxy/releases"
private const val NIGHTLY_RELEASE_URL = "https://api.github.com/repos/misantronic/RAOfflineProxy-nightly/releases/tags/nightly-android"
private const val STABLE_NIGHTLY_NUMBER = -1
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

        val candidates = releases + fetchNightlyCandidates(currentVersionName)

        return selectLatestUpdate(currentVersionName, candidates)
            ?.also { Log.i(TAG, "Found newer update version=${it.versionName} apkUrl=${it.apkUrl}") }
            ?: run {
                Log.i(TAG, "No newer Android update found for currentVersion=$currentVersionName")
                null
            }
    }

    private fun fetchReleases(): List<ReleaseInfo>? = fetchBody(RELEASES_URL)?.let(::parseReleases)

    private fun fetchNightlyCandidates(currentVersionName: String): List<ReleaseInfo> {
        if (parseVersion(currentVersionName)?.isNightly != true) return emptyList()

        Log.i(TAG, "Checking nightly release $NIGHTLY_RELEASE_URL")
        return listOfNotNull(fetchBody(NIGHTLY_RELEASE_URL)?.let(::parseNightlyRelease))
    }

    private fun parseNightlyRelease(body: String): ReleaseInfo? =
        try {
            val release = JSONObject(body)
            parseRelease(release, nightlyVersionName(release))?.takeIf { it.version.isNightly }
        } catch (e: JSONException) {
            Log.w(TAG, "Nightly release response could not be parsed: ${e.message}")
            null
        }

    private fun fetchBody(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RAOfflineProxy/${BuildConfig.VERSION_NAME}")
        }

        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "GitHub request $url failed with HTTP $statusCode: ${errorBody.take(512)}")
                return null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "GitHub request $url failed: ${e.message ?: e::class.java.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseReleases(body: String): List<ReleaseInfo> {
        val releases = JSONArray(body)

        val accepted = (0 until releases.length()).mapNotNull { index ->
            releases.optJSONObject(index)?.let { parseRelease(it, it.optString("tag_name")) }
        }
        Log.d(TAG, "Parsed releases: total=${releases.length()} accepted=${accepted.size}")
        return accepted
    }

    internal fun nightlyVersionName(release: JSONObject): String =
        release.optString("name").trim().substringAfterLast(' ')

    private fun parseRelease(release: JSONObject, rawVersionName: String): ReleaseInfo? {
        if (release.optBoolean("draft")) return null

        val versionName = rawVersionName
            .trim()
            .removePrefix("v")
            .takeIf { it.isNotBlank() }
            ?: return null
        val version = parseVersion(versionName) ?: return null
        val releaseUrl = release.optString("html_url").takeIf { it.isNotBlank() } ?: return null
        val apkUrl = release.optJSONArray("assets")?.let(::findApkUrl) ?: return null

        return ReleaseInfo(
            versionName = versionName,
            version = version,
            apkUrl = apkUrl,
            releaseUrl = releaseUrl
        )
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
            .filter { currentVersion.isNightly || !it.version.isNightly }
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
    val channelNumber: Int,
    val nightlyNumber: Int = STABLE_NIGHTLY_NUMBER
) : Comparable<AppVersion> {
    val isNightly: Boolean
        get() = nightlyNumber != STABLE_NIGHTLY_NUMBER

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(
            this,
            other,
            AppVersion::major,
            AppVersion::minor,
            AppVersion::patch,
            AppVersion::channelRank,
            AppVersion::channelNumber,
            AppVersion::nightlyNumber
        )
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
        },
        nightlyNumber = match.groupValues[6]
            .takeIf { it.isNotEmpty() }
            ?.let { it.toIntOrNull() ?: return null }
            ?: STABLE_NIGHTLY_NUMBER
    )
}

private val VERSION_REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta)(\\d+))?(?:-nightly\\.(\\d+))?$")
