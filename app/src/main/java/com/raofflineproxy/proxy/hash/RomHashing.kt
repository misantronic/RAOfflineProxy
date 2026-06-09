package com.raofflineproxy.proxy.hash

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.proxy.resolveDocumentAbsolutePath
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val TAG = "RAProxy/Hash"

/**
 * ROM identification now delegates to the unified rcheevos rc_hash hasher
 * ([RcHashNativeBridge] / libraproxy_rchash). One native call covers every
 * supported format — cartridge, disc (incl. CHD via the bundled libchdr
 * reader), `.cue`, `.m3u` — so the per-format Kotlin strategies are gone.
 *
 * The native library hashes a file by path, so callers that only have a SAF
 * content stream (no real filesystem path) copy the bytes to a temp file
 * first. Zipped console ROMs are extracted here before hashing, because
 * rc_hash's own `.zip` path is for arcade/MAME images, not zipped cartridges.
 *
 * GameCube/Wii *container* formats (`.rvz`/`.ciso`/`.gcz`/`.wbfs`, and raw
 * `.gcm`) are the one case rc_hash can't handle alone: it reads the raw disc
 * layout, so we decompress on the fly via the [RomDataSource] readers and feed
 * the bytes to rc_hash through [RcHashNativeBridge.hashDiscDataSource]. Plain
 * `.iso` discs go straight through rc_hash by path.
 *
 * [RomHashInput] / [RomDataSource] are retained as a thin compatibility layer
 * for existing callers (e.g. SmartCache); the result is the first hash
 * candidate. Use the `*Candidates` variants to get the full ordered list.
 */

private val supportedArchiveRomExtensions = setOf(
    "a78", "bin", "cart", "ciso", "fds", "fig", "gba", "gb", "gbc", "gcm",
    "gcz", "iso", "lnx", "n64", "nds", "nes", "pbp", "pce", "sfc", "sgx",
    "smc", "swc", "v64", "wad", "wbfs", "z64"
)

internal data class RomHashInput(
    val fileName: String,
    val fileSize: Long,
    val openStream: () -> InputStream?,
    val openDataSource: (() -> RomDataSource?)? = null,
    val openPspChdDataSource: (() -> RomDataSource?)? = null,
    val openPsxChdDataSource: (() -> RomDataSource?)? = null,
    /** Real filesystem path, when known, so we can hash without copying. */
    val sourcePath: String? = null,
)

internal interface RomDataSource : Closeable {
    val length: Long

    fun read(offset: Long, buffer: ByteArray, length: Int = buffer.size): Int
}

internal fun parseCueDataBinFileName(content: String): String? {
    var currentFile: String? = null
    for (line in content.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("FILE ", ignoreCase = true)) {
            val parts = trimmed.split('"')
            if (parts.size >= 3) currentFile = parts[1]
        } else if (trimmed.startsWith("TRACK ", ignoreCase = true)) {
            if (!trimmed.contains("AUDIO", ignoreCase = true) && currentFile != null) {
                return currentFile
            }
        }
    }
    return null
}

internal fun parseM3uFirstEntry(content: String): String? =
    content.lines()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

internal fun hasExtension(fileName: String, vararg extensions: String): Boolean =
    extensions.any { extension -> fileName.endsWith(".$extension", ignoreCase = true) }

// ---- GameCube/Wii container formats (rc_hash can't decompress these) ----

private fun isNintendoDiscContainer(fileName: String): Boolean =
    hasExtension(fileName, "rvz", "ciso", "gcz", "wbfs", "gcm")

/** Wraps [openBase] in the right decompressing reader for [fileName]'s container. */
private fun openDiscDataSource(fileName: String, openBase: () -> RomDataSource?): RomDataSource? = when {
    hasExtension(fileName, "rvz") -> RvzRomDataSource.open(openBase)
    hasExtension(fileName, "ciso") -> CisoRomDataSource.open(openBase)
    hasExtension(fileName, "gcz") -> GczRomDataSource.open(openBase)
    hasExtension(fileName, "wbfs") -> WbfsRomDataSource.open(openBase)
    hasExtension(fileName, "gcm") -> openBase() // already a raw GameCube image
    else -> null
}

private fun hashDiscCandidates(fileName: String, openBase: () -> RomDataSource?): List<String> {
    if (!RcHashNativeBridge.isAvailable()) {
        logWarn(TAG, "Native hasher unavailable; cannot hash $fileName")
        return emptyList()
    }
    val dataSource = runCatching { openDiscDataSource(fileName, openBase) }.getOrNull()
    if (dataSource == null) {
        logWarn(TAG, "Could not open disc data source for $fileName")
        return emptyList()
    }
    val candidates = dataSource.use { RcHashNativeBridge.hashDiscDataSource(it) }
    if (candidates.isEmpty()) {
        logWarn(TAG, "No hash candidates for $fileName (disc)")
    } else {
        logInfo(TAG, "Hashed $fileName (disc) -> $candidates")
    }
    return candidates
}

/** Hashes a real filesystem path with the native hasher. */
private fun hashCandidatesForPath(path: String): List<String> {
    if (!RcHashNativeBridge.isAvailable()) {
        logWarn(TAG, "Native hasher unavailable; cannot hash $path")
        return emptyList()
    }
    val candidates = RcHashNativeBridge.hashFile(path)
    if (candidates.isEmpty()) {
        logWarn(TAG, "No hash candidates for $path")
    } else {
        logInfo(TAG, "Hashed $path -> $candidates")
    }
    return candidates
}

private fun extensionSuffix(fileName: String): String =
    fileName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.let { ".${it.lowercase()}" }
        ?: ".rom"

/** Copies a stream to a temp file (named with the right extension) and hashes it. */
private fun hashCandidatesViaTempCopy(
    fileName: String,
    tempDir: File?,
    openStream: () -> InputStream?,
): List<String> {
    val suffix = extensionSuffix(fileName)
    val tempFile = if (tempDir != null) {
        File.createTempFile("romhash_", suffix, tempDir)
    } else {
        File.createTempFile("romhash_", suffix)
    }
    return try {
        val copied = runCatching {
            openStream()?.use { input -> tempFile.outputStream().use(input::copyTo) }
        }.getOrNull()
        if (copied == null) {
            logWarn(TAG, "Could not read bytes for $fileName")
            emptyList()
        } else {
            hashCandidatesForPath(tempFile.absolutePath)
        }
    } finally {
        tempFile.delete()
    }
}

// ---- Public entry points (compatibility surface) ----

internal fun hashRom(input: RomHashInput): String? = hashRomCandidates(input).firstOrNull()

internal fun hashRomCandidates(input: RomHashInput): List<String> {
    val fileName = input.fileName
    if (isNintendoDiscContainer(fileName)) {
        return hashDiscCandidates(fileName) {
            input.openDataSource?.invoke()
                ?: input.sourcePath?.let { FileRomDataSource(File(it)) }
        }
    }
    val realPath = input.sourcePath
    if (realPath != null && File(realPath).isFile) {
        return hashCandidatesForPath(realPath)
    }
    return hashCandidatesViaTempCopy(fileName, tempDir = null, openStream = input.openStream)
}

internal fun hashRomFile(context: Context, file: File): String? =
    hashRomFileCandidates(context, file).firstOrNull()

internal fun hashRomFileCandidates(context: Context, file: File): List<String> {
    val fileName = file.name
    if (hasExtension(fileName, "zip")) {
        return hashZipRomCandidates(fileName, file.absolutePath, context.cacheDir) { file.inputStream() }
    }
    if (isNintendoDiscContainer(fileName)) {
        return hashDiscCandidates(fileName) { FileRomDataSource(file) }
    }
    // rc_hash resolves .cue/.m3u (and everything else) directly from the path.
    return hashCandidatesForPath(file.absolutePath)
}

internal fun hashRom(context: Context, file: DocumentFile): String? =
    hashRomCandidates(context, file).firstOrNull()

internal fun hashRomCandidates(context: Context, file: DocumentFile): List<String> {
    val fileName = file.name ?: return emptyList()

    if (hasExtension(fileName, "zip")) {
        val zipSourcePath = resolveDocumentAbsolutePath(file)?.takeIf { File(it).isFile }
        return hashZipRomCandidates(fileName, zipSourcePath, context.cacheDir) {
            context.contentResolver.openInputStream(file.uri)
        }
    }

    if (isNintendoDiscContainer(fileName)) {
        return hashDiscCandidates(fileName) {
            context.contentResolver.openFileDescriptor(file.uri, "r")
                ?.let { ParcelFileDescriptorRomDataSource(it) }
        }
    }

    // Prefer a real filesystem path: lets the native hasher read the file (and,
    // for .cue/.m3u, its siblings) directly without copying.
    val realPath = resolveDocumentAbsolutePath(file)
    if (realPath != null && File(realPath).isFile) {
        return hashCandidatesForPath(realPath)
    }

    // SAF-only fallback: copy the document's bytes to a temp file and hash that.
    // (.cue/.m3u that reach here can't resolve siblings; folder scans normally
    // resolve a real path above.)
    return hashCandidatesViaTempCopy(fileName, context.cacheDir) {
        context.contentResolver.openInputStream(file.uri)
    }
}

internal fun hashZipRom(
    fileName: String,
    sourcePath: String?,
    tempDir: File,
    openArchiveStream: () -> InputStream?,
): String? = hashZipRomCandidates(fileName, sourcePath, tempDir, openArchiveStream).firstOrNull()

internal fun hashZipRomCandidates(
    fileName: String,
    sourcePath: String?,
    tempDir: File,
    openArchiveStream: () -> InputStream?,
): List<String> {
    val romEntryName = findSingleSupportedZipEntryName(openArchiveStream)
    if (romEntryName != null) {
        // A zipped single console ROM: extract and hash its actual content.
        val tempFile = extractZipEntryToTempFile(openArchiveStream, romEntryName, tempDir)
            ?: return emptyList()
        return try {
            hashCandidatesForPath(tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    // Otherwise treat it as an arcade/MAME set. rc_hash's arcade hash is just
    // MD5 of the zip's base filename (e.g. "aliens"), and it never reads the
    // contents — so the *name* must be preserved. Use the real path when we have
    // one; otherwise copy to a temp file that keeps the original name.
    if (sourcePath != null && File(sourcePath).isFile) {
        return hashCandidatesForPath(sourcePath)
    }
    return hashArcadeZipViaNamedTemp(fileName, tempDir, openArchiveStream)
}

/** Copies an archive to a temp file that keeps [fileName] so the arcade
 * (filename-based) hash is computed correctly, then hashes and cleans up. */
private fun hashArcadeZipViaNamedTemp(
    fileName: String,
    tempDir: File,
    openArchiveStream: () -> InputStream?,
): List<String> {
    val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "archive.zip" }
    val workDir = File(tempDir, "romhash_${System.nanoTime()}")
    if (!workDir.mkdirs()) return emptyList()
    val named = File(workDir, safeName)
    return try {
        val copied = runCatching {
            openArchiveStream()?.use { input -> named.outputStream().use(input::copyTo) }
        }.getOrNull()
        if (copied == null) emptyList() else hashCandidatesForPath(named.absolutePath)
    } finally {
        named.delete()
        workDir.delete()
    }
}

private fun findSingleSupportedZipEntryName(
    openArchiveStream: () -> InputStream?,
): String? {
    var matchedEntryName: String? = null

    ZipInputStream(openArchiveStream() ?: return null).use { archive ->
        while (true) {
            val entry = archive.nextEntry ?: break
            val entryName = entry.name
            if (entry.isDirectory || !isSupportedArchiveRomEntry(entryName)) {
                archive.closeEntry()
                continue
            }
            if (matchedEntryName != null) {
                archive.closeEntry()
                return null
            }
            matchedEntryName = entryName
            archive.closeEntry()
        }
    }

    return matchedEntryName
}

private fun extractZipEntryToTempFile(
    openArchiveStream: () -> InputStream?,
    entryName: String,
    tempDir: File,
): File? {
    ZipInputStream(openArchiveStream() ?: return null).use { archive ->
        while (true) {
            val entry = archive.nextEntry ?: break
            if (entry.isDirectory || entry.name != entryName) {
                archive.closeEntry()
                continue
            }

            val entryFileName = entryName.substringAfterLast('/').substringAfterLast('\\')
            val tempFile = File.createTempFile("romhash_", extensionSuffix(entryFileName), tempDir)
            tempFile.outputStream().use { output -> archive.copyTo(output) }
            archive.closeEntry()
            return tempFile
        }
    }

    return null
}

private fun isSupportedArchiveRomEntry(entryName: String): Boolean {
    val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
    if (fileName.isBlank() || fileName.startsWith('.')) {
        return false
    }

    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in supportedArchiveRomExtensions
}

// ---- Random-access sources backing the disc-container decompressors ----

internal class FileRomDataSource(
    file: File,
) : RomDataSource {
    private val inputStream = FileInputStream(file)
    private val channel = inputStream.channel

    override val length: Long
        get() = channel.size()

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        channel.position(offset)
        val targetLength = length.coerceAtMost(buffer.size)
        var totalRead = 0
        while (totalRead < targetLength) {
            val read = inputStream.read(buffer, totalRead, targetLength - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        channel.close()
        inputStream.close()
    }
}

internal class ParcelFileDescriptorRomDataSource(
    private val fileDescriptor: ParcelFileDescriptor,
) : RomDataSource {
    private val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    private val channel = inputStream.channel

    override val length: Long
        get() = channel.size()

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        channel.position(offset)
        val targetLength = length.coerceAtMost(buffer.size)
        var totalRead = 0
        while (totalRead < targetLength) {
            val read = inputStream.read(buffer, totalRead, targetLength - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        channel.close()
        inputStream.close()
        fileDescriptor.close()
    }
}
