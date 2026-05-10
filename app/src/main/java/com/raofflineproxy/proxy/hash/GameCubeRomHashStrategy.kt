package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/GameCubeHash"

internal object GameCubeRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "iso", "gcm")

    override fun hash(input: RomHashInput): String? {
        val hash = hashGameCubeDisc(input)
        if (hash == null) {
            logInfo(TAG, "Could not hash ${input.fileName} as GameCube disc")
        }
        return hash
    }
}
