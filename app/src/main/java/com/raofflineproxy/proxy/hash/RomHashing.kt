package com.raofflineproxy.proxy.hash

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream

private const val TAG = "RAProxy/Hash"

internal data class RomHashInput(
    val fileName: String,
    val fileSize: Long,
    val openStream: () -> InputStream?,
    val openDataSource: (() -> RomDataSource?)? = null
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
    PspRomHashStrategy,
    PsxRomHashStrategy,
    Nintendo64RomHashStrategy,
    NesRomHashStrategy,
    FdsRomHashStrategy,
    SnesRomHashStrategy,
    GameBoyAdvanceRomHashStrategy,
    GameBoyColorRomHashStrategy,
    GameBoyRomHashStrategy
)

internal fun hashRom(context: Context, file: DocumentFile): String? {
    val fileName = file.name ?: return null
    val input = RomHashInput(
        fileName = fileName,
        fileSize = file.length(),
        openStream = { context.contentResolver.openInputStream(file.uri) },
        openDataSource = {
            context.contentResolver.openFileDescriptor(file.uri, "r")?.let(::ParcelFileDescriptorRomDataSource)
        }
    )
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
    val fallback = GenericMd5RomHashStrategy.hash(input)
    if (fallback != null) {
        logInfo(TAG, "GenericMd5RomHashStrategy produced hash=$fallback for $fileName")
    } else {
        logWarn(TAG, "No hash strategy could hash $fileName")
    }
    return fallback
}

internal fun hasExtension(fileName: String, vararg extensions: String): Boolean =
    extensions.any { extension -> fileName.endsWith(".$extension", ignoreCase = true) }

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
