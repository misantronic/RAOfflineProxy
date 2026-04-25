package com.raofflineproxy

import com.raofflineproxy.proxy.hash.FdsRomHashStrategy
import com.raofflineproxy.proxy.hash.NesRomHashStrategy
import com.raofflineproxy.proxy.hash.SnesRomHashStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class RomScannerHashTest {

    @Test
    fun nesHeaderBytesToSkip_nesHeader_skips16Bytes() {
        val header = byteArrayOf(
            'N'.code.toByte(),
            'E'.code.toByte(),
            'S'.code.toByte(),
            0x1A,
            0x02,
            0x01,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        )

        assertEquals(16, NesRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun fdsHeaderBytesToSkip_fdsHeader_skips16Bytes() {
        val header = byteArrayOf(
            'F'.code.toByte(),
            'D'.code.toByte(),
            'S'.code.toByte(),
            0x1A,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        )

        assertEquals(16, FdsRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun nesHeaderBytesToSkip_nonHeader_skipsNothing() {
        val header = byteArrayOf(
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0A,
            0x0B,
            0x0C,
            0x0D,
            0x0E,
            0x0F,
            0x10
        )

        assertEquals(0, NesRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun nesHeaderBytesToSkip_shortRead_skipsNothing() {
        val header = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte())

        assertEquals(0, NesRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun snesHeaderBytesToSkip_copierHeader_skips512Bytes() {
        assertEquals(512, SnesRomHashStrategy.headerBytesToSkip(bytesRead = 512, fileSize = 8192L + 512L))
    }

    @Test
    fun snesHeaderBytesToSkip_plainRom_skipsNothing() {
        assertEquals(0, SnesRomHashStrategy.headerBytesToSkip(bytesRead = 512, fileSize = 8192L))
    }
}
