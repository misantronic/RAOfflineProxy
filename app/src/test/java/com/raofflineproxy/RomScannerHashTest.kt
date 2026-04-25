package com.raofflineproxy

import com.raofflineproxy.proxy.hash.FdsRomHashStrategy
import com.raofflineproxy.proxy.hash.NesRomHashStrategy
import com.raofflineproxy.proxy.hash.detectPrimaryVolumeDescriptor
import com.raofflineproxy.proxy.hash.detectIsoSectorLayout
import com.raofflineproxy.proxy.hash.findFileRecord
import com.raofflineproxy.proxy.hash.PsxRomHashStrategy
import com.raofflineproxy.proxy.hash.PspRomHashStrategy
import com.raofflineproxy.proxy.hash.RomDataSource
import com.raofflineproxy.proxy.hash.RomHashInput
import com.raofflineproxy.proxy.hash.SnesRomHashStrategy
import com.raofflineproxy.proxy.hash.SectorLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

        val layout = detectPrimaryVolumeDescriptor(
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

        val layout = detectPrimaryVolumeDescriptor(
            ByteArrayRomDataSource(image),
            SectorLayout(rawSectorSize = 2048, dataOffset = 0, sectorBias = 0)
        )

        assertNotNull(layout)
        assertEquals(1L, layout!!.sectorBias)
    }

    @Test
    fun pspMatches_supportsIsoAndPbp() {
        assertTrue(PspRomHashStrategy.matches("game.iso"))
        assertTrue(PspRomHashStrategy.matches("EBOOT.PBP"))
    }

    @Test
    fun pspHash_pbpFallsBackToWholeFileMd5() {
        val bytes = "pbp-homebrew".toByteArray(Charsets.US_ASCII)
        val hash = PspRomHashStrategy.hash(
            RomHashInput(
                fileName = "EBOOT.PBP",
                fileSize = bytes.size.toLong(),
                openStream = { bytes.inputStream() }
            )
        )

        assertEquals("29971dbf4d52a9ff0caa3957062643af", hash)
    }

    @Test
    fun pspHash_isoHashesParamSfoThenEbootBin() {
        val paramBytes = "PARAM-DATA".toByteArray(Charsets.US_ASCII)
        val ebootBytes = "EBOOT-DATA".toByteArray(Charsets.US_ASCII)
        val image = buildIsoImage(
            mapOf(
                "PSP_GAME" to DirectoryEntry(
                    sector = 20,
                    size = 2048,
                    isDirectory = true
                ),
                "PSP_GAME\\PARAM.SFO" to DirectoryEntry(
                    sector = 30,
                    size = paramBytes.size.toLong(),
                    isDirectory = false,
                    content = paramBytes
                ),
                "PSP_GAME\\SYSDIR" to DirectoryEntry(
                    sector = 21,
                    size = 2048,
                    isDirectory = true
                ),
                "PSP_GAME\\SYSDIR\\EBOOT.BIN" to DirectoryEntry(
                    sector = 31,
                    size = ebootBytes.size.toLong(),
                    isDirectory = false,
                    content = ebootBytes
                )
            )
        )

        val dataSource = ByteArrayRomDataSource(image)
        val layout = detectIsoSectorLayout(dataSource)
        assertNotNull(layout)
        assertNotNull(findFileRecord(dataSource, layout!!, "PSP_GAME\\PARAM.SFO"))
        assertNotNull(findFileRecord(dataSource, layout, "PSP_GAME\\SYSDIR\\EBOOT.BIN"))

        val hash = PspRomHashStrategy.hash(
            RomHashInput(
                fileName = "game.iso",
                fileSize = image.size.toLong(),
                openStream = { image.inputStream() },
                openDataSource = { ByteArrayRomDataSource(image) }
            )
        )

        assertEquals("078c9a3e352a09d0ec06155a58057611", hash)
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

    private data class DirectoryEntry(
        val sector: Int,
        val size: Long,
        val isDirectory: Boolean,
        val content: ByteArray = byteArrayOf()
    )

    private fun buildIsoImage(entries: Map<String, DirectoryEntry>): ByteArray {
        val totalSectors = 64
        val image = ByteArray(totalSectors * 2048)

        val pvdOffset = 16 * 2048
        image[pvdOffset] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(image, pvdOffset + 1)
        image[pvdOffset + 6] = 1
        image[pvdOffset + 128] = 0x00
        image[pvdOffset + 129] = 0x08
        image[pvdOffset + 130] = 0x08
        image[pvdOffset + 131] = 0x00

        val rootEntry = createDirectoryRecord("", sector = 18, size = 2048, isDirectory = true)
        rootEntry.copyInto(image, pvdOffset + 156)

        val rootEntries = listOfNotNull(
            entries["PSP_GAME"]?.let { createDirectoryRecord("PSP_GAME", it.sector, it.size.toInt(), true) }
        )
        writeDirectorySector(image, 18, rootEntries)

        val pspGameEntries = listOfNotNull(
            entries["PSP_GAME\\PARAM.SFO"]?.let {
                createDirectoryRecord("PARAM.SFO;1", it.sector, it.size.toInt(), false)
            },
            entries["PSP_GAME\\SYSDIR"]?.let {
                createDirectoryRecord("SYSDIR", it.sector, it.size.toInt(), true)
            }
        )
        writeDirectorySector(image, 20, pspGameEntries)

        val sysdirEntries = listOfNotNull(
            entries["PSP_GAME\\SYSDIR\\EBOOT.BIN"]?.let {
                createDirectoryRecord("EBOOT.BIN;1", it.sector, it.size.toInt(), false)
            }
        )
        writeDirectorySector(image, 21, sysdirEntries)

        entries.values.filter { !it.isDirectory }.forEach { entry ->
            val offset = entry.sector * 2048
            entry.content.copyInto(image, offset)
        }

        return image
    }

    private fun writeDirectorySector(image: ByteArray, sector: Int, records: List<ByteArray>) {
        val offset = sector * 2048
        var cursor = offset
        records.forEach { record ->
            record.copyInto(image, cursor)
            cursor += record.size
        }
    }

    private fun createDirectoryRecord(
        name: String,
        sector: Int,
        size: Int,
        isDirectory: Boolean,
        specialNameByte: Int? = null
    ): ByteArray {
        val nameBytes = if (specialNameByte != null) byteArrayOf(specialNameByte.toByte()) else name.toByteArray(Charsets.US_ASCII)
        val padding = if (nameBytes.size % 2 == 0) 0 else 1
        val length = 33 + nameBytes.size + padding
        return ByteArray(length).apply {
            this[0] = length.toByte()
            this[1] = 0
            writeLittleEndianInt(this, 2, sector)
            writeLittleEndianInt(this, 10, size)
            this[25] = if (isDirectory) 0x02 else 0x00
            this[32] = nameBytes.size.toByte()
            nameBytes.copyInto(this, 33)
        }
    }

    private fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()

        target[offset + 4] = ((value shr 24) and 0xFF).toByte()
        target[offset + 5] = ((value shr 16) and 0xFF).toByte()
        target[offset + 6] = ((value shr 8) and 0xFF).toByte()
        target[offset + 7] = (value and 0xFF).toByte()
    }
}
