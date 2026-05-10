package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/RvzHash"

internal object RvzRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "rvz")

    override fun hash(input: RomHashInput): String? {
        logWarn(TAG, "RVZ hashing is not supported without a logical disc reader: ${input.fileName}")
        return null
    }
}
