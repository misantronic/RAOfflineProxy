package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

internal object GenericMd5RomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = false

    override fun hash(input: RomHashInput): String? = md5HashWithHeaderRule(input, headerLength = 0) { _, _, _ -> 0 }
}

internal fun md5HashWithHeaderRule(
    input: RomHashInput,
    headerLength: Int,
    bytesToSkip: (header: ByteArray, bytesRead: Int, fileSize: Long) -> Int
): String? {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        input.openStream()?.use { stream ->
            val header = ByteArray(headerLength)
            var headerBytesRead = 0
            while (headerBytesRead < header.size) {
                val read = stream.read(header, headerBytesRead, header.size - headerBytesRead)
                if (read <= 0) break
                headerBytesRead += read
            }

            val headerBytesToSkip = bytesToSkip(header, headerBytesRead, input.fileSize)
                .coerceIn(0, headerBytesRead)
            if (headerBytesRead > headerBytesToSkip) {
                digest.update(header, headerBytesToSkip, headerBytesRead - headerBytesToSkip)
            }

            val buffer = ByteArray(8192)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        } ?: return null
        digest.digest().toHexString()
    } catch (_: Exception) {
        null
    }
}

internal fun ByteArray.startsWithMagic(bytesRead: Int, magic: ByteArray): Boolean {
    if (bytesRead < magic.size) return false
    for (index in magic.indices) {
        if (this[index] != magic[index]) return false
    }
    return true
}
