package com.raofflineproxy.ui

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

internal fun initialTreeUriForPath(path: String): Uri? {
    val normalized = path.replace('\\', '/').trim().trimEnd('/')
    if (normalized.isEmpty()) return null

    val canonical = runCatching { File(path).canonicalPath.replace('\\', '/').trim().trimEnd('/') }
        .getOrNull()
    val normalizedCandidates = buildList {
        add(normalized)
        if (!canonical.isNullOrEmpty() && !canonical.equals(normalized, ignoreCase = true)) {
            add(canonical)
        }
    }

    val externalStorageRoot = Environment.getExternalStorageDirectory().path
        .replace('\\', '/')
        .trim()
        .trimEnd('/')

    val primaryPrefixes = listOf(
        "/storage/emulated/0",
        "/storage/self/primary",
        externalStorageRoot,
        "storage/emulated/0",
        "storage/self/primary",
        externalStorageRoot.trimStart('/')
    )

    val primaryRelative = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        primaryPrefixes.firstNotNullOfOrNull { prefix ->
            when {
                candidate.equals(prefix, ignoreCase = true) -> ""
                candidate.startsWith("$prefix/", ignoreCase = true) -> candidate.substring(prefix.length).trimStart('/')
                else -> null
            }
        }
    }

    if (primaryRelative != null) {
        val docId = if (primaryRelative.isEmpty()) "primary:" else "primary:$primaryRelative"
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, docId)
    }

    val storagePrefix = "/storage/"
    for (candidate in normalizedCandidates) {
        if (candidate.startsWith(storagePrefix, ignoreCase = true)) {
            val afterStorage = candidate.substring(storagePrefix.length)
            val volume = afterStorage.substringBefore('/').takeIf { it.isNotEmpty() }
            if (volume != null) {
                val relative = afterStorage.substringAfter('/', "")
                val docId = if (relative.isEmpty()) "$volume:" else "$volume:$relative"
                return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, docId)
            }
        }
    }

    val fallbackRelative = normalizedCandidates.first().trimStart('/')
    val fallbackDocId = if (fallbackRelative.isEmpty()) "primary:" else "primary:$fallbackRelative"
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, fallbackDocId)
}
