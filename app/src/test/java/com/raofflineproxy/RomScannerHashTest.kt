package com.raofflineproxy

import com.raofflineproxy.proxy.hash.FdsRomHashStrategy
import com.raofflineproxy.proxy.hash.NesRomHashStrategy
import com.raofflineproxy.proxy.hash.PsxRomHashStrategy
import com.raofflineproxy.proxy.hash.RomDataSource
import com.raofflineproxy.proxy.hash.SnesRomHashStrategy
import com.raofflineproxy.proxy.hash.SectorLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

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

    @Test
    fun psxParseBootPath_normalizesCdromPrefixAndVersion() {
        val systemCnf = "BOOT = cdrom:\\SLUS_007.45;1\nTCB = 4\n"

        assertEquals("SLUS_007.45", PsxRomHashStrategy.parseBootPath(systemCnf))
    }

    @Test
    fun psxParseBootPath_preservesSubdirectories() {
        val systemCnf = "BOOT=cdrom:\\BIN\\SCES_012.37;1\n"

        assertEquals("BIN\\SCES_012.37", PsxRomHashStrategy.parseBootPath(systemCnf))
    }

    @Test
    fun psxParseBootPath_fallsBackToNullWhenMissing() {
        assertNull(PsxRomHashStrategy.parseBootPath("TCB = 4\nEVENT = 10\n"))
    }

    @Test
    fun psxDetectPrimaryVolumeDescriptor_mode2352Uses24ByteOffset() {
        val image = ByteArray(2352 * 17)
        val pvdOffset = 16 * 2352 + 24
        image[pvdOffset] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(image, pvdOffset + 1)

        val layout = PsxRomHashStrategy.detectPrimaryVolumeDescriptor(
            ByteArrayRomDataSource(image),
            SectorLayout(rawSectorSize = 2352, dataOffset = 24, sectorBias = 0)
        )

        assertNotNull(layout)
        assertEquals(0L, layout!!.sectorBias)
    }

    @Test
    fun psxDetectPrimaryVolumeDescriptor_recordsSectorBias() {
        val image = ByteArray(2048 * 18)
        val pvdOffset = 17 * 2048
        image[pvdOffset] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(image, pvdOffset + 1)

        val layout = PsxRomHashStrategy.detectPrimaryVolumeDescriptor(
            ByteArrayRomDataSource(image),
            SectorLayout(rawSectorSize = 2048, dataOffset = 0, sectorBias = 0)
        )

        assertNotNull(layout)
        assertEquals(1L, layout!!.sectorBias)
    }

    private class ByteArrayRomDataSource(
        private val data: ByteArray
    ) : RomDataSource {
        override val length: Long = data.size.toLong()

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= data.size) return -1
            val start = offset.toInt()
            val count = minOf(length, data.size - start)
            data.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = start + count)
            return count
        }

        override fun close() = Unit
    }
}
