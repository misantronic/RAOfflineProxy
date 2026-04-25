package com.raofflineproxy.proxy.hash

internal object Atari7800RomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "a78")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 128) { header, bytesRead, _ ->
            headerBytesToSkip(header, bytesRead)
        }

    internal fun headerBytesToSkip(header: ByteArray, bytesRead: Int): Int {
        if (bytesRead >= 10 && header.copyOfRange(1, 10).contentEquals("ATARI7800".toByteArray(Charsets.US_ASCII))) {
            return 128
        }

        return 0
    }
}
