package com.raofflineproxy.proxy.hash

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

internal data class RomHashInput(
    val fileName: String,
    val fileSize: Long,
    val openStream: () -> InputStream?
)

internal interface RomHashStrategy {
    fun matches(fileName: String): Boolean

    fun hash(input: RomHashInput): String?
}

private val romHashStrategies: List<RomHashStrategy> = listOf(
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
        openStream = { context.contentResolver.openInputStream(file.uri) }
    )
    val strategy = romHashStrategies.firstOrNull { it.matches(fileName) } ?: GenericMd5RomHashStrategy
    return strategy.hash(input)
}

internal fun hasExtension(fileName: String, vararg extensions: String): Boolean =
    extensions.any { extension -> fileName.endsWith(".$extension", ignoreCase = true) }
