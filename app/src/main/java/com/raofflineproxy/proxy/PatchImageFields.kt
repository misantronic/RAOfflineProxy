package com.raofflineproxy.proxy

import com.raofflineproxy.RA_HOST
import org.json.JSONObject

internal fun putPatchImageFields(target: JSONObject, imageUrl: String?) {
    if (imageUrl.isNullOrEmpty()) {
        return
    }

    target.put("ImageIcon", patchImagePathFromUrl(imageUrl))
    target.put("ImageIconUrl", imageUrl)
}

internal fun patchImagePath(patchData: JSONObject): String? =
    patchData.optString("ImageIcon")
        .takeIf { it.isNotEmpty() }
        ?: patchData.optString("ImageIconUrl")
            .takeIf { it.isNotEmpty() }
            ?.let(::patchImagePathFromUrl)

internal fun patchImageUrl(patchData: JSONObject): String? =
    patchData.optString("ImageIconUrl")
        .takeIf { it.isNotEmpty() }
        ?: patchImagePath(patchData)
            ?.let(::absolutePatchImageUrl)

internal fun absolutePatchImageUrl(imagePath: String): String =
    if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
        imagePath
    } else {
        "$RA_HOST$imagePath"
    }

internal fun patchImagePathFromUrl(imageUrl: String): String =
    imageUrl.substringAfter("retroachievements.org", imageUrl)
        .substringAfter("media.retroachievements.org", imageUrl)
        .takeIf { it.startsWith('/') }
        ?: imageUrl
