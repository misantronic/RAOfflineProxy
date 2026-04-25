package com.raofflineproxy.proxy.hash

internal object GameBoyColorRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "gbc")

    override fun hash(input: RomHashInput): String? = GenericMd5RomHashStrategy.hash(input)
}
