package com.raofflineproxy.proxy.hash

import com.github.luben.zstd.Zstd
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "RAProxy/RvzDataSource"
private val RVZ_MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 0x01)
private const val RVZ_HEADER_1_SIZE = 0x48
private const val RVZ_HEADER_2_MIN_SIZE = 0xD5
private const val RVZ_DISC_TYPE_GAMECUBE = 1
private const val RVZ_DISC_TYPE_WII = 2
private const val RVZ_COMPRESSION_NONE = 0
private const val RVZ_COMPRESSION_ZSTD = 5
private const val RVZ_DISC_HEADER_SIZE = 0x80
private const val RVZ_PARTITION_ENTRY_SIZE = 0x30
private const val RVZ_PARTITION_DATA_ENTRY_SIZE = 0x10
private const val RVZ_RAW_DATA_ENTRY_SIZE = 0x18
private const val RVZ_GROUP_ENTRY_SIZE = 0x0C
private const val RVZ_HASH_EXCEPTION_ENTRY_SIZE = 0x16
private const val RVZ_GROUP_COMPRESSED_BIT = 0x8000_0000.toInt()
private const val RVZ_GROUP_SIZE_MASK = 0x7FFF_FFFF
private const val RVZ_SECTOR_SIZE = 0x8000L
private const val RVZ_SEED_SIZE = 68
private const val RVZ_HEADER_1_HASH_END = 0x34
private const val WII_CLUSTER_HEADER_SIZE = 0x400
private const val WII_CLUSTER_DATA_SIZE = 0x7C00
private const val WII_CLUSTER_TOTAL_SIZE = WII_CLUSTER_HEADER_SIZE + WII_CLUSTER_DATA_SIZE
private const val WII_GROUP_BLOCK_COUNT = 64
private const val WII_GROUP_DATA_SIZE = WII_CLUSTER_DATA_SIZE * WII_GROUP_BLOCK_COUNT
private const val WII_GROUP_TOTAL_SIZE = WII_CLUSTER_TOTAL_SIZE * WII_GROUP_BLOCK_COUNT
private const val WII_HASH_HEADER_IV_OFFSET = 0x3D0
private const val WII_EXCEPTION_LIST_COUNT_OFFSET = 0x90
private const val WII_EXCEPTION_LIST_ENTRY_SIZE_OFFSET = 0x94
private const val WII_EXCEPTION_LIST_OFFSET_OFFSET = 0x98
private const val WII_EXCEPTION_LIST_HASH_OFFSET = 0xA0
private const val RVZ_RAW_ENTRY_COUNT_OFFSET = 0xB4
private const val RVZ_RAW_ENTRY_OFFSET_OFFSET = 0xB8
private const val RVZ_RAW_ENTRY_SIZE_OFFSET = 0xC0
private const val RVZ_GROUP_ENTRY_COUNT_OFFSET = 0xC4
private const val RVZ_GROUP_ENTRY_OFFSET_OFFSET = 0xC8
private const val RVZ_GROUP_ENTRY_SIZE_OFFSET = 0xD0
private val ZERO_IV = IvParameterSpec(ByteArray(16))

internal enum class RvzDiscType {
    GAMECUBE,
    WII
}

internal data class RvzMetadata(
    val discType: RvzDiscType,
    val isoFileSize: Long
)

private data class RvzWiiPartition(
    val key: ByteArray,
    val dataEntries: List<RvzWiiPartitionDataEntry>,
    val firstSector: Int,
    val totalSectors: Int
) {
    val rawDataOffset: Long = firstSector.toLong() * RVZ_SECTOR_SIZE
    val decryptedSize: Long = totalSectors.toLong() * WII_CLUSTER_DATA_SIZE
    val groupCache = mutableMapOf<Long, ByteArray>()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RvzWiiPartition

        if (firstSector != other.firstSector) return false
        if (totalSectors != other.totalSectors) return false
        if (rawDataOffset != other.rawDataOffset) return false
        if (decryptedSize != other.decryptedSize) return false
        if (!key.contentEquals(other.key)) return false
        if (dataEntries != other.dataEntries) return false
        if (groupCache != other.groupCache) return false

        return true
    }

    override fun hashCode(): Int {
        var result = firstSector
        result = 31 * result + totalSectors
        result = 31 * result + rawDataOffset.hashCode()
        result = 31 * result + decryptedSize.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + dataEntries.hashCode()
        result = 31 * result + groupCache.hashCode()
        return result
    }
}

private data class RvzWiiPartitionDataEntry(
    val firstSector: Int,
    val numberOfSectors: Int,
    val groupIndex: Int,
    val groupCount: Int
) {
}

private data class RvzHashException(
    val offset: Int,
    val hash: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RvzHashException

        if (offset != other.offset) return false
        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

private data class RvzPartitionGroup(
    val mainData: ByteArray,
    val exceptionLists: List<List<RvzHashException>>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RvzPartitionGroup

        if (!mainData.contentEquals(other.mainData)) return false
        if (exceptionLists != other.exceptionLists) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mainData.contentHashCode()
        result = 31 * result + exceptionLists.hashCode()
        return result
    }
}

internal class RvzRomDataSource private constructor(
    private val delegate: RomDataSource,
    private val metadata: RvzMetadata,
    private val discHeader: ByteArray,
    private val chunkSize: Int,
    private val fileCompressionType: Int,
    private val rawEntries: List<RvzRawDataEntry>,
    private val groupEntries: List<RvzGroupEntry>,
    private val wiiPartitions: List<RvzWiiPartition>
) : RomDataSource {
    override val length: Long
        get() = metadata.isoFileSize

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) return -1
        val targetLength = minOf(length, buffer.size, (this.length - offset).toInt())
        var totalRead = 0
        var currentOffset = offset

        if (currentOffset < RVZ_DISC_HEADER_SIZE) {
            val headerOffset = currentOffset.toInt()
            val headerCount = minOf(targetLength, RVZ_DISC_HEADER_SIZE - headerOffset)
            discHeader.copyInto(
                destination = buffer,
                destinationOffset = totalRead,
                startIndex = headerOffset,
                endIndex = headerOffset + headerCount
            )
            totalRead += headerCount
            currentOffset += headerCount
        }

        while (totalRead < targetLength) {
            val partition = wiiPartitions.firstOrNull { currentOffset in it.rawDataOffset until (it.rawDataOffset + it.totalSectors.toLong() * RVZ_SECTOR_SIZE) }
            if (partition != null) {
                val bytesFromPartition = readWiiPartitionBytes(
                    partition = partition,
                    offset = currentOffset,
                    destination = buffer,
                    destinationOffset = totalRead,
                    maxLength = targetLength - totalRead
                )
                if (bytesFromPartition <= 0) break
                totalRead += bytesFromPartition
                currentOffset += bytesFromPartition
                continue
            }

            val entry = rawEntries.firstOrNull { currentOffset in it.logicalStart until it.logicalEnd } ?: break
            val entryOffset = currentOffset - entry.logicalStart
            val groupIndex = (entryOffset / chunkSize.toLong()).toInt()
            if (groupIndex !in 0 until entry.groupCount) break

            val groupLogicalStart = entry.logicalStart + groupIndex * chunkSize.toLong()
            val groupLogicalSize = minOf(chunkSize.toLong(), entry.logicalEnd - groupLogicalStart).toInt()
            val groupData = readGroup(entry.groupIndex + groupIndex, groupLogicalSize, groupLogicalStart)
            val offsetInGroup = (currentOffset - groupLogicalStart).toInt()
            val bytesFromGroup = minOf(groupLogicalSize - offsetInGroup, targetLength - totalRead)
            groupData.copyInto(
                destination = buffer,
                destinationOffset = totalRead,
                startIndex = offsetInGroup,
                endIndex = offsetInGroup + bytesFromGroup
            )

            totalRead += bytesFromGroup
            currentOffset += bytesFromGroup
        }

        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        delegate.close()
    }

    internal fun metadata(): RvzMetadata = metadata

    private fun readWiiPartitionBytes(
        partition: RvzWiiPartition,
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        maxLength: Int
    ): Int {
        val partitionEnd = partition.rawDataOffset + partition.totalSectors.toLong() * RVZ_SECTOR_SIZE
        val targetEnd = minOf(offset + maxLength, partitionEnd)
        var currentOffset = offset
        var totalRead = 0
        while (currentOffset < targetEnd) {
            val groupStart = (((currentOffset - partition.rawDataOffset) / WII_GROUP_TOTAL_SIZE) * WII_GROUP_DATA_SIZE)
            val encryptedGroup = getEncryptedWiiGroup(partition, groupStart) ?: return if (totalRead > 0) totalRead else -1
            val groupRawStart = partition.rawDataOffset + (groupStart / WII_CLUSTER_DATA_SIZE) * RVZ_SECTOR_SIZE
            val offsetInGroup = (currentOffset - groupRawStart).toInt()
            val bytesFromGroup = minOf(encryptedGroup.size - offsetInGroup, (targetEnd - currentOffset).toInt())
            encryptedGroup.copyInto(
                destination = destination,
                destinationOffset = destinationOffset + totalRead,
                startIndex = offsetInGroup,
                endIndex = offsetInGroup + bytesFromGroup
            )
            totalRead += bytesFromGroup
            currentOffset += bytesFromGroup
        }
        return totalRead
    }

    private fun getEncryptedWiiGroup(partition: RvzWiiPartition, groupStart: Long): ByteArray? {
        partition.groupCache[groupStart]?.let { return it }

        val decryptedBlocks = Array(WII_GROUP_BLOCK_COUNT) { ByteArray(WII_CLUSTER_DATA_SIZE) }
        for (blockIndex in 0 until WII_GROUP_BLOCK_COUNT) {
            val blockOffset = groupStart + blockIndex * WII_CLUSTER_DATA_SIZE.toLong()
            if (blockOffset >= partition.decryptedSize) {
                decryptedBlocks[blockIndex].fill(0)
                continue
            }
            if (!readWiiPartitionDecrypted(partition, blockOffset, decryptedBlocks[blockIndex])) {
                return null
            }
        }

        val hashBlocks = buildWiiHashBlocks(decryptedBlocks)
        applyWiiHashExceptions(hashBlocks, collectWiiHashExceptions(partition, groupStart))

        val encrypted = ByteArray(WII_GROUP_TOTAL_SIZE)
        val keySpec = SecretKeySpec(partition.key, "AES")
        for (blockIndex in 0 until WII_GROUP_BLOCK_COUNT) {
            val headerBytes = hashBlocks[blockIndex]
            val encryptedHeader = aesEncryptCbc(keySpec, ZERO_IV, headerBytes)
            val encryptedData = aesEncryptCbc(
                keySpec,
                IvParameterSpec(encryptedHeader.copyOfRange(WII_HASH_HEADER_IV_OFFSET, WII_HASH_HEADER_IV_OFFSET + 16)),
                decryptedBlocks[blockIndex]
            )
            val outputOffset = blockIndex * WII_CLUSTER_TOTAL_SIZE
            encryptedHeader.copyInto(encrypted, destinationOffset = outputOffset)
            encryptedData.copyInto(encrypted, destinationOffset = outputOffset + WII_CLUSTER_HEADER_SIZE)
        }

        partition.groupCache[groupStart] = encrypted
        return encrypted
    }

    private fun readWiiPartitionDecrypted(partition: RvzWiiPartition, offset: Long, destination: ByteArray): Boolean {
        require(destination.size == WII_CLUSTER_DATA_SIZE)
        for (entry in partition.dataEntries) {
            val entryOffset = (entry.firstSector - partition.firstSector).toLong() * WII_CLUSTER_DATA_SIZE
            val entrySize = entry.numberOfSectors.toLong() * WII_CLUSTER_DATA_SIZE
            if (offset !in entryOffset until (entryOffset + entrySize)) continue
            val chunkSize = chunkSize.toLong() * WII_CLUSTER_DATA_SIZE / RVZ_SECTOR_SIZE
            val chunkIndex = ((offset - entryOffset) / chunkSize).toInt()
            if (chunkIndex !in 0 until entry.groupCount) return false
            val chunkStart = entryOffset + chunkIndex * chunkSize
            val chunkLogicalSize = minOf(chunkSize, entryOffset + entrySize - chunkStart).toInt()
            val exceptionListCount = maxOf(1, (chunkSize / WII_GROUP_DATA_SIZE).toInt())
            val group = readPartitionGroup(
                groupIndex = entry.groupIndex + chunkIndex,
                logicalSize = chunkLogicalSize,
                groupLogicalStart = chunkStart,
                exceptionListCount = exceptionListCount
            ) ?: return false
            val offsetInChunk = (offset - chunkStart).toInt()
            if (offsetInChunk + destination.size > group.mainData.size) return false
            group.mainData.copyInto(destination, startIndex = offsetInChunk, endIndex = offsetInChunk + destination.size)
            return true
        }
        return false
    }

    private fun collectWiiHashExceptions(partition: RvzWiiPartition, groupStart: Long): List<RvzHashException> {
        val exceptions = mutableListOf<RvzHashException>()
        val groupEnd = groupStart + WII_GROUP_DATA_SIZE
        val chunkSize = chunkSize.toLong() * WII_CLUSTER_DATA_SIZE / RVZ_SECTOR_SIZE
        for (entry in partition.dataEntries) {
            val entryOffset = (entry.firstSector - partition.firstSector).toLong() * WII_CLUSTER_DATA_SIZE
            val entrySize = entry.numberOfSectors.toLong() * WII_CLUSTER_DATA_SIZE
            val overlapStart = maxOf(entryOffset, groupStart)
            val overlapEnd = minOf(entryOffset + entrySize, groupEnd)
            if (overlapStart >= overlapEnd) continue

            val firstChunk = ((overlapStart - entryOffset) / chunkSize).toInt()
            val lastChunk = ((overlapEnd - entryOffset - 1) / chunkSize).toInt()
            for (chunkIndex in firstChunk..lastChunk) {
                if (chunkIndex !in 0 until entry.groupCount) continue
                val chunkStart = entryOffset + chunkIndex * chunkSize
                val chunkLogicalSize = minOf(chunkSize, entryOffset + entrySize - chunkStart).toInt()
                val exceptionListCount = maxOf(1, (chunkSize / WII_GROUP_DATA_SIZE).toInt())
                val partitionGroup = readPartitionGroup(
                    groupIndex = entry.groupIndex + chunkIndex,
                    logicalSize = chunkLogicalSize,
                    groupLogicalStart = chunkStart,
                    exceptionListCount = exceptionListCount
                ) ?: continue

                val exceptionListIndex = if (exceptionListCount == 1) 0 else ((groupStart - chunkStart) / WII_GROUP_DATA_SIZE).toInt()
                if (exceptionListIndex !in partitionGroup.exceptionLists.indices) continue
                val additionalOffset = (((chunkStart % WII_GROUP_DATA_SIZE) / WII_CLUSTER_DATA_SIZE) * WII_CLUSTER_HEADER_SIZE).toInt()
                partitionGroup.exceptionLists[exceptionListIndex].forEach { exception ->
                    exceptions += exception.copy(offset = exception.offset + additionalOffset)
                }
            }
        }
        return exceptions
    }

    private fun readPartitionGroup(
        groupIndex: Int,
        logicalSize: Int,
        groupLogicalStart: Long,
        exceptionListCount: Int
    ): RvzPartitionGroup? {
        val groupEntry = groupEntries[groupIndex]
        val storedSize = groupEntry.dataSize and RVZ_GROUP_SIZE_MASK
        if (storedSize == 0) {
            return RvzPartitionGroup(
                mainData = ByteArray(logicalSize),
                exceptionLists = List(exceptionListCount) { emptyList() }
            )
        }

        val encoded = readExact(groupEntry.dataOffset shl 2, storedSize)
        val usesCompression = groupEntry.usesFileCompression
        val decompressed = if (usesCompression) {
            decompressUnknownSize(encoded)
        } else {
            encoded
        }

        val (exceptionLists, mainDataOffset) = parseExceptionLists(
            data = decompressed,
            exceptionListCount = exceptionListCount,
            alignLast = !usesCompression
        )
        val packedMainData = decompressed.copyOfRange(mainDataOffset, decompressed.size)
        val mainData = if (groupEntry.packedSize > 0) {
            decodeRvzPacked(packedMainData.copyOf(groupEntry.packedSize), logicalSize, groupLogicalStart)
        } else {
            if (packedMainData.size < logicalSize) return null
            packedMainData.copyOf(logicalSize)
        }

        return RvzPartitionGroup(mainData = mainData, exceptionLists = exceptionLists)
    }

    private fun readGroup(groupIndex: Int, logicalSize: Int, groupLogicalStart: Long): ByteArray {
        val groupEntry = groupEntries[groupIndex]
        val storedSize = groupEntry.dataSize and RVZ_GROUP_SIZE_MASK
        if (storedSize == 0) {
            return ByteArray(logicalSize)
        }

        val encoded = readExact(groupEntry.dataOffset shl 2, storedSize)
        val decompressed = when {
            groupEntry.usesFileCompression -> decompress(encoded, fileCompressionType, groupEntry.packedSize.takeIf { it > 0 } ?: logicalSize)
            else -> encoded
        }

        return if (groupEntry.packedSize > 0) {
            decodeRvzPacked(decompressed, logicalSize, groupLogicalStart)
        } else {
            if (decompressed.size < logicalSize) {
                throw IllegalArgumentException("RVZ group is shorter than expected logical size")
            }
            decompressed.copyOf(logicalSize)
        }
    }

    private fun decompress(encoded: ByteArray, compressionType: Int, expectedSize: Int): ByteArray = when (compressionType) {
        RVZ_COMPRESSION_NONE -> encoded
        RVZ_COMPRESSION_ZSTD -> {
            val result = ByteArray(expectedSize)
            val decodedSize = Zstd.decompressByteArray(result, 0, result.size, encoded, 0, encoded.size)
            if (Zstd.isError(decodedSize)) {
                throw IllegalArgumentException("RVZ zstd decompress failed: ${Zstd.getErrorName(decodedSize)}")
            }
            if (decodedSize < 0 || decodedSize.toInt() != expectedSize) {
                throw IllegalArgumentException("RVZ zstd decompress produced unexpected size=$decodedSize expected=$expectedSize")
            }
            result
        }

        else -> throw IllegalArgumentException("Unsupported RVZ compression type=$compressionType")
    }

    private fun readExact(offset: Long, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val chunk = ByteArray(size - totalRead)
            val read = delegate.read(offset + totalRead, chunk, chunk.size)
            if (read <= 0) {
                throw IllegalArgumentException("Unexpected EOF while reading RVZ data")
            }
            chunk.copyInto(buffer, destinationOffset = totalRead, endIndex = read)
            totalRead += read
        }
        return buffer
    }

    private data class RvzRawDataEntry(
        val logicalStart: Long,
        val logicalEnd: Long,
        val groupIndex: Int,
        val groupCount: Int
    )

    private data class RvzGroupEntry(
        val dataOffset: Long,
        val dataSize: Int,
        val packedSize: Int
    ) {
        val usesFileCompression: Boolean
            get() = dataSize and RVZ_GROUP_COMPRESSED_BIT != 0
    }

    companion object {
        internal fun open(openDataSource: () -> RomDataSource?): RvzRomDataSource? {
            val delegate = openDataSource() ?: return null
            return try {
                parse(delegate)
            } catch (error: Throwable) {
                logWarn(TAG, "Failed to open RVZ: ${error.message}")
                delegate.close()
                null
            }
        }

        private fun parse(delegate: RomDataSource): RvzRomDataSource {
            val header1 = readFully(delegate, 0L, RVZ_HEADER_1_SIZE)
            require(header1.copyOfRange(0, 4).contentEquals(RVZ_MAGIC)) { "RVZ magic mismatch" }

            val header2Size = readBigEndianInt(header1, 0x0C) ?: throw IllegalArgumentException("Missing RVZ header2 size")
            require(header2Size >= RVZ_HEADER_2_MIN_SIZE) { "RVZ header2 too small" }
            val isoFileSize = readBigEndianLong(header1, 0x24) ?: throw IllegalArgumentException("Missing RVZ iso size")
            require(isoFileSize >= RVZ_DISC_HEADER_SIZE) { "RVZ iso size too small" }

            val header1Digest = MessageDigest.getInstance("SHA-1").digest(header1.copyOfRange(0, RVZ_HEADER_1_HASH_END))
            require(header1Digest.contentEquals(header1.copyOfRange(RVZ_HEADER_1_HASH_END, RVZ_HEADER_1_SIZE))) { "RVZ header1 SHA-1 mismatch" }

            val header2 = readFully(delegate, RVZ_HEADER_1_SIZE.toLong(), header2Size)
            val header2ExpectedHash = header1.copyOfRange(0x10, 0x24)
            val header2Digest = MessageDigest.getInstance("SHA-1").digest(header2)
            require(header2Digest.contentEquals(header2ExpectedHash)) { "RVZ header2 SHA-1 mismatch" }

            val discType = when (readBigEndianInt(header2, 0x00)) {
                RVZ_DISC_TYPE_GAMECUBE -> RvzDiscType.GAMECUBE
                RVZ_DISC_TYPE_WII -> RvzDiscType.WII
                else -> throw IllegalArgumentException("Unsupported RVZ disc type")
            }
            val compressionType = readBigEndianInt(header2, 0x04) ?: throw IllegalArgumentException("Missing RVZ compression type")
            require(compressionType == RVZ_COMPRESSION_NONE || compressionType == RVZ_COMPRESSION_ZSTD) {
                "Unsupported RVZ compression type=$compressionType"
            }
            val chunkSize = readBigEndianInt(header2, 0x0C) ?: throw IllegalArgumentException("Missing RVZ chunk size")
            require(chunkSize > 0) { "Invalid RVZ chunk size" }
            val discHeader = header2.copyOfRange(0x10, 0x10 + RVZ_DISC_HEADER_SIZE)
            val partitionEntryCount = readBigEndianInt(header2, WII_EXCEPTION_LIST_COUNT_OFFSET) ?: throw IllegalArgumentException("Missing RVZ partition entry count")
            val partitionEntrySize = readBigEndianInt(header2, WII_EXCEPTION_LIST_ENTRY_SIZE_OFFSET) ?: throw IllegalArgumentException("Missing RVZ partition entry size")
            val partitionEntriesOffset = readBigEndianLong(header2, WII_EXCEPTION_LIST_OFFSET_OFFSET) ?: throw IllegalArgumentException("Missing RVZ partition entry offset")
            val partitionEntriesHash = header2.copyOfRange(WII_EXCEPTION_LIST_HASH_OFFSET, WII_EXCEPTION_LIST_HASH_OFFSET + 20)
            val rawEntryCount = readBigEndianInt(header2, RVZ_RAW_ENTRY_COUNT_OFFSET) ?: throw IllegalArgumentException("Missing RVZ raw entry count")
            val rawEntriesOffset = readBigEndianLong(header2, RVZ_RAW_ENTRY_OFFSET_OFFSET) ?: throw IllegalArgumentException("Missing RVZ raw entry offset")
            val rawEntriesSize = readBigEndianInt(header2, RVZ_RAW_ENTRY_SIZE_OFFSET) ?: throw IllegalArgumentException("Missing RVZ raw entry size")
            val groupEntryCount = readBigEndianInt(header2, RVZ_GROUP_ENTRY_COUNT_OFFSET) ?: throw IllegalArgumentException("Missing RVZ group entry count")
            val groupEntriesOffset = readBigEndianLong(header2, RVZ_GROUP_ENTRY_OFFSET_OFFSET) ?: throw IllegalArgumentException("Missing RVZ group entry offset")
            val groupEntriesSize = readBigEndianInt(header2, RVZ_GROUP_ENTRY_SIZE_OFFSET) ?: throw IllegalArgumentException("Missing RVZ group entry size")

            val wiiPartitions = if (discType == RvzDiscType.WII && partitionEntryCount > 0) {
                val partitionEntriesBytes = readFully(delegate, partitionEntriesOffset, partitionEntryCount * partitionEntrySize)
                val partitionEntriesDigest = MessageDigest.getInstance("SHA-1").digest(partitionEntriesBytes)
                require(partitionEntriesDigest.contentEquals(partitionEntriesHash)) { "RVZ partition entries SHA-1 mismatch" }
                parsePartitionEntries(partitionEntriesBytes, partitionEntryCount, partitionEntrySize)
            } else {
                emptyList()
            }

            val rawEntriesBytes = readMetadataBlock(delegate, rawEntriesOffset, rawEntriesSize, compressionType, rawEntryCount * RVZ_RAW_DATA_ENTRY_SIZE)
            val groupEntriesBytes = readMetadataBlock(delegate, groupEntriesOffset, groupEntriesSize, compressionType, groupEntryCount * RVZ_GROUP_ENTRY_SIZE)

            val rawEntries = parseRawEntries(rawEntriesBytes, rawEntryCount)
            val groupEntries = parseGroupEntries(groupEntriesBytes, groupEntryCount)

            return RvzRomDataSource(
                delegate = delegate,
                metadata = RvzMetadata(discType = discType, isoFileSize = isoFileSize),
                discHeader = discHeader,
                chunkSize = chunkSize,
                fileCompressionType = compressionType,
                rawEntries = rawEntries,
                groupEntries = groupEntries,
                wiiPartitions = wiiPartitions
            )
        }

        private fun parsePartitionEntries(bytes: ByteArray, count: Int, entrySize: Int): List<RvzWiiPartition> =
            List(count) { index ->
                val offset = index * entrySize
                val entry = bytes.copyOfRange(offset, offset + entrySize).copyOf(RVZ_PARTITION_ENTRY_SIZE)
                val dataEntries = List(2) { entryIndex ->
                    val entryOffset = 16 + entryIndex * RVZ_PARTITION_DATA_ENTRY_SIZE
                    RvzWiiPartitionDataEntry(
                        firstSector = readBigEndianInt(entry, entryOffset) ?: 0,
                        numberOfSectors = readBigEndianInt(entry, entryOffset + 4) ?: 0,
                        groupIndex = readBigEndianInt(entry, entryOffset + 8) ?: 0,
                        groupCount = readBigEndianInt(entry, entryOffset + 12) ?: 0
                    )
                }.filter { it.numberOfSectors > 0 }
                val firstSector = dataEntries.firstOrNull()?.firstSector ?: 0
                val totalSectors = if (dataEntries.size > 1) {
                    (dataEntries[1].firstSector - firstSector) + dataEntries[1].numberOfSectors
                } else {
                    dataEntries.firstOrNull()?.numberOfSectors ?: 0
                }
                RvzWiiPartition(
                    key = entry.copyOfRange(0, 16),
                    dataEntries = dataEntries,
                    firstSector = firstSector,
                    totalSectors = totalSectors
                )
            }.filter { it.dataEntries.isNotEmpty() }

        private fun readMetadataBlock(
            delegate: RomDataSource,
            offset: Long,
            compressedSize: Int,
            compressionType: Int,
            expectedSize: Int
        ): ByteArray {
            val encoded = readFully(delegate, offset, compressedSize)
            return when (compressionType) {
                RVZ_COMPRESSION_NONE -> encoded
                RVZ_COMPRESSION_ZSTD -> {
                    val result = ByteArray(expectedSize)
                    val decodedSize = Zstd.decompressByteArray(result, 0, result.size, encoded, 0, encoded.size)
                    if (Zstd.isError(decodedSize) || decodedSize.toInt() != expectedSize) {
                        throw IllegalArgumentException("RVZ metadata decompress failed")
                    }
                    result
                }

                else -> throw IllegalArgumentException("Unsupported RVZ metadata compression type=$compressionType")
            }
        }

        private fun parseRawEntries(bytes: ByteArray, count: Int): List<RvzRawDataEntry> =
            List(count) { index ->
                val offset = index * RVZ_RAW_DATA_ENTRY_SIZE
                val rawOffset = readBigEndianLong(bytes, offset) ?: throw IllegalArgumentException("Invalid RVZ raw entry offset")
                val rawSize = readBigEndianLong(bytes, offset + 8) ?: throw IllegalArgumentException("Invalid RVZ raw entry size")
                val groupIndex = readBigEndianInt(bytes, offset + 16) ?: throw IllegalArgumentException("Invalid RVZ raw entry group index")
                val groupCount = readBigEndianInt(bytes, offset + 20) ?: throw IllegalArgumentException("Invalid RVZ raw entry group count")
                val skippedData = rawOffset % RVZ_SECTOR_SIZE
                val logicalStart = rawOffset - skippedData
                val logicalEnd = logicalStart + rawSize + skippedData
                RvzRawDataEntry(
                    logicalStart = logicalStart,
                    logicalEnd = logicalEnd,
                    groupIndex = groupIndex,
                    groupCount = groupCount
                )
            }

        private fun parseGroupEntries(bytes: ByteArray, count: Int): List<RvzGroupEntry> =
            List(count) { index ->
                val offset = index * RVZ_GROUP_ENTRY_SIZE
                val dataOffset = readBigEndianInt(bytes, offset)?.toLong() ?: throw IllegalArgumentException("Invalid RVZ group offset")
                val dataSize = readBigEndianInt(bytes, offset + 4) ?: throw IllegalArgumentException("Invalid RVZ group size")
                val packedSize = readBigEndianInt(bytes, offset + 8) ?: throw IllegalArgumentException("Invalid RVZ group packed size")
                RvzGroupEntry(
                    dataOffset = dataOffset,
                    dataSize = dataSize,
                    packedSize = packedSize
                )
            }

        private fun readFully(delegate: RomDataSource, offset: Long, size: Int): ByteArray {
            val buffer = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val chunk = ByteArray(size - totalRead)
                val read = delegate.read(offset + totalRead, chunk, chunk.size)
                if (read <= 0) throw IllegalArgumentException("Unexpected EOF")
                chunk.copyInto(buffer, destinationOffset = totalRead, endIndex = read)
                totalRead += read
            }
            return buffer
        }

        private fun decompressUnknownSize(encoded: ByteArray): ByteArray {
            val expectedSize = Zstd.decompressedSize(encoded).takeIf { it > 0L && it <= Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw IllegalArgumentException("RVZ zstd decompressed size unavailable")
            return decompressZstd(encoded, expectedSize)
        }

        private fun parseExceptionLists(data: ByteArray, exceptionListCount: Int, alignLast: Boolean): Pair<List<List<RvzHashException>>, Int> {
            var offset = 0
            val lists = MutableList(exceptionListCount) { emptyList<RvzHashException>() }
            repeat(exceptionListCount) { index ->
                val count = readBigEndianShort(data, offset) ?: throw IllegalArgumentException("Invalid RVZ exception list count")
                offset += 2
                val list = MutableList(count) {
                    val entryOffset = offset + it * RVZ_HASH_EXCEPTION_ENTRY_SIZE
                    val exceptionOffset = readBigEndianShort(data, entryOffset) ?: throw IllegalArgumentException("Invalid RVZ exception offset")
                    val hash = data.copyOfRange(entryOffset + 2, entryOffset + RVZ_HASH_EXCEPTION_ENTRY_SIZE)
                    RvzHashException(offset = exceptionOffset, hash = hash)
                }
                offset += count * RVZ_HASH_EXCEPTION_ENTRY_SIZE
                if (alignLast && index == exceptionListCount - 1) {
                    offset = alignTo4(offset)
                }
                lists[index] = list
            }
            return lists to offset
        }
    }
}

private fun buildWiiHashBlocks(blocks: Array<ByteArray>): Array<ByteArray> {
    val headers = Array(WII_GROUP_BLOCK_COUNT) { ByteArray(WII_CLUSTER_HEADER_SIZE) }
    val subgroupHashes = Array(8) { ByteArray(20) }
    for (blockIndex in 0 until WII_GROUP_BLOCK_COUNT) {
        val header = headers[blockIndex]
        for (chunkIndex in 0 until 31) {
            val digest = sha1(blocks[blockIndex], chunkIndex * WII_CLUSTER_HEADER_SIZE, WII_CLUSTER_HEADER_SIZE)
            digest.copyInto(header, destinationOffset = chunkIndex * 20)
        }
        val subgroupIndex = blockIndex / 8
        val positionInSubgroup = blockIndex % 8
        sha1(header, 0, 31 * 20).copyInto(headers[subgroupIndex * 8], destinationOffset = 0x280 + positionInSubgroup * 20)
    }
    for (subgroupIndex in 0 until 8) {
        val source = headers[subgroupIndex * 8]
        subgroupHashes[subgroupIndex] = sha1(source, 0x280, 8 * 20)
        for (copyIndex in 1 until 8) {
            source.copyOfRange(0x280, 0x340).copyInto(headers[subgroupIndex * 8 + copyIndex], destinationOffset = 0x280)
        }
    }
    val h2Source = ByteArray(8 * 20)
    for (index in 0 until 8) {
        subgroupHashes[index].copyInto(h2Source, destinationOffset = index * 20)
    }
    val h2Hashes = Array(8) { h2Source.copyOfRange(it * 20, it * 20 + 20) }
    for (blockIndex in 0 until WII_GROUP_BLOCK_COUNT) {
        val header = headers[blockIndex]
        for (hashIndex in 0 until 8) {
            h2Hashes[hashIndex].copyInto(header, destinationOffset = 0x340 + hashIndex * 20)
        }
    }
    return headers
}

private fun applyWiiHashExceptions(hashBlocks: Array<ByteArray>, exceptions: List<RvzHashException>) {
    exceptions.forEach { exception ->
        val blockIndex = exception.offset / WII_CLUSTER_HEADER_SIZE
        val offsetInBlock = exception.offset % WII_CLUSTER_HEADER_SIZE
        if (blockIndex !in hashBlocks.indices || offsetInBlock + exception.hash.size > WII_CLUSTER_HEADER_SIZE) {
            throw IllegalArgumentException("Invalid RVZ Wii hash exception offset=${exception.offset}")
        }
        exception.hash.copyInto(hashBlocks[blockIndex], destinationOffset = offsetInBlock)
    }
}

private fun aesEncryptCbc(key: SecretKeySpec, iv: IvParameterSpec, input: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, iv)
    return cipher.doFinal(input)
}

private fun sha1(data: ByteArray, offset: Int, size: Int): ByteArray =
    MessageDigest.getInstance("SHA-1").digest(data.copyOfRange(offset, offset + size))

private fun readBigEndianShort(bytes: ByteArray, offset: Int): Int? {
    if (offset + 2 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

private fun alignTo4(value: Int): Int = (value + 3) and 3.inv()

private fun decompressZstd(encoded: ByteArray, expectedSize: Int): ByteArray {
    val result = ByteArray(expectedSize)
    val decodedSize = Zstd.decompressByteArray(result, 0, result.size, encoded, 0, encoded.size)
    if (Zstd.isError(decodedSize) || decodedSize.toInt() != expectedSize) {
        throw IllegalArgumentException("RVZ zstd decompress failed")
    }
    return result
}

internal fun decodeRvzPacked(packed: ByteArray, logicalSize: Int, groupLogicalStart: Long): ByteArray {
    val output = ByteArray(logicalSize)
    var inputOffset = 0
    var outputOffset = 0

    while (inputOffset < packed.size && outputOffset < logicalSize) {
        val recordSize = readBigEndianInt(packed, inputOffset) ?: throw IllegalArgumentException("Invalid RVZ packed record")
        inputOffset += 4
        val generated = recordSize < 0
        val size = recordSize and RVZ_GROUP_SIZE_MASK

        if (!generated) {
            if (inputOffset + size > packed.size || outputOffset + size > logicalSize) {
                throw IllegalArgumentException("Invalid RVZ packed copy record")
            }
            packed.copyInto(output, destinationOffset = outputOffset, startIndex = inputOffset, endIndex = inputOffset + size)
            inputOffset += size
            outputOffset += size
            continue
        }

        if (inputOffset + RVZ_SEED_SIZE > packed.size || outputOffset + size > logicalSize) {
            throw IllegalArgumentException("Invalid RVZ packed PRNG record")
        }

        val seed = IntArray(17) { index -> readBigEndianInt(packed, inputOffset + index * 4) ?: 0 }
        inputOffset += RVZ_SEED_SIZE
        val bytes = generateRvzBytes(seed, size, groupLogicalStart + outputOffset)
        bytes.copyInto(output, destinationOffset = outputOffset)
        outputOffset += size
    }

    if (outputOffset != logicalSize) {
        throw IllegalArgumentException("RVZ packed output truncated expected=$logicalSize actual=$outputOffset")
    }

    return output
}

internal fun generateRvzBytes(seed: IntArray, size: Int, absoluteOffset: Long): ByteArray {
    val buffer = IntArray(521)
    for (index in seed.indices) {
        buffer[index] = seed[index]
    }
    for (index in 17 until buffer.size) {
        buffer[index] = (buffer[index - 17] shl 23) xor (buffer[index - 16] ushr 9) xor buffer[index - 1]
    }
    repeat(4) { advanceLaggedFibonacci(buffer) }

    var wordIndex = 0
    val bytesToSkip = (absoluteOffset % RVZ_SECTOR_SIZE).toInt()
    if (bytesToSkip > 0) {
        wordIndex = writeLaggedFibonacciBytes(buffer, ByteArray(bytesToSkip), bytesToSkip, wordIndex)
    }

    val output = ByteArray(size)
    writeLaggedFibonacciBytes(buffer, output, output.size, wordIndex)
    return output
}

private fun writeLaggedFibonacciBytes(
    buffer: IntArray,
    output: ByteArray,
    byteCount: Int,
    initialWordIndex: Int
): Int {
    var wordIndex = initialWordIndex
    var outputOffset = 0
    val outputEnd = byteCount
    while (outputOffset < outputEnd) {
        if (wordIndex == buffer.size) {
            advanceLaggedFibonacci(buffer)
            wordIndex = 0
        }
        val value = buffer[wordIndex++]
        val bytes = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 18).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
        val count = minOf(bytes.size, outputEnd - outputOffset)
        bytes.copyInto(output, destinationOffset = outputOffset, endIndex = count)
        outputOffset += count
    }
    return wordIndex
}

private fun advanceLaggedFibonacci(buffer: IntArray) {
    for (index in 0 until 32) {
        buffer[index] = buffer[index] xor buffer[index + buffer.size - 32]
    }
    for (index in 32 until buffer.size) {
        buffer[index] = buffer[index] xor buffer[index - 32]
    }
}

internal fun readBigEndianLong(bytes: ByteArray, offset: Int = 0): Long? {
    if (offset + 8 > bytes.size) return null
    return ((bytes[offset].toLong() and 0xFF) shl 56) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 48) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 40) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 32) or
        ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
        (bytes[offset + 7].toLong() and 0xFF)
}
