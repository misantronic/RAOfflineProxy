package com.raofflineproxy.proxy

import android.content.Context
import java.io.File

private const val AWARD_IMAGES_DIR = "award_images"

private fun awardImagesRoot(context: Context) = File(context.filesDir, AWARD_IMAGES_DIR)
private fun awardDir(context: Context, achievementId: Int) = File(awardImagesRoot(context), achievementId.toString())

fun persistAwardGameIcon(context: Context, achievementId: Int, source: File): String? =
    copyToAwardDir(source, File(awardDir(context, achievementId), "icon"))

fun persistAwardBadge(context: Context, achievementId: Int, source: File): String? =
    copyToAwardDir(source, File(awardDir(context, achievementId), "badge"))

fun deleteAwardImages(context: Context, achievementId: Int) {
    awardDir(context, achievementId).deleteRecursively()
}

fun clearAllAwardImages(context: Context) {
    awardImagesRoot(context).deleteRecursively()
}

private fun copyToAwardDir(source: File, dest: File): String? {
    if (!source.isFile) return null
    return runCatching {
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()
}
