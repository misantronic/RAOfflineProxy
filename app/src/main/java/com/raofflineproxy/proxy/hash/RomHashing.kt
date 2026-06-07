package com.raofflineproxy.proxy.hash

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.raofflineproxy.proxy.resolveDocumentRelativePath
import com.raofflineproxy.proxy.storageRoots
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val TAG = "RAProxy/Hash"

private val supportedArchiveRomExtensions = setOf(
    "a78",
    "bin",
    "cart",
    "ciso",
    "fds",
    "fig",
    "gba",
    "gb",
    "gbc",
    "gcm",
    "gcz",
    "iso",
    "lnx",
    "n64",
    "nds",
    "nes",
    "pbp",
    "pce",
    "sfc",
    "sgx",
    "smc",
    "swc",
    "v64",
    "wad",
    "wbfs",
    "z64"
)

internal data class RomHashInput(
    val fileName: String,
    val fileSize: Long,
    val openStream: () -> InputStream?,
    val openDataSource: (() -> RomDataSource?)? = null,
    val openPspChdDataSource: (() -> RomDataSource?)? = null,
    val openPsxChdDataSource: (() -> RomDataSource?)? = null
)

internal interface RomDataSource : Closeable {
    val length: Long

    fun read(offset: Long, buffer: ByteArray, length: Int = buffer.size): Int
}

internal interface RomHashStrategy {
    fun matches(fileName: String): Boolean

    fun hash(input: RomHashInput): String?
}

private val romHashStrategies: List<RomHashStrategy> = listOf(
    RvzRomHashStrategy,
    WiiWadRomHashStrategy,
    GameCubeRomHashStrategy,
    WiiDiscRomHashStrategy,
    PspRomHashStrategy,
    PsxRomHashStrategy,
    PsxChdRomHashStrategy,
    PspChdRomHashStrategy,
    NintendoDsRomHashStrategy,
    Nintendo64RomHashStrategy,
    Atari7800RomHashStrategy,
    AtariLynxRomHashStrategy,
    NesRomHashStrategy,
    FdsRomHashStrategy,
    PcEngineRomHashStrategy,
    SuperCassetteVisionRomHashStrategy,
    SnesRomHashStrategy,
    GameBoyAdvanceRomHashStrategy,
    GameBoyColorRomHashStrategy,
    GameBoyRomHashStrategy
)

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

private fun hashPlaylistSibling(context: Context, file: DocumentFile, siblingName: String): String? {
    val fileName = file.name ?: "playlist"

    // Folder-scan case: the sibling is reachable through the same SAF tree.
    file.parentFile?.findFile(siblingName)?.let { return hashRom(context, it) }

    // Single-file case: we only have a document URI with no parent, so fall back to
    // the real filesystem path. This requires all-files access on Android 11+.
    val hasAllFilesAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    if (!hasAllFilesAccess) {
        logWarn(TAG, "Cannot access sibling $siblingName for $fileName — scan a folder or grant all-files access")
        return null
    }

    val relativePath = resolveDocumentRelativePath(file)
    if (relativePath == null) {
        logWarn(TAG, "Could not resolve a storage-relative path for $fileName (uri=${file.uri})")
        return null
    }
    val relativeParent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")

    // ROMs may live on internal storage or an SD card, so try every mounted root.
    for (root in storageRoots()) {
        val parentDir = if (relativeParent.isEmpty()) root else File(root, relativeParent)
        val siblingFile = findSiblingFileOnDisk(parentDir, siblingName)
        if (siblingFile != null) {
            logInfo(TAG, "Hashing playlist sibling directly from filesystem: ${siblingFile.path}")
            return hashRomFile(context, siblingFile)
        }
    }

    logWarn(TAG, "Sibling $siblingName not found under any storage root for relativeParent=$relativeParent")
    for (root in storageRoots()) {
        val parentDir = if (relativeParent.isEmpty()) root else File(root, relativeParent)
        val listing = parentDir.listFiles()
        logWarn(
            TAG,
            "  tried ${parentDir.path} (exists=${parentDir.exists()} canRead=${parentDir.canRead()} entries=${listing?.size ?: -1})"
        )
    }
    return null
}

private fun findSiblingFileOnDisk(parentDir: File, siblingName: String): File? {
    val direct = parentDir.resolve(siblingName)
    if (direct.exists()) return direct
    // Some rips reference the BIN with different casing than the file on disk.
    return parentDir.listFiles()?.firstOrNull { it.name.equals(siblingName, ignoreCase = true) }
}

internal fun hashRomFile(context: Context, file: File): String? {
    val fileName = file.name

    if (hasExtension(fileName, "m3u")) {
        val firstEntry = parseM3uFirstEntry(file.readText()) ?: return null
        val entryName = firstEntry.substringAfterLast('/').substringAfterLast('\\')
        val entry = file.parentFile?.resolve(entryName)
        if (entry == null || !entry.exists()) {
            logWarn(TAG, "Resolved M3U entry not found: $entryName")
            return null
        }
        return hashRomFile(context, entry)
    }

    if (hasExtension(fileName, "cue")) {
        val binFileName = parseCueDataBinFileName(file.readText())
        if (binFileName == null) {
            logWarn(TAG, "No data track found in CUE file $fileName")
            return null
        }
        val bin = file.parentFile?.resolve(binFileName)
        if (bin == null || !bin.exists()) {
            logWarn(TAG, "Resolved CUE data track not found: $binFileName")
            return null
        }
        return hashRomFile(context, bin)
    }

    if (hasExtension(fileName, "zip")) {
        return hashZipRom(
            tempDir = context.cacheDir,
            openArchiveStream = { file.inputStream() }
        )
    }

    return hashRom(
        RomHashInput(
            fileName = fileName,
            fileSize = file.length(),
            openStream = { file.inputStream() },
            openDataSource = { FileRomDataSource(file) },
            openPspChdDataSource = {
                if (!hasExtension(fileName, "chd")) null else PspChdRomDataSource.open(file)
            },
            openPsxChdDataSource = {
                if (!hasExtension(fileName, "chd")) null else PsxChdRomDataSource.open(file)
            }
        )
    )
}

internal fun hashRom(
    context: Context,
    file: DocumentFile
): String? {
    val fileName = file.name ?: return null

    if (hasExtension(fileName, "m3u")) {
        val content = context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader()?.use { it.readText() } ?: return null
        val firstEntry = parseM3uFirstEntry(content) ?: return null
        val entryName = firstEntry.substringAfterLast('/').substringAfterLast('\\')
        return hashPlaylistSibling(context, file, entryName)
    }

    if (hasExtension(fileName, "cue")) {
        val content = context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader()?.use { it.readText() } ?: return null
        val binFileName = parseCueDataBinFileName(content)
        if (binFileName == null) {
            logWarn(TAG, "No data track found in CUE file $fileName")
            return null
        }
        return hashPlaylistSibling(context, file, binFileName)
    }

    if (hasExtension(fileName, "zip")) {
        return hashZipRom(
            tempDir = context.cacheDir,
            openArchiveStream = { context.contentResolver.openInputStream(file.uri) }
        )
    }

    return hashRom(
        RomHashInput(
            fileName = fileName,
            fileSize = file.length(),
            openStream = { context.contentResolver.openInputStream(file.uri) },
            openDataSource = {
                context.contentResolver.openFileDescriptor(file.uri, "r")?.let(::ParcelFileDescriptorRomDataSource)
            },
            openPspChdDataSource = {
                if (!hasExtension(fileName, "chd")) {
                    null
                } else {
                    openWrappedChdDataSource(
                        tempDir = context.cacheDir,
                        openInputStream = { context.contentResolver.openInputStream(file.uri) },
                        openDataSource = PspChdRomDataSource::open
                    )
                }
            },
            openPsxChdDataSource = {
                if (!hasExtension(fileName, "chd")) {
                    null
                } else {
                    openWrappedChdDataSource(
                        tempDir = context.cacheDir,
                        openInputStream = { context.contentResolver.openInputStream(file.uri) },
                        openDataSource = PsxChdRomDataSource::open
                    )
                }
            }
        )
    )
}

private fun openWrappedChdDataSource(
    tempDir: File,
    openInputStream: () -> InputStream?,
    openDataSource: (File) -> RomDataSource?
): RomDataSource? {
    val tempFile = File.createTempFile("romhash_", ".chd", tempDir)
    val copied = runCatching {
        openInputStream()?.use { input ->
            tempFile.outputStream().use(input::copyTo)
        }
    }.getOrNull()
    if (copied == null) {
        tempFile.delete()
        return null
    }

    openDataSource(tempFile)?.let { dataSource ->
        return object : RomDataSource by dataSource {
            override fun close() {
                dataSource.close()
                tempFile.delete()
            }
        }
    }

    tempFile.delete()
    return null
}

internal fun hashRom(input: RomHashInput): String? {
    val fileName = input.fileName
    romHashStrategies.forEach { strategy ->
        if (!strategy.matches(fileName)) return@forEach
        logInfo(TAG, "Trying ${strategy.javaClass.simpleName} for $fileName size=${input.fileSize}")
        val hash = strategy.hash(input)
        if (hash != null) {
            logInfo(TAG, "${strategy.javaClass.simpleName} produced hash=$hash for $fileName")
            return hash
        }
        logInfo(TAG, "${strategy.javaClass.simpleName} could not hash $fileName")
    }

    if (hasExtension(fileName, "chd")) {
        logWarn(TAG, "Refusing generic MD5 fallback for $fileName because CHD needs disc-aware hashing")
        return null
    }

    val detectedNintendoFormat = detectNintendoDiscFormat(input)
    if (detectedNintendoFormat != null) {
        logWarn(TAG, "Refusing generic MD5 fallback for $fileName detectedFormat=$detectedNintendoFormat")
        return null
    }

    val fallback = GenericMd5RomHashStrategy.hash(input)
    if (fallback != null) {
        logInfo(TAG, "GenericMd5RomHashStrategy produced hash=$fallback for $fileName")
    } else {
        logWarn(TAG, "No hash strategy could hash $fileName")
    }
    return fallback
}

internal fun hashZipRom(
    tempDir: File,
    openArchiveStream: () -> InputStream?
): String? {
    val romEntryName = findSingleSupportedZipEntryName(openArchiveStream) ?: return null
    val tempFile = extractZipEntryToTempFile(openArchiveStream, romEntryName, tempDir) ?: return null

    return try {
        hashRom(
            RomHashInput(
                fileName = romEntryName.substringAfterLast('/').substringAfterLast('\\'),
                fileSize = tempFile.length(),
                openStream = { tempFile.inputStream() },
                openDataSource = { FileRomDataSource(tempFile) }
            )
        )
    } finally {
        tempFile.delete()
    }
}

private fun findSingleSupportedZipEntryName(
    openArchiveStream: () -> InputStream?
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
    tempDir: File
): File? {
    ZipInputStream(openArchiveStream() ?: return null).use { archive ->
        while (true) {
            val entry = archive.nextEntry ?: break
            if (entry.isDirectory || entry.name != entryName) {
                archive.closeEntry()
                continue
            }

            val entryFileName = entryName.substringAfterLast('/').substringAfterLast('\\')
            val suffix = entryFileName.substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.let { ".${it.lowercase()}" }
                ?: ".rom"
            val tempFile = File.createTempFile("romhash_", suffix, tempDir)
            tempFile.outputStream().use { output ->
                archive.copyTo(output)
            }
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

internal fun hasExtension(fileName: String, vararg extensions: String): Boolean =
    extensions.any { extension -> fileName.endsWith(".$extension", ignoreCase = true) }

private class FileRomDataSource(
    file: File
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

private class ParcelFileDescriptorRomDataSource(
    private val fileDescriptor: ParcelFileDescriptor
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
