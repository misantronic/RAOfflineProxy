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
    rawPatchImageValue(patchData)?.let(::patchImagePathFromUrl)

internal fun patchImageUrl(patchData: JSONObject): String? =
    patchData.optString("ImageIconUrl")
        .takeIf { it.isNotEmpty() }
        ?: rawPatchImageValue(patchData)
            ?.let(::absolutePatchImageUrl)

private fun rawPatchImageValue(patchData: JSONObject): String? =
    patchData.optString("ImageIcon")
        .takeIf { it.isNotEmpty() }
        ?: patchData.optString("ImageIconUrl")
            .takeIf { it.isNotEmpty() }

internal fun absolutePatchImageUrl(imagePath: String): String =
    if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
        imagePath
    } else {
        "$RA_HOST$imagePath"
    }

internal fun patchImagePathFromUrl(imageUrl: String): String =
    extractImagePath(imageUrl) ?: imageUrl
