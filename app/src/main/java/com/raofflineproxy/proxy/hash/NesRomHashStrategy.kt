package com.raofflineproxy.proxy.hash

private val NES_HEADER_MAGIC = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A)

internal object NesRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "nes")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 16) { header, bytesRead, _ ->
            headerBytesToSkip(header, bytesRead)
        }

    internal fun headerBytesToSkip(header: ByteArray, bytesRead: Int): Int {
        if (header.startsWithMagic(bytesRead, NES_HEADER_MAGIC)) return minOf(16, bytesRead)
        return 0
    }
}
