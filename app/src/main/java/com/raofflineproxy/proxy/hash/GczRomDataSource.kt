package com.raofflineproxy.proxy.hash

import java.util.zip.Adler32
import java.util.zip.Inflater

private const val TAG = "RAProxy/GczDataSource"
private const val GCZ_MAGIC = 0xB10BC001.toInt()

internal class GczRomDataSource private constructor(
    private val delegate: RomDataSource,
    private val dataSize: Long,
    private val blockSize: Int,
    private val compressedDataSize: Long,
    private val blockPointers: LongArray,
    private val blockHashes: IntArray,
    private val dataOffset: Long
) : RomDataSource {
    private var cachedBlockIndex: Int = -1
    private var cachedBlockData: ByteArray? = null

    override val length: Long
        get() = dataSize

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) return -1
        val remaining = this.length - offset
        val targetLength = minOf(length, buffer.size, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var totalRead = 0
        var currentOffset = offset

        while (totalRead < targetLength) {
            val blockIndex = (currentOffset / blockSize).toInt()
            if (blockIndex !in blockPointers.indices) break
            val offsetInBlock = (currentOffset % blockSize).toInt()
            val bytesToRead = minOf(blockSize - offsetInBlock, targetLength - totalRead)
            val block = readBlock(blockIndex) ?: return if (totalRead > 0) totalRead else -1
            block.copyInto(buffer, destinationOffset = totalRead, startIndex = offsetInBlock, endIndex = offsetInBlock + bytesToRead)
            totalRead += bytesToRead
            currentOffset += bytesToRead
        }

        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        delegate.close()
    }

    private fun readBlock(blockIndex: Int): ByteArray? {
        if (cachedBlockIndex == blockIndex) return cachedBlockData

        val pointer = blockPointers[blockIndex]
        val uncompressed = pointer < 0L
        val start = pointer and Long.MAX_VALUE
        val end = when {
            blockIndex == blockPointers.lastIndex -> compressedDataSize
            else -> blockPointers[blockIndex + 1] and Long.MAX_VALUE
        }
        val compressedBlockSize = (end - start).toInt()
        if (compressedBlockSize <= 0) return null

        val stored = readBytes(delegate, dataOffset + start, compressedBlockSize) ?: return null
        val checksum = Adler32().apply { update(stored) }.value.toInt()
        if (checksum != blockHashes[blockIndex]) {
            logWarn(TAG, "GCZ Adler32 mismatch for block=$blockIndex")
        }

        val block = if (uncompressed) {
            if (stored.size < blockSize) return null
            stored.copyOf(blockSize)
        } else {
            inflateBlock(stored, blockSize) ?: return null
        }

        cachedBlockIndex = blockIndex
        cachedBlockData = block
        return block
    }

    companion object {
        internal fun open(openDataSource: () -> RomDataSource?): GczRomDataSource? {
            val delegate = openDataSource() ?: return null
            return try {
                parse(delegate)
            } catch (error: Throwable) {
                logWarn(TAG, "Failed to open GCZ: ${error.message}")
                delegate.close()
                null
            }
        }

        private fun parse(delegate: RomDataSource): GczRomDataSource {
            val header = readBytes(delegate, 0L, 32) ?: throw IllegalArgumentException("Unexpected EOF while reading GCZ header")
            require(littleEndianInt(header, 0) == GCZ_MAGIC) { "GCZ magic mismatch" }

            val compressedDataSize = littleEndianLong(header, 8)
            val dataSize = littleEndianLong(header, 16)
            val blockSize = littleEndianInt(header, 24)
            val numBlocks = littleEndianInt(header, 28)
            require(compressedDataSize > 0L && dataSize > 0L && blockSize > 0 && numBlocks > 0) { "Invalid GCZ header values" }

            val pointersOffset = 32L
            val pointersSize = numBlocks * 8
            val hashesOffset = pointersOffset + pointersSize
            val hashesSize = numBlocks * 4
            val dataOffset = hashesOffset + hashesSize

            val pointerBytes = readBytes(delegate, pointersOffset, pointersSize) ?: throw IllegalArgumentException("Unexpected EOF while reading GCZ block pointers")
            val hashBytes = readBytes(delegate, hashesOffset, hashesSize) ?: throw IllegalArgumentException("Unexpected EOF while reading GCZ block hashes")
            val blockPointers = LongArray(numBlocks) { index -> littleEndianLong(pointerBytes, index * 8) }
            val blockHashes = IntArray(numBlocks) { index -> littleEndianInt(hashBytes, index * 4) }

            return GczRomDataSource(
                delegate = delegate,
                dataSize = dataSize,
                blockSize = blockSize,
                compressedDataSize = compressedDataSize,
                blockPointers = blockPointers,
                blockHashes = blockHashes,
                dataOffset = dataOffset
            )
        }
    }
}

private fun inflateBlock(input: ByteArray, expectedSize: Int): ByteArray? {
    val inflater = Inflater()
    return try {
        inflater.setInput(input)
        val output = ByteArray(expectedSize)
        val count = inflater.inflate(output)
        if (!inflater.finished() || count != expectedSize) return null
        output
    } finally {
        inflater.end()
    }
}

private fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
    if (offset + 8 > bytes.size) throw IllegalArgumentException("Little-endian long out of range")
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 4].toLong() and 0xFF) shl 32) or
        ((bytes[offset + 5].toLong() and 0xFF) shl 40) or
        ((bytes[offset + 6].toLong() and 0xFF) shl 48) or
        ((bytes[offset + 7].toLong() and 0xFF) shl 56)
}
