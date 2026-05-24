package com.raofflineproxy.ui

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

internal fun writeSafTextFile(
    context: Context,
    directory: DocumentFile,
    target: DocumentFile,
    content: String
) {
    val bytes = content.toByteArray()
    val writeModes = listOf("wt", "rwt")
    var lastError: Throwable? = null

    for (mode in writeModes) {
        try {
            val output = context.contentResolver.openOutputStream(target.uri, mode)
                ?: throw IOException("Could not open ${target.name ?: "document"} for writing")
            output.use { it.write(bytes) }
            return
        } catch (error: Throwable) {
            lastError = error
        }
    }

    replaceSafTextFile(context, directory, target, bytes, lastError)
}

private fun replaceSafTextFile(
    context: Context,
    directory: DocumentFile,
    target: DocumentFile,
    bytes: ByteArray,
    lastError: Throwable?
) {
    val targetName = target.name ?: throw IOException("Could not resolve document name", lastError)
    val tempName = "$targetName.raofflineproxy.tmp"
    directory.findFile(tempName)?.delete()

    val tempFile = directory.createFile("application/octet-stream", tempName)
        ?: throw IOException("Could not create temporary replacement for $targetName", lastError)

    try {
        val tempOutput = context.contentResolver.openOutputStream(tempFile.uri, "wt")
            ?: throw IOException("Could not open temporary replacement for $targetName", lastError)
        tempOutput.use { it.write(bytes) }

        if (!target.delete()) {
            throw IOException("Could not delete $targetName before replacement", lastError)
        }

        if (!tempFile.renameTo(targetName)) {
            throw IOException("Could not rename temporary replacement for $targetName", lastError)
        }
    } catch (error: Throwable) {
        runCatching { tempFile.delete() }
        throw if (error is IOException) error else IOException(error.message ?: "Could not replace $targetName", lastError)
    }
}
