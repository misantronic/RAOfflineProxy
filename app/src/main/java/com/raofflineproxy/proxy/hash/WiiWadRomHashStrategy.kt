package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/WiiWadHash"

internal object WiiWadRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "wad")

    override fun hash(input: RomHashInput): String? {
        val hash = hashWiiWad(input)
        if (hash == null) {
            logInfo(TAG, "Could not hash ${input.fileName} as Wii WAD")
        }
        return hash
    }
}
