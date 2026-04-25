package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

private const val NDS_HEADER_SIZE = 512
private const val NDS_ICON_BLOCK_SIZE = 0xA00
private const val NDS_HASHED_HEADER_SIZE = 0x160
private const val NDS_MAX_CODE_SIZE = 16 * 1024 * 1024
private const val NDS_SUPERCARD_HEADER_SIZE = 512L

internal object NintendoDsRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "nds")

    override fun hash(input: RomHashInput): String? {
        val openDataSource = input.openDataSource ?: return null
        return openDataSource().use { dataSource ->
            if (dataSource == null) return@use null

            val header = ByteArray(NDS_HEADER_SIZE)
            val headerOffset = if (hasSuperCardHeader(dataSource, header)) NDS_SUPERCARD_HEADER_SIZE else 0L
            if (!readFully(dataSource, headerOffset, header)) return@use null

            val arm9Addr = littleEndianInt(header, 0x20).toLong() and 0xFFFF_FFFFL
            val arm9Size = littleEndianInt(header, 0x2C)
            val arm7Addr = littleEndianInt(header, 0x30).toLong() and 0xFFFF_FFFFL
            val arm7Size = littleEndianInt(header, 0x3C)
            val iconAddr = littleEndianInt(header, 0x68).toLong() and 0xFFFF_FFFFL

            if (arm9Size < 0 || arm7Size < 0 || arm9Size + arm7Size > NDS_MAX_CODE_SIZE) {
                return@use null
            }

            val digest = MessageDigest.getInstance("MD5")
            digest.update(header, 0, NDS_HASHED_HEADER_SIZE)

            if (!hashSegment(digest, dataSource, headerOffset + arm9Addr, arm9Size)) return@use null
            if (!hashSegment(digest, dataSource, headerOffset + arm7Addr, arm7Size)) return@use null

            val iconBlock = ByteArray(NDS_ICON_BLOCK_SIZE)
            val iconBytesRead = readIntoBuffer(dataSource, headerOffset + iconAddr, iconBlock)
            if (iconBytesRead < 0) return@use null
            if (iconBytesRead < NDS_ICON_BLOCK_SIZE) {
                iconBlock.fill(0, iconBytesRead, NDS_ICON_BLOCK_SIZE)
            }
            digest.update(iconBlock)

            digest.digest().toHexString()
        }
    }

    internal fun hasSuperCardHeader(dataSource: RomDataSource, scratch: ByteArray = ByteArray(NDS_HEADER_SIZE)): Boolean {
        if (!readFully(dataSource, 0L, scratch)) return false
        return scratch[0] == 0x2E.toByte() &&
            scratch[1] == 0x00.toByte() &&
            scratch[2] == 0x00.toByte() &&
            scratch[3] == 0xEA.toByte() &&
            scratch[0xB0] == 0x44.toByte() &&
            scratch[0xB1] == 0x46.toByte() &&
            scratch[0xB2] == 0x96.toByte() &&
            scratch[0xB3] == 0x00.toByte()
    }

    private fun hashSegment(digest: MessageDigest, dataSource: RomDataSource, offset: Long, size: Int): Boolean {
        if (size == 0) return true
        val buffer = ByteArray(size)
        if (!readFully(dataSource, offset, buffer, size)) return false
        digest.update(buffer, 0, size)
        return true
    }

    private fun readFully(dataSource: RomDataSource, offset: Long, buffer: ByteArray, length: Int = buffer.size): Boolean =
        readIntoBuffer(dataSource, offset, buffer, length) == length

    private fun readIntoBuffer(dataSource: RomDataSource, offset: Long, buffer: ByteArray, length: Int = buffer.size): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read = dataSource.read(offset + totalRead, buffer, length - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return totalRead
    }
}
