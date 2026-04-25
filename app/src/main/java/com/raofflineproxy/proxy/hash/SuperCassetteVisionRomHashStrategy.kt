package com.raofflineproxy.proxy.hash

internal object SuperCassetteVisionRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "cart")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 32) { header, bytesRead, _ ->
            headerBytesToSkip(header, bytesRead)
        }

    internal fun headerBytesToSkip(header: ByteArray, bytesRead: Int): Int {
        if (header.startsWithMagic(bytesRead, "EmuSCV".toByteArray(Charsets.US_ASCII))) return 32
        return 0
    }
}
