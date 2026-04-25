package com.raofflineproxy.proxy.hash

private val FDS_HEADER_MAGIC = byteArrayOf('F'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 0x1A)

internal object FdsRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "fds")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 16) { header, bytesRead, _ ->
            headerBytesToSkip(header, bytesRead)
        }

    internal fun headerBytesToSkip(header: ByteArray, bytesRead: Int): Int {
        if (header.startsWithMagic(bytesRead, FDS_HEADER_MAGIC)) return minOf(16, bytesRead)
        return 0
    }
}
