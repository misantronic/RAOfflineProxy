package com.raofflineproxy.proxy.hash

internal object SnesRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "smc", "sfc", "fig", "swc")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 512) { _, bytesRead, fileSize ->
            headerBytesToSkip(bytesRead, fileSize)
        }

    internal fun headerBytesToSkip(bytesRead: Int, fileSize: Long): Int {
        if (bytesRead < 512) return 0
        if (fileSize <= 512L) return 0
        return if ((fileSize - 512L) % 8192L == 0L) 512 else 0
    }
}
