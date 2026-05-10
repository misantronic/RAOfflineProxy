package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

private const val TAG = "RAProxy/NintendoDiscHash"
private const val MAX_HASH_BUFFER_SIZE = 64 * 1024 * 1024
private const val MAX_DISC_HEADER_SIZE = 1024 * 1024
private const val MAX_DISC_CHUNK_SIZE = 1024 * 1024
private const val GAMECUBE_DISC_MAGIC = 0xC2339F3D.toInt()
private const val WII_DISC_MAGIC = 0x5D1C9EA3
private const val NINTENDO_DISC_BASE_HEADER_SIZE = 0x2440
private const val NINTENDO_APPLOADER_HEADER_SIZE = 0x20
private const val NINTENDO_APPLOADER_SIZE_OFFSET = NINTENDO_DISC_BASE_HEADER_SIZE + 0x14
private const val WII_REGION_CODE_OFFSET = 0x4E000L
private const val WII_HEADER_HASH_SIZE = 0x80
private const val WII_REGION_CODE_SIZE = 4
private const val WII_TMD_SIZE_OFFSET = 0x2A4L
private const val WII_TMD_OFFSET_OFFSET = 0x2A8L
private const val WII_PARTITION_DATA_OFFSET = 0x2B8L
private const val WII_DOL_OFFSET_OFFSET = 0x420
private const val WII_CLUSTER_PHYSICAL_SIZE = 0x8000
private const val WII_CLUSTER_HASH_HEADER_SIZE = 0x400
private const val WII_HASHED_CLUSTER_DATA_SIZE = 0x7C00
private const val WII_MAX_ENCRYPTED_CLUSTERS = 1024
private const val WII_PARTITION_TYPE_UPDATE = 1
private const val DOL_SECTION_COUNT = 18
private const val DOL_SECTION_OFFSET_TABLE = 0x00
private const val DOL_SECTION_SIZE_TABLE = 0x90
private const val DOL_TABLE_SIZE = 0xD8

internal enum class NintendoDiscFormat {
    GAMECUBE,
    WII,
    WII_WAD,
    RVZ
}

internal data class NintendoPartitionEntry(
    val offset: Long,
    val type: Int
)

internal data class NintendoDolSection(
    val absoluteOffset: Long,
    val size: Long
)

internal fun hashGameCubeDisc(input: RomHashInput): String? {
    val openDataSource = input.openDataSource ?: return null
    return openDataSource().use { dataSource ->
        if (dataSource == null) return@use null
        val digest = MessageDigest.getInstance("MD5")
        if (!updateGameCubePartition(digest, dataSource)) return@use null
        digest.digest().toHexString()
    }
}

internal fun hashWiiDisc(input: RomHashInput): String? {
    val openDataSource = input.openDataSource ?: return null
    return openDataSource().use { dataSource ->
        if (dataSource == null) return@use null
        val digest = MessageDigest.getInstance("MD5")
        if (!updateWiiDisc(digest, dataSource)) return@use null
        digest.digest().toHexString()
    }
}

internal fun hashWiiWad(input: RomHashInput): String? {
    val openDataSource = input.openDataSource ?: return null
    return openDataSource().use { dataSource ->
        if (dataSource == null) return@use null
        val digest = MessageDigest.getInstance("MD5")
        if (!updateWiiWad(digest, dataSource)) return@use null
        digest.digest().toHexString()
    }
}

internal fun detectNintendoDiscFormat(input: RomHashInput): NintendoDiscFormat? {
    if (hasExtension(input.fileName, "rvz")) return NintendoDiscFormat.RVZ
    if (hasExtension(input.fileName, "wad")) return NintendoDiscFormat.WII_WAD
    if (hasExtension(input.fileName, "gcm")) return NintendoDiscFormat.GAMECUBE
    if (!hasExtension(input.fileName, "iso")) return null

    val openDataSource = input.openDataSource ?: return null
    return openDataSource().use { dataSource ->
        if (dataSource == null) return@use null
        when {
            readBigEndianInt(dataSource, 0x1CL) == GAMECUBE_DISC_MAGIC -> NintendoDiscFormat.GAMECUBE
            readBigEndianInt(dataSource, 0x18L) == WII_DISC_MAGIC -> NintendoDiscFormat.WII
            else -> null
        }
    }
}

private fun updateGameCubePartition(digest: MessageDigest, dataSource: RomDataSource): Boolean {
    val discMagic = readBigEndianInt(dataSource, 0x1CL) ?: return false
    if (discMagic != GAMECUBE_DISC_MAGIC) {
        logInfo(TAG, "GameCube magic mismatch: 0x${discMagic.toUInt().toString(16)}")
        return false
    }

    return updateNintendoDiscPartition(
        digest = digest,
        dataSource = dataSource,
        partitionOffset = 0L,
        wiiShift = 0
    )
}

private fun updateWiiDisc(digest: MessageDigest, dataSource: RomDataSource): Boolean {
    val discMagic = readBigEndianInt(dataSource, 0x18L) ?: return false
    if (discMagic != WII_DISC_MAGIC) {
        logInfo(TAG, "Wii magic mismatch: 0x${discMagic.toUInt().toString(16)}")
        return false
    }

    val encrypted = readBytes(dataSource, 0x61L, 1)?.firstOrNull()?.let { it == 0.toByte() } ?: return false

    val header = readBytes(dataSource, 0L, WII_HEADER_HASH_SIZE) ?: return false
    digest.update(header)

    val regionCode = readBytes(dataSource, WII_REGION_CODE_OFFSET, WII_REGION_CODE_SIZE) ?: return false
    digest.update(regionCode)

    val partitions = readWiiPartitions(dataSource)
    if (partitions.isEmpty()) {
        logInfo(TAG, "No Wii partitions found")
        return false
    }

    val nonUpdatePartitions = partitions.filter { it.type != WII_PARTITION_TYPE_UPDATE }
    if (nonUpdatePartitions.isEmpty()) {
        logInfo(TAG, "Only Wii update partitions present")
        return false
    }

    for (partition in nonUpdatePartitions) {
        val tmdSize = readBigEndianInt(dataSource, partition.offset + WII_TMD_SIZE_OFFSET)?.toLong()
            ?: return false
        val tmdOffset = readBigEndianInt(dataSource, partition.offset + WII_TMD_OFFSET_OFFSET)?.toLong()?.shl(2)
            ?: return false
        val cappedTmdSize = minOf(tmdSize, WII_HASHED_CLUSTER_DATA_SIZE.toLong()).toInt()
        val tmd = readBytes(dataSource, partition.offset + tmdOffset, cappedTmdSize) ?: return false
        digest.update(tmd)

        val partDataOffset = readBigEndianInt(dataSource, partition.offset + WII_PARTITION_DATA_OFFSET)?.toLong()?.shl(2)
            ?: return false
        val partSize = readBigEndianInt(dataSource, partition.offset + WII_PARTITION_DATA_OFFSET + 4)?.toLong()?.shl(2)
            ?: return false

        if (encrypted) {
            if (!updateEncryptedWiiPartition(digest, dataSource, partDataOffset, partSize)) return false
        } else {
            if (!updateNintendoDiscPartition(digest, dataSource, partition.offset, wiiShift = 2)) return false
        }
    }

    return true
}

private fun updateWiiWad(digest: MessageDigest, dataSource: RomDataSource): Boolean {
    val header = readBytes(dataSource, 0L, 0x40) ?: return false
    if (header[0x04] != 'I'.code.toByte() || header[0x05] != 's'.code.toByte() || header[0x06] != 0.toByte() || header[0x07] != 0.toByte()) {
        logInfo(TAG, "WAD magic mismatch")
        return false
    }

    val certificateSize = readBigEndianInt(header, 0x08)?.toLong() ?: return false
    val ticketSize = readBigEndianInt(header, 0x10)?.toLong() ?: return false
    val tmdSize = readBigEndianInt(header, 0x14)?.toLong() ?: return false
    readBigEndianInt(header, 0x18)?.toLong() ?: return false

    val alignedCertificateSize = align64(certificateSize)
    val alignedTicketSize = align64(ticketSize)
    val alignedTmdSize = align64(tmdSize)
    val tmdStart = 0x40L + alignedCertificateSize + alignedTicketSize
    val cappedTmdSize = minOf(alignedTmdSize, MAX_HASH_BUFFER_SIZE.toLong()).toInt()
    val tmd = readBytes(dataSource, tmdStart, cappedTmdSize) ?: return false

    digest.update(tmd)

    val contentCount = readBigEndianShort(dataSource, tmdStart + 0x1DEL) ?: return false
    var contentOffset = tmdStart + alignedTmdSize
    repeat(contentCount) { index ->
        val sizeOffset = tmdStart + 0x1E4L + 8 + index * 0x24L
        val highSize = readBigEndianInt(dataSource, sizeOffset) ?: return false
        val contentSize = if (highSize == 0) {
            val lowSize = readBigEndianInt(dataSource, sizeOffset + 4) ?: return false
            ((lowSize.toLong() + 0x0F) and 0x0F.inv().toLong())
        } else {
            MAX_HASH_BUFFER_SIZE.toLong()
        }
        val bytesToHash = minOf(contentSize, MAX_HASH_BUFFER_SIZE.toLong()).toInt()
        val content = readBytes(dataSource, contentOffset, bytesToHash) ?: return false
        digest.update(content)
        contentOffset = align64(contentOffset + contentSize)
    }

    return true
}

private fun updateNintendoDiscPartition(
    digest: MessageDigest,
    dataSource: RomDataSource,
    partitionOffset: Long,
    wiiShift: Int
): Boolean {
    val apploaderBodySize = readBigEndianInt(dataSource, partitionOffset + NINTENDO_APPLOADER_SIZE_OFFSET.toLong())
        ?: return false
    val apploaderTrailerSize = readBigEndianInt(dataSource, partitionOffset + NINTENDO_APPLOADER_SIZE_OFFSET.toLong() + 4)
        ?: return false
    var headerSize = NINTENDO_DISC_BASE_HEADER_SIZE + NINTENDO_APPLOADER_HEADER_SIZE + apploaderBodySize + apploaderTrailerSize
    if (headerSize > MAX_DISC_HEADER_SIZE) {
        headerSize = MAX_DISC_HEADER_SIZE
    }

    val partitionHeader = readBytes(dataSource, partitionOffset, headerSize) ?: return false
    digest.update(partitionHeader)

    val dolOffset = readBigEndianInt(partitionHeader, WII_DOL_OFFSET_OFFSET)?.toLong() ?: return false
    val dolAbsoluteOffset = partitionOffset + (dolOffset shl wiiShift)
    val dolHeader = readBytes(dataSource, dolAbsoluteOffset, DOL_TABLE_SIZE) ?: return false
    val sections = readDolSections(dolHeader, partitionOffset, wiiShift)
    for (section in sections) {
        if (section.size <= 0L) continue
        var remaining = section.size
        var sectionOffset = section.absoluteOffset
        while (remaining > MAX_DISC_CHUNK_SIZE) {
            val chunk = readBytes(dataSource, sectionOffset, MAX_DISC_CHUNK_SIZE) ?: return false
            digest.update(chunk)
            sectionOffset += MAX_DISC_CHUNK_SIZE.toLong()
            remaining -= MAX_DISC_CHUNK_SIZE.toLong()
        }
        val tail = readBytes(dataSource, sectionOffset, remaining.toInt()) ?: return false
        digest.update(tail)
    }

    return true
}

private fun updateEncryptedWiiPartition(
    digest: MessageDigest,
    dataSource: RomDataSource,
    partitionDataOffset: Long,
    partitionSize: Long
): Boolean {
    var clusterOffset = partitionDataOffset
    val clusterCount = minOf((partitionSize / WII_CLUSTER_PHYSICAL_SIZE).toInt(), WII_MAX_ENCRYPTED_CLUSTERS)
    repeat(clusterCount) {
        val cluster = readBytes(dataSource, clusterOffset + WII_CLUSTER_HASH_HEADER_SIZE, WII_HASHED_CLUSTER_DATA_SIZE) ?: return@repeat
        digest.update(cluster)
        clusterOffset += WII_CLUSTER_PHYSICAL_SIZE
    }
    return true
}

private fun readWiiPartitions(dataSource: RomDataSource): List<NintendoPartitionEntry> {
    val groups = listOf(0x40000L, 0x40008L, 0x40010L, 0x40018L)
    val partitions = mutableListOf<NintendoPartitionEntry>()

    for (groupOffset in groups) {
        val count = readBigEndianInt(dataSource, groupOffset)?.takeIf { it > 0 } ?: continue
        val tableOffset = readBigEndianInt(dataSource, groupOffset + 4)?.toLong()?.shl(2) ?: continue
        for (index in 0 until count) {
            val entryOffset = tableOffset + index * 8L
            val partitionOffset = readBigEndianInt(dataSource, entryOffset)?.toLong()?.shl(2) ?: continue
            val partitionType = readBigEndianInt(dataSource, entryOffset + 4) ?: continue
            partitions += NintendoPartitionEntry(partitionOffset, partitionType)
        }
    }

    return partitions.sortedBy { it.offset }
}

private fun readDolSections(header: ByteArray, partitionOffset: Long, wiiShift: Int): List<NintendoDolSection> =
    buildList {
        repeat(DOL_SECTION_COUNT) { index ->
            val offset = readBigEndianInt(header, DOL_SECTION_OFFSET_TABLE + index * 4)?.toLong()?.shl(wiiShift) ?: 0L
            val size = readBigEndianInt(header, DOL_SECTION_SIZE_TABLE + index * 4)?.toLong()?.shl(wiiShift) ?: 0L
            if (offset > 0L && size > 0L) {
                add(NintendoDolSection(partitionOffset + offset, size))
            }
        }
    }

internal fun readBytes(dataSource: RomDataSource, offset: Long, size: Int): ByteArray? {
    if (size < 0) return null
    val buffer = ByteArray(size)
    var totalRead = 0
    while (totalRead < size) {
        val chunk = ByteArray(minOf(8192, size - totalRead))
        val read = dataSource.read(offset + totalRead, chunk, chunk.size)
        if (read <= 0) return null
        chunk.copyInto(buffer, destinationOffset = totalRead, endIndex = read)
        totalRead += read
    }
    return buffer
}

private fun readBigEndianInt(dataSource: RomDataSource, offset: Long): Int? =
    readBytes(dataSource, offset, 4)?.let(::readBigEndianInt)

internal fun readBigEndianInt(bytes: ByteArray, offset: Int = 0): Int? {
    if (offset + 4 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

private fun readBigEndianShort(dataSource: RomDataSource, offset: Long): Int? {
    val bytes = readBytes(dataSource, offset, 2) ?: return null
    return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
}

private fun align64(value: Long): Long = (value + 63) and 63.inv().toLong()
