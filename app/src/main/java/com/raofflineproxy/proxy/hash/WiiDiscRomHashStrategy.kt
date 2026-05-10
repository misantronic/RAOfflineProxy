package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/WiiDiscHash"

internal object WiiDiscRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "iso")

    override fun hash(input: RomHashInput): String? {
        val hash = hashWiiDisc(input)
        if (hash == null) {
            logInfo(TAG, "Could not hash ${input.fileName} as Wii disc")
        }
        return hash
    }
}
