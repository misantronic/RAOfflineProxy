package com.raofflineproxy.proxy.hash

private const val TAG = "RAProxy/RvzHash"

internal object RvzRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "rvz")

    override fun hash(input: RomHashInput): String? {
        val openDataSource = input.openDataSource ?: return null
        return RvzRomDataSource.open(openDataSource)?.use { rvz ->
            when (rvz.metadata().discType) {
                RvzDiscType.GAMECUBE -> hashGameCubeDisc(rvz)
                RvzDiscType.WII -> hashWiiDisc(rvz)
            }
        }
    }
}
