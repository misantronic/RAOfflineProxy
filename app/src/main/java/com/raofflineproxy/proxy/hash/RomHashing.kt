package com.raofflineproxy.proxy.hash

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream

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
    PsxRomHashStrategy,
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
        val hash = strategy.hash(input)
        if (hash != null) return hash
    }
    return GenericMd5RomHashStrategy.hash(input)
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
        return inputStream.read(buffer, 0, length.coerceAtMost(buffer.size))
    }

    override fun close() {
        channel.close()
        inputStream.close()
        fileDescriptor.close()
    }
}
