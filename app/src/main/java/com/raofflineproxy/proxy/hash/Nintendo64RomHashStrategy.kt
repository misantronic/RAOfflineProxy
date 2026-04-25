package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

private const val TAG = "RAProxy/N64Hash"
private const val N64_BIG_ENDIAN_MAGIC = 0x80
private const val N64_DISK_DRIVE_MAGIC_ONE = 0xE8
private const val N64_DISK_DRIVE_MAGIC_TWO = 0x22
private const val N64_HASH_LIMIT_BYTES = 64L * 1024L * 1024L
private const val N64_BUFFER_SIZE = 65536

internal object Nintendo64RomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "z64", "n64", "v64")

    override fun hash(input: RomHashInput): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            input.openStream()?.use { stream ->
                val header = ByteArray(1)
                if (stream.read(header) != 1) return null

                val byteOrder = detectByteOrder(header[0]) ?: run {
                    logInfo(TAG, "Unrecognized N64 first byte=0x${(header[0].toInt() and 0xFF).toString(16)} file=${input.fileName}")
                    return null
                }

                logInfo(TAG, "Detected N64 byte order=$byteOrder file=${input.fileName}")

                var remaining = minOf(input.fileSize, N64_HASH_LIMIT_BYTES)
                if (remaining <= 0L) return null

                val buffer = ByteArray(N64_BUFFER_SIZE)
                buffer[0] = header[0]
                var buffered = 1
                val initialRead = stream.read(buffer, 1, buffer.size - 1)
                if (initialRead > 0) {
                    buffered += initialRead
                }
                normalizeNintendo64Bytes(buffer, buffered, byteOrder)
                digest.update(buffer, 0, minOf(buffered.toLong(), remaining).toInt())
                remaining -= buffered.toLong()

                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    normalizeNintendo64Bytes(buffer, read, byteOrder)
                    digest.update(buffer, 0, read)
                    remaining -= read.toLong()
                }
            } ?: return null
            digest.digest().toHexString()
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizeNintendo64Bytes(bytes: ByteArray, bytesRead: Int, byteOrder: N64ByteOrder) {
        when (byteOrder) {
            N64ByteOrder.BYTE_SWAPPED -> byteswap16(bytes, bytesRead)
            N64ByteOrder.LITTLE_ENDIAN -> byteswap32(bytes, bytesRead)
            N64ByteOrder.BIG_ENDIAN,
            N64ByteOrder.DISK_DRIVE -> Unit
        }
    }

    internal fun detectByteOrder(firstByte: Byte): N64ByteOrder? {
        return when (firstByte.toInt() and 0xFF) {
            N64_BIG_ENDIAN_MAGIC -> N64ByteOrder.BIG_ENDIAN
            0x37 -> N64ByteOrder.BYTE_SWAPPED
            0x40 -> N64ByteOrder.LITTLE_ENDIAN
            N64_DISK_DRIVE_MAGIC_ONE,
            N64_DISK_DRIVE_MAGIC_TWO -> N64ByteOrder.DISK_DRIVE
            else -> null
        }
    }

    private fun byteswap16(bytes: ByteArray, bytesRead: Int) {
        var index = 0
        while (index + 1 < bytesRead) {
            val first = bytes[index]
            bytes[index] = bytes[index + 1]
            bytes[index + 1] = first
            index += 2
        }
    }

    private fun byteswap32(bytes: ByteArray, bytesRead: Int) {
        var index = 0
        while (index + 3 < bytesRead) {
            val b0 = bytes[index]
            val b1 = bytes[index + 1]
            val b2 = bytes[index + 2]
            val b3 = bytes[index + 3]
            bytes[index] = b3
            bytes[index + 1] = b2
            bytes[index + 2] = b1
            bytes[index + 3] = b0
            index += 4
        }
    }
}

internal enum class N64ByteOrder {
    BIG_ENDIAN,
    BYTE_SWAPPED,
    LITTLE_ENDIAN,
    DISK_DRIVE
}
