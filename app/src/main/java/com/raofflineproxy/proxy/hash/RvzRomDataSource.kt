package com.raofflineproxy.proxy.hash

import com.github.luben.zstd.Zstd
import java.security.MessageDigest

private const val TAG = "RAProxy/RvzDataSource"
private val RVZ_MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 0x01)
private const val RVZ_HEADER_1_SIZE = 0x48
private const val RVZ_HEADER_2_MIN_SIZE = 0xD5
private const val RVZ_DISC_TYPE_GAMECUBE = 1
private const val RVZ_DISC_TYPE_WII = 2
private const val RVZ_COMPRESSION_NONE = 0
private const val RVZ_COMPRESSION_ZSTD = 5
private const val RVZ_DISC_HEADER_SIZE = 0x80
private const val RVZ_RAW_DATA_ENTRY_SIZE = 0x18
private const val RVZ_GROUP_ENTRY_SIZE = 0x0C
private const val RVZ_GROUP_COMPRESSED_BIT = 0x8000_0000.toInt()
private const val RVZ_GROUP_SIZE_MASK = 0x7FFF_FFFF
private const val RVZ_SECTOR_SIZE = 0x8000L
private const val RVZ_SEED_SIZE = 68
private const val RVZ_HEADER_1_HASH_END = 0x34

internal enum class RvzDiscType {
    GAMECUBE,
    WII
}

internal data class RvzMetadata(
    val discType: RvzDiscType,
    val isoFileSize: Long
)

internal class RvzRomDataSource private constructor(
    private val delegate: RomDataSource,
    private val metadata: RvzMetadata,
    private val discHeader: ByteArray,
    private val chunkSize: Int,
    private val fileCompressionType: Int,
    private val rawEntries: List<RvzRawDataEntry>,
    private val groupEntries: List<RvzGroupEntry>
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

    private fun advanceLaggedFibonacci(buffer: IntArray) {
        for (index in 0 until 32) {
            buffer[index] = buffer[index] xor buffer[index + buffer.size - 32]
        }
        for (index in 32 until buffer.size) {
            buffer[index] = buffer[index] xor buffer[index - 32]
        }
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
            val rawEntryCount = readBigEndianInt(header2, 0xB4) ?: throw IllegalArgumentException("Missing RVZ raw entry count")
            val rawEntriesOffset = readBigEndianLong(header2, 0xB8) ?: throw IllegalArgumentException("Missing RVZ raw entry offset")
            val rawEntriesSize = readBigEndianInt(header2, 0xC0) ?: throw IllegalArgumentException("Missing RVZ raw entry size")
            val groupEntryCount = readBigEndianInt(header2, 0xC4) ?: throw IllegalArgumentException("Missing RVZ group entry count")
            val groupEntriesOffset = readBigEndianLong(header2, 0xC8) ?: throw IllegalArgumentException("Missing RVZ group entry offset")
            val groupEntriesSize = readBigEndianInt(header2, 0xD0) ?: throw IllegalArgumentException("Missing RVZ group entry size")

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
                groupEntries = groupEntries
            )
        }

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
    }
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
        wordIndex = writeLaggedFibonacciBytes(buffer, ByteArray(bytesToSkip), 0, bytesToSkip, wordIndex)
    }

    val output = ByteArray(size)
    writeLaggedFibonacciBytes(buffer, output, 0, output.size, wordIndex)
    return output
}

private fun writeLaggedFibonacciBytes(
    buffer: IntArray,
    output: ByteArray,
    destinationOffset: Int,
    byteCount: Int,
    initialWordIndex: Int
): Int {
    var wordIndex = initialWordIndex
    var outputOffset = destinationOffset
    val outputEnd = destinationOffset + byteCount
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
