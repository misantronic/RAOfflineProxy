package com.raofflineproxy.proxy.hash

internal object GameBoyRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "gb")

    override fun hash(input: RomHashInput): String? = GenericMd5RomHashStrategy.hash(input)
}
