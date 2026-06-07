package com.raofflineproxy.proxy

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * The storage-relative path of a SAF document, e.g. "ROMs/psx/game.cue".
 * Returns null when the URI is not a "volume:relative/path" document id.
 */
internal fun resolveDocumentRelativePath(file: DocumentFile): String? {
    val uriPath = file.uri.path ?: return null
    val documentId = uriPath.substringAfterLast("/document/", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?: return null
    return Uri.decode(documentId).substringAfter(':', missingDelimiterValue = "")
        .trim('/')
        .takeIf { it.isNotBlank() }
}

/**
 * All mounted storage roots: internal storage plus any SD cards / USB volumes.
 * Used together with [resolveDocumentRelativePath] to locate a document on disk
 * without assuming it lives on primary storage.
 */
internal fun storageRoots(): List<File> {
    val roots = mutableListOf(File("/storage/emulated/0"))
    File("/storage").listFiles()?.forEach { entry ->
        if (entry.isDirectory && entry.name != "emulated" && entry.name != "self") {
            roots.add(entry)
        }
    }
    return roots.distinctBy { it.path }
}

internal fun resolveDocumentAbsolutePath(file: DocumentFile): String? {
    val relativePath = resolveDocumentRelativePath(file) ?: return null
    val match = storageRoots().map { File(it, relativePath) }.firstOrNull { it.exists() }
    return (match ?: File("/storage/emulated/0", relativePath)).path
}
