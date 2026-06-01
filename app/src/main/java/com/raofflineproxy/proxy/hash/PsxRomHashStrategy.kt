package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

private const val MAX_HASH_BYTES = 64L * 1024L * 1024L
private const val PSX_EXE_HEADER_SIZE = 2048
private const val PSX_EXE_BODY_SIZE_OFFSET = 0x1C
private val PSX_EXE_MAGIC = "PS-X EXE".toByteArray(Charsets.US_ASCII)

internal object PsxRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "bin", "iso")

    override fun hash(input: RomHashInput): String? = hashPsxDisc(input, input.openDataSource)

    internal fun parseBootPath(systemCnf: String): String? {
        val bootLine = systemCnf.lineSequence().firstOrNull { line ->
            line.trimStart().let { trimmed ->
                trimmed.startsWith("BOOT", ignoreCase = true)
                    && trimmed.drop(4).firstOrNull()?.let { it.isWhitespace() || it == '=' } != false
            }
        } ?: return null

        val afterKey = bootLine.substringAfter("BOOT", "")
        if (afterKey.isEmpty()) return null
        val rawValue = afterKey.substringAfter('=', "").trimStart()
        if (rawValue.isEmpty()) return null

        var path = rawValue
        if (path.startsWith("cdrom:", ignoreCase = true)) {
            path = path.substring(6)
        }
        path = path.trimStart { it == '\\' }

        val endIndex = path.indexOfFirst { it.isWhitespace() || it == ';' }
        val normalized = if (endIndex >= 0) path.substring(0, endIndex) else path
        return normalized.takeIf { it.isNotEmpty() }
    }

    internal fun findExecutable(dataSource: RomDataSource, layout: SectorLayout): PsxExecutable? {
        val systemCnf = readFileText(dataSource, layout, "SYSTEM.CNF")
        val bootPath = parseBootPath(systemCnf ?: "") ?: "PSX.EXE"
        val executableRecord = findFileRecord(dataSource, layout, bootPath) ?: return null
        return PsxExecutable(bootPath, executableRecord.sector, executableRecord.size)
    }

    internal fun hashExecutable(dataSource: RomDataSource, layout: SectorLayout, executable: PsxExecutable): String? {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(executable.path.toByteArray(Charsets.US_ASCII))

        val firstSector = readSector(dataSource, layout, executable.sector) ?: return null
        var bytesToHash = executable.size.coerceAtMost(MAX_HASH_BYTES)
        if (firstSector.startsWithMagic(firstSector.size, PSX_EXE_MAGIC)) {
            val bodySize = littleEndianInt(firstSector, PSX_EXE_BODY_SIZE_OFFSET).toLong() and 0xFFFF_FFFFL
            bytesToHash = (bodySize + PSX_EXE_HEADER_SIZE).coerceAtMost(MAX_HASH_BYTES)
        }

        val buffer = ByteArray(ISO_SECTOR)
        var remaining = bytesToHash
        var sectorIndex = executable.sector
        while (remaining > 0) {
            val sector = readSector(dataSource, layout, sectorIndex) ?: return null
            val count = minOf(remaining.toInt(), sector.size)
            sector.copyInto(buffer, endIndex = count)
            digest.update(buffer, 0, count)
            remaining -= count
            sectorIndex++
        }
        return digest.digest().toHexString()
    }

}

internal object PsxChdRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "chd")

    override fun hash(input: RomHashInput): String? = hashPsxDisc(input, input.openPsxChdDataSource)
}

private fun hashPsxDisc(input: RomHashInput, openDataSource: (() -> RomDataSource?)?): String? {
    val sourceFactory = openDataSource ?: return null
    return sourceFactory().use { dataSource ->
        if (dataSource == null) return@use null

        val sectorLayout = detectIsoSectorLayout(dataSource) ?: return@use null
        val executable = PsxRomHashStrategy.findExecutable(dataSource, sectorLayout) ?: return@use null
        PsxRomHashStrategy.hashExecutable(dataSource, sectorLayout, executable)
    }
}

internal data class PsxExecutable(
    val path: String,
    val sector: Long,
    val size: Long
)
