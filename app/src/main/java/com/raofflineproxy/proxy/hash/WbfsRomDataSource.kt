package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/WbfsDataSource"
private val WBFS_MAGIC = byteArrayOf('W'.code.toByte(), 'B'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte())
private const val WBFS_HEADER_SIZE = 512
private const val WII_SECTOR_SIZE = 0x8000L
private const val WII_SECTOR_COUNT = 143432L * 2L
private const val WII_DISC_HEADER_SIZE = 256L

internal class WbfsRomDataSource private constructor(
    private val delegate: RomDataSource,
    private val wbfsSectorSize: Long,
    private val blocksPerDisc: Int,
    private val wlbaTable: IntArray
) : RomDataSource {
    override val length: Long
        get() = WII_SECTOR_COUNT * WII_SECTOR_SIZE

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) return -1
        val targetLength = minOf(length, buffer.size, (this.length - offset).toInt())
        var totalRead = 0
        var currentOffset = offset

        while (totalRead < targetLength) {
            val blockIndex = (currentOffset / wbfsSectorSize).toInt()
            if (blockIndex !in 0 until blocksPerDisc) break

            val offsetInBlock = currentOffset % wbfsSectorSize
            val bytesToRead = minOf((wbfsSectorSize - offsetInBlock).toInt(), targetLength - totalRead)
            val mappedBlock = wlbaTable[blockIndex]
            if (mappedBlock == 0) {
                buffer.fill(0, totalRead, totalRead + bytesToRead)
            } else {
                val fileOffset = mappedBlock.toLong() * wbfsSectorSize + offsetInBlock
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
        internal fun open(openDataSource: () -> RomDataSource?): WbfsRomDataSource? {
            val delegate = openDataSource() ?: return null
            return try {
                parse(delegate)
            } catch (error: Throwable) {
                logWarn(TAG, "Failed to open WBFS: ${error.message}")
                delegate.close()
                null
            }
        }

        private fun parse(delegate: RomDataSource): WbfsRomDataSource {
            val header = readBytes(delegate, 0L, WBFS_HEADER_SIZE) ?: throw IllegalArgumentException("Unexpected EOF while reading WBFS header")
            require(header.copyOfRange(0, 4).contentEquals(WBFS_MAGIC)) { "WBFS magic mismatch" }

            val hdSectorCount = readBigEndianInt(header, 4) ?: throw IllegalArgumentException("Missing WBFS hd sector count")
            val hdSectorShift = header[8].toInt() and 0xFF
            val wbfsSectorShift = header[9].toInt() and 0xFF
            val hdSectorSize = 1L shl hdSectorShift
            val wbfsSectorSize = 1L shl wbfsSectorShift
            require(wbfsSectorSize >= WII_SECTOR_SIZE) { "WBFS sector too small" }
            require(delegate.length == hdSectorCount.toLong() * hdSectorSize) { "WBFS file size mismatch" }
            require((header[12].toInt() and 0xFF) != 0) { "WBFS disc slot 0 missing" }

            val blocksPerDisc = ((WII_SECTOR_COUNT * WII_SECTOR_SIZE + wbfsSectorSize - 1) / wbfsSectorSize).toInt()
            val discInfoSize = alignUp(WII_DISC_HEADER_SIZE + blocksPerDisc * 2L, hdSectorSize)
            require(hdSectorSize + discInfoSize <= delegate.length) { "WBFS disc info out of range" }

            val wlbaBytes = readBytes(delegate, hdSectorSize + WII_DISC_HEADER_SIZE, blocksPerDisc * 2)
                ?: throw IllegalArgumentException("Unexpected EOF while reading WBFS wlba table")
            val wlbaTable = IntArray(blocksPerDisc) { index ->
                val entryOffset = index * 2
                ((wlbaBytes[entryOffset].toInt() and 0xFF) shl 8) or (wlbaBytes[entryOffset + 1].toInt() and 0xFF)
            }

            return WbfsRomDataSource(
                delegate = delegate,
                wbfsSectorSize = wbfsSectorSize,
                blocksPerDisc = blocksPerDisc,
                wlbaTable = wlbaTable
            )
        }
    }
}

private fun alignUp(value: Long, alignment: Long): Long = ((value + alignment - 1) / alignment) * alignment
