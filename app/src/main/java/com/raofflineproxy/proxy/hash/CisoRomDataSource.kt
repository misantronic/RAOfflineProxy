package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/CisoDataSource"
private const val CISO_HEADER_SIZE = 0x8000L
private const val CISO_MAP_SIZE = (CISO_HEADER_SIZE - 8).toInt()
private val CISO_MAGIC = byteArrayOf('C'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte())
private const val UNUSED_CISO_BLOCK = -1

internal class CisoRomDataSource private constructor(
    private val delegate: RomDataSource,
    private val blockSize: Int,
    private val blockMap: IntArray
) : RomDataSource {
    override val length: Long
        get() = blockMap.size.toLong() * blockSize

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) return -1
        val targetLength = minOf(length, buffer.size, (this.length - offset).toInt())
        var totalRead = 0
        var currentOffset = offset

        while (totalRead < targetLength) {
            val blockIndex = (currentOffset / blockSize).toInt()
            if (blockIndex !in blockMap.indices) break
            val offsetInBlock = (currentOffset % blockSize).toInt()
            val bytesToRead = minOf(blockSize - offsetInBlock, targetLength - totalRead)
            val mappedBlock = blockMap[blockIndex]
            if (mappedBlock == UNUSED_CISO_BLOCK) {
                buffer.fill(0, totalRead, totalRead + bytesToRead)
            } else {
                val fileOffset = CISO_HEADER_SIZE + mappedBlock.toLong() * blockSize + offsetInBlock
                val chunk = ByteArray(bytesToRead)
                val read = delegate.read(fileOffset, chunk, chunk.size)
                if (read < bytesToRead) return if (totalRead > 0) totalRead else -1
                chunk.copyInto(buffer, destinationOffset = totalRead)
            }
            totalRead += bytesToRead
            currentOffset += bytesToRead
        }

        return if (totalRead == 0) -1 else totalRead
    }

    override fun close() {
        delegate.close()
    }

    companion object {
        internal fun open(openDataSource: () -> RomDataSource?): CisoRomDataSource? {
            val delegate = openDataSource() ?: return null
            return try {
                parse(delegate)
            } catch (error: Throwable) {
                logWarn(TAG, "Failed to open CISO: ${error.message}")
                delegate.close()
                null
            }
        }

        private fun parse(delegate: RomDataSource): CisoRomDataSource {
            val header = readBytes(delegate, 0L, CISO_HEADER_SIZE.toInt()) ?: throw IllegalArgumentException("Unexpected EOF while reading CISO header")
            require(header.copyOfRange(0, 4).contentEquals(CISO_MAGIC)) { "CISO magic mismatch" }

            val blockSize = littleEndianInt(header, 4)
            require(blockSize > 0) { "Invalid CISO block size=$blockSize" }

            val blockMap = IntArray(CISO_MAP_SIZE)
            var usedBlockCount = 0
            for (index in 0 until CISO_MAP_SIZE) {
                blockMap[index] = when (val value = header[8 + index].toInt() and 0xFF) {
                    0 -> UNUSED_CISO_BLOCK
                    1 -> usedBlockCount++
                    else -> throw IllegalArgumentException("Invalid CISO map value=$value at index=$index")
                }
            }

            return CisoRomDataSource(
                delegate = delegate,
                blockSize = blockSize,
                blockMap = blockMap
            )
        }
    }
}
