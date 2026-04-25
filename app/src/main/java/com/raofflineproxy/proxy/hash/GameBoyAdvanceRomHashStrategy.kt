package com.raofflineproxy.proxy.hash

internal object GameBoyAdvanceRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "gba")

    override fun hash(input: RomHashInput): String? = GenericMd5RomHashStrategy.hash(input)
}
