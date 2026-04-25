package com.raofflineproxy.proxy.hash

internal object PcEngineRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "pce", "sgx")

    override fun hash(input: RomHashInput): String? =
        md5HashWithHeaderRule(input, headerLength = 512) { _, _, fileSize ->
            headerBytesToSkip(fileSize)
        }

    internal fun headerBytesToSkip(fileSize: Long): Int {
        if ((fileSize and 512L) != 0L) return 512
        return 0
    }
}
