package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest
import kotlin.math.ceil

private const val ISO_SECTOR = 2048
private const val RAW_2352_SECTOR = 2352
private const val MODE2_2336_SECTOR = 2336
private const val PVD_SECTOR = 16L
private const val PVD_SCAN_WINDOW = 32L
private const val MAX_HASH_BYTES = 64L * 1024L * 1024L
private const val PSX_EXE_HEADER_SIZE = 2048
private const val PSX_EXE_BODY_SIZE_OFFSET = 0x1C
private const val PVD_LOGICAL_BLOCK_SIZE_OFFSET = 128
private val PVD_MAGIC = "CD001".toByteArray(Charsets.US_ASCII)
private val PSX_EXE_MAGIC = "PS-X EXE".toByteArray(Charsets.US_ASCII)

internal object PsxRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "bin", "iso")

    override fun hash(input: RomHashInput): String? {
        val openDataSource = input.openDataSource ?: return null
        return openDataSource().use { dataSource ->
            if (dataSource == null) return@use null

            val sectorLayout = detectSectorLayout(dataSource) ?: return@use null
            val executable = findExecutable(dataSource, sectorLayout) ?: return@use null
            hashExecutable(dataSource, sectorLayout, executable)
        }
    }

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

    private fun detectSectorLayout(dataSource: RomDataSource): SectorLayout? {
        val candidates = listOf(
            SectorLayout(ISO_SECTOR, 0, 0),
            SectorLayout(RAW_2352_SECTOR, 24, 0),
            SectorLayout(RAW_2352_SECTOR, 16, 0),
            SectorLayout(MODE2_2336_SECTOR, 8, 0)
        )
        return candidates.firstNotNullOfOrNull { layout -> detectPrimaryVolumeDescriptor(dataSource, layout) }
    }

    internal fun detectPrimaryVolumeDescriptor(dataSource: RomDataSource, layout: SectorLayout): SectorLayout? {
        for (physicalSector in PVD_SECTOR..(PVD_SECTOR + PVD_SCAN_WINDOW)) {
            val sector = readPhysicalSector(dataSource, layout, physicalSector) ?: continue
            if (sector.size >= 6 && sector[0] == 1.toByte() && sector.copyOfRange(1, 6).contentEquals(PVD_MAGIC)) {
                val sectorBias = physicalSector - PVD_SECTOR
                return layout.copy(sectorBias = sectorBias)
            }
        }
        return null
    }

    private fun findExecutable(dataSource: RomDataSource, layout: SectorLayout): PsxExecutable? {
        val systemCnf = readFileText(dataSource, layout, "SYSTEM.CNF")
        val bootPath = parseBootPath(systemCnf ?: "") ?: "PSX.EXE"
        val executableRecord = findFileRecord(dataSource, layout, bootPath) ?: return null
        return PsxExecutable(bootPath, executableRecord.sector, executableRecord.size)
    }

    private fun hashExecutable(dataSource: RomDataSource, layout: SectorLayout, executable: PsxExecutable): String? {
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

    private fun readFileText(dataSource: RomDataSource, layout: SectorLayout, path: String): String? {
        val record = findFileRecord(dataSource, layout, path) ?: return null
        val bytes = readFileBytes(dataSource, layout, record.sector, record.size.toInt()) ?: return null
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readFileBytes(dataSource: RomDataSource, layout: SectorLayout, startSector: Long, size: Int): ByteArray? {
        val result = ByteArray(size)
        var remaining = size
        var writeOffset = 0
        var sectorIndex = startSector
        while (remaining > 0) {
            val sector = readSector(dataSource, layout, sectorIndex) ?: return null
            val count = minOf(remaining, sector.size)
            sector.copyInto(result, writeOffset, 0, count)
            remaining -= count
            writeOffset += count
            sectorIndex++
        }
        return result
    }

    private fun findFileRecord(dataSource: RomDataSource, layout: SectorLayout, path: String): IsoFileRecord? {
        val segments = path.split('\\', '/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val rootRecord = readRootDirectoryRecord(dataSource, layout) ?: return null
        var currentSector = rootRecord.sector
        var currentSize = rootRecord.size

        for ((index, segment) in segments.withIndex()) {
            val record = findDirectoryEntry(dataSource, layout, currentSector, currentSize, segment) ?: return null
            if (index == segments.lastIndex) return record
            if (!record.isDirectory) return null
            currentSector = record.sector
            currentSize = record.size
        }

        return null
    }

    private fun readRootDirectoryRecord(dataSource: RomDataSource, layout: SectorLayout): IsoFileRecord? {
        val sector = readSector(dataSource, layout, PVD_SECTOR) ?: return null
        if (sector.size < 190) return null
        return parseDirectoryRecord(sector, 156)
    }

    private fun findDirectoryEntry(
        dataSource: RomDataSource,
        layout: SectorLayout,
        directorySector: Long,
        directorySize: Long,
        targetName: String
    ): IsoFileRecord? {
        val logicalBlockSize = readLogicalBlockSize(dataSource, layout)
        val sectorsToScan = ceil(directorySize.toDouble() / logicalBlockSize.toDouble()).toInt()
        var sectorIndex = directorySector
        repeat(sectorsToScan) {
            val sector = readSector(dataSource, layout, sectorIndex) ?: return null
            var offset = 0
            while (offset < logicalBlockSize && offset < sector.size) {
                val length = sector[offset].toInt() and 0xFF
                if (length == 0) break
                val record = parseDirectoryRecord(sector, offset)
                if (record != null && namesEqual(record.name, targetName)) {
                    return record
                }
                offset += length
            }
            sectorIndex++
        }
        return null
    }

    private fun readLogicalBlockSize(dataSource: RomDataSource, layout: SectorLayout): Int {
        val pvd = readSector(dataSource, layout, PVD_SECTOR) ?: return ISO_SECTOR
        val blockSize = littleEndianShort(pvd, PVD_LOGICAL_BLOCK_SIZE_OFFSET)
        return if (blockSize in 1..ISO_SECTOR) blockSize else ISO_SECTOR
    }

    private fun parseDirectoryRecord(sector: ByteArray, offset: Int): IsoFileRecord? {
        if (offset + 34 > sector.size) return null
        val length = sector[offset].toInt() and 0xFF
        if (length == 0 || offset + length > sector.size) return null

        val sectorNumber = littleEndianInt(sector, offset + 2).toLong() and 0xFFFF_FFFFL
        val size = littleEndianInt(sector, offset + 10).toLong() and 0xFFFF_FFFFL
        val flags = sector[offset + 25].toInt() and 0xFF
        val nameLength = sector[offset + 32].toInt() and 0xFF
        if (offset + 33 + nameLength > sector.size) return null
        val rawName = sector.copyOfRange(offset + 33, offset + 33 + nameLength)
        if (nameLength == 1 && (rawName[0] == 0.toByte() || rawName[0] == 1.toByte())) {
            return IsoFileRecord(sectorNumber, size, "", flags and 0x02 != 0)
        }
        val name = rawName.toString(Charsets.US_ASCII).substringBefore(';')
        return IsoFileRecord(sectorNumber, size, name, flags and 0x02 != 0)
    }

    private fun namesEqual(recordName: String, targetName: String): Boolean =
        recordName.equals(targetName.substringBefore(';'), ignoreCase = true)

    private fun readSector(dataSource: RomDataSource, layout: SectorLayout, sectorIndex: Long): ByteArray? {
        return readPhysicalSector(dataSource, layout, sectorIndex + layout.sectorBias)
    }

    private fun readPhysicalSector(dataSource: RomDataSource, layout: SectorLayout, physicalSectorIndex: Long): ByteArray? {
        val rawBuffer = ByteArray(layout.rawSectorSize)
        val offset = physicalSectorIndex * layout.rawSectorSize
        val read = dataSource.read(offset, rawBuffer, rawBuffer.size)
        if (read < layout.dataOffset + ISO_SECTOR) return null
        return rawBuffer.copyOfRange(layout.dataOffset, layout.dataOffset + ISO_SECTOR)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

internal data class SectorLayout(
    val rawSectorSize: Int,
    val dataOffset: Int,
    val sectorBias: Long
)

private data class IsoFileRecord(
    val sector: Long,
    val size: Long,
    val name: String,
    val isDirectory: Boolean
)

private data class PsxExecutable(
    val path: String,
    val sector: Long,
    val size: Long
)
