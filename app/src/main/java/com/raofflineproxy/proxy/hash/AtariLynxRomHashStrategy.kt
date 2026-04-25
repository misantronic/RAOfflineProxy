package com.raofflineproxy.proxy.hash

internal object AtariLynxRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "lnx")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 64) { header, bytesRead, _ ->
            headerBytesToSkip(header, bytesRead)
        }

    internal fun headerBytesToSkip(header: ByteArray, bytesRead: Int): Int {
        if (header.startsWithMagic(bytesRead, "LYNX".toByteArray(Charsets.US_ASCII))) return 64
        return 0
    }
}
