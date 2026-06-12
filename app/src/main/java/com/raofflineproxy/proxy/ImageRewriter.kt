package com.raofflineproxy.proxy

import org.json.JSONArray
import org.json.JSONObject

internal fun extractImagePath(url: String): String? {
    if (url.isBlank()) return null
    if (url.startsWith('/')) return url.substringBefore('?')
    // Both retroachievements.org and media.retroachievements.org share the same suffix,
    // so one substringAfter covers both.
    val afterHost = url.substringAfter("retroachievements.org", "").substringBefore('?')
    return afterHost.takeIf { it.startsWith('/') }
}

internal fun rewriteImageUrls(
    action: String?,
    body: String,
    proxyBaseUrl: String,
    onImageUrl: (originalUrl: String, path: String) -> Unit = { _, _ -> },
): String = runCatching {
    val json = JSONObject(body)
    when (action) {
        "patch" -> rewritePatchImageUrls(json, proxyBaseUrl, onImageUrl)
        "achievementsets" -> rewriteAchievementSetsImageUrls(json, proxyBaseUrl, onImageUrl)
        "login2" -> rewriteLogin2ImageUrls(json, proxyBaseUrl, onImageUrl)
        else -> return body
    }
    json.toString()
}.getOrDefault(body)

private fun rewritePatchImageUrls(
    json: JSONObject,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    val patchData = json.optJSONObject("PatchData") ?: return
    rewriteIconFields(patchData, proxyBaseUrl, onImageUrl)
    rewriteAchievementBadgeFields(patchData.optJSONArray("Achievements"), proxyBaseUrl, onImageUrl)
}

private fun rewriteAchievementSetsImageUrls(
    json: JSONObject,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    rewriteIconFields(json, proxyBaseUrl, onImageUrl)
    val sets = json.optJSONArray("Sets") ?: return
    for (i in 0 until sets.length()) {
        val set = sets.optJSONObject(i) ?: continue
        rewriteIconFields(set, proxyBaseUrl, onImageUrl)
        rewriteAchievementBadgeFields(set.optJSONArray("Achievements"), proxyBaseUrl, onImageUrl)
    }
}

private fun rewriteLogin2ImageUrls(
    json: JSONObject,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    rewriteUrlField(json, "AvatarUrl", proxyBaseUrl, onImageUrl)
}

private fun rewriteIconFields(
    obj: JSONObject,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    val sourceUrl = obj.optString("ImageIconUrl").takeIf { it.isNotEmpty() }
        ?: obj.optString("ImageIcon").takeIf { it.isNotEmpty() }
        ?: return
    val path = extractImagePath(sourceUrl) ?: return
    onImageUrl(sourceUrl, path)
    val proxyUrl = "$proxyBaseUrl$path"
    if (obj.has("ImageIconUrl")) obj.put("ImageIconUrl", proxyUrl)
    if (obj.has("ImageIcon")) obj.put("ImageIcon", proxyUrl)
}

private fun rewriteAchievementBadgeFields(
    achievements: JSONArray?,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    if (achievements == null) return
    for (i in 0 until achievements.length()) {
        val achievement = achievements.optJSONObject(i) ?: continue
        rewriteUrlField(achievement, "BadgeURL", proxyBaseUrl, onImageUrl)
        rewriteUrlField(achievement, "BadgeLockedURL", proxyBaseUrl, onImageUrl)
    }
}

private fun rewriteUrlField(
    obj: JSONObject,
    key: String,
    proxyBaseUrl: String,
    onImageUrl: (String, String) -> Unit,
) {
    val url = obj.optString(key).takeIf { it.isNotEmpty() } ?: return
    val path = extractImagePath(url) ?: return
    onImageUrl(url, path)
    obj.put(key, "$proxyBaseUrl$path")
}
