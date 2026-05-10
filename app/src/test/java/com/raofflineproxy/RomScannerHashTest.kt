package com.raofflineproxy

import com.raofflineproxy.proxy.hash.FdsRomHashStrategy
import com.raofflineproxy.proxy.hash.GameCubeRomHashStrategy
import com.raofflineproxy.proxy.hash.N64ByteOrder
import com.raofflineproxy.proxy.hash.NesRomHashStrategy
import com.raofflineproxy.proxy.hash.NintendoDsRomHashStrategy
import com.raofflineproxy.proxy.hash.Nintendo64RomHashStrategy
import com.raofflineproxy.proxy.hash.RvzRomHashStrategy
import com.raofflineproxy.proxy.hash.Atari7800RomHashStrategy
import com.raofflineproxy.proxy.hash.AtariLynxRomHashStrategy
import com.raofflineproxy.proxy.hash.PcEngineRomHashStrategy
import com.raofflineproxy.proxy.hash.detectPrimaryVolumeDescriptor
import com.raofflineproxy.proxy.hash.detectIsoSectorLayout
import com.raofflineproxy.proxy.hash.findFileRecord
import com.raofflineproxy.proxy.hash.readBigEndianInt
import com.raofflineproxy.proxy.hash.hashRom
import com.raofflineproxy.proxy.hash.PsxRomHashStrategy
import com.raofflineproxy.proxy.hash.PspRomHashStrategy
import com.raofflineproxy.proxy.hash.RomDataSource
import com.raofflineproxy.proxy.hash.RomHashInput
import com.raofflineproxy.proxy.hash.SnesRomHashStrategy
import com.raofflineproxy.proxy.hash.SuperCassetteVisionRomHashStrategy
import com.raofflineproxy.proxy.hash.SectorLayout
import com.raofflineproxy.proxy.hash.WiiDiscRomHashStrategy
import com.raofflineproxy.proxy.hash.WiiWadRomHashStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun atari7800HeaderBytesToSkip_header_skips128Bytes() {
        val header = ByteArray(128)
        "ATARI7800".toByteArray(Charsets.US_ASCII).copyInto(header, 1)

        assertEquals(128, Atari7800RomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun atari7800HeaderBytesToSkip_plainRom_skipsNothing() {
        assertEquals(0, Atari7800RomHashStrategy.headerBytesToSkip(ByteArray(128), 128))
    }

    @Test
    fun lynxHeaderBytesToSkip_header_skips64Bytes() {
        val header = ByteArray(64)
        "LYNX".toByteArray(Charsets.US_ASCII).copyInto(header, 0)

        assertEquals(64, AtariLynxRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun lynxHeaderBytesToSkip_plainRom_skipsNothing() {
        assertEquals(0, AtariLynxRomHashStrategy.headerBytesToSkip(ByteArray(64), 64))
    }

    @Test
    fun pcEngineHeaderBytesToSkip_sizeWith512ExtraBytes_skipsHeader() {
        assertEquals(512, PcEngineRomHashStrategy.headerBytesToSkip(8192L + 512L))
    }

    @Test
    fun pcEngineHeaderBytesToSkip_exactMultipleOf8k_skipsNothing() {
        assertEquals(0, PcEngineRomHashStrategy.headerBytesToSkip(8192L))
    }

    @Test
    fun superCassetteVisionHeaderBytesToSkip_header_skips32Bytes() {
        val header = ByteArray(32)
        "EmuSCV".toByteArray(Charsets.US_ASCII).copyInto(header, 0)

        assertEquals(32, SuperCassetteVisionRomHashStrategy.headerBytesToSkip(header, header.size))
    }

    @Test
    fun superCassetteVisionHeaderBytesToSkip_plainRom_skipsNothing() {
        assertEquals(0, SuperCassetteVisionRomHashStrategy.headerBytesToSkip(ByteArray(32), 32))
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

    @Test
    fun ndsHasSuperCardHeader_detectsExpectedPattern() {
        val file = ByteArray(1024)
        file[0] = 0x2E
        file[1] = 0x00
        file[2] = 0x00
        file[3] = 0xEA.toByte()
        file[0xB0] = 0x44
        file[0xB1] = 0x46
        file[0xB2] = 0x96.toByte()
        file[0xB3] = 0x00

        assertTrue(NintendoDsRomHashStrategy.hasSuperCardHeader(ByteArrayRomDataSource(file)))
    }

    @Test
    fun ndsHash_hashesHeaderArm9Arm7AndIconBlock() {
        val header = ByteArray(512)
        header[0x20] = 0x00
        header[0x21] = 0x02
        header[0x2C] = 0x04
        header[0x30] = 0x00
        header[0x31] = 0x03
        header[0x3C] = 0x03
        header[0x68] = 0x00
        header[0x69] = 0x04

        val arm9 = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val arm7 = byteArrayOf(0x55, 0x66, 0x77)
        val icon = ByteArray(0xA00) { index -> (index and 0xFF).toByte() }

        val file = ByteArray(0x1000 + 0xA00)
        header.copyInto(file, 0)
        arm9.copyInto(file, 0x0200)
        arm7.copyInto(file, 0x0300)
        icon.copyInto(file, 0x0400)

        val hash = NintendoDsRomHashStrategy.hash(
            RomHashInput(
                fileName = "test.nds",
                fileSize = file.size.toLong(),
                openStream = { file.inputStream() },
                openDataSource = { ByteArrayRomDataSource(file) }
            )
        )

        assertEquals("213d1906eccdfd3b2f42d625a1ae42d9", hash)
    }

    @Test
    fun ndsHash_superCardHeaderIsIgnored() {
        val superCardHeader = ByteArray(512)
        superCardHeader[0] = 0x2E
        superCardHeader[1] = 0x00
        superCardHeader[2] = 0x00
        superCardHeader[3] = 0xEA.toByte()
        superCardHeader[0xB0] = 0x44
        superCardHeader[0xB1] = 0x46
        superCardHeader[0xB2] = 0x96.toByte()
        superCardHeader[0xB3] = 0x00

        val header = ByteArray(512)
        header[0x20] = 0x00
        header[0x21] = 0x02
        header[0x2C] = 0x01
        header[0x30] = 0x00
        header[0x31] = 0x03
        header[0x3C] = 0x01
        header[0x68] = 0x00
        header[0x69] = 0x04

        val file = ByteArray(1024 + 0x1000 + 0xA00)
        superCardHeader.copyInto(file, 0)
        header.copyInto(file, 512)
        file[0x0200 + 512] = 0x12
        file[0x0300 + 512] = 0x34

        val hash = NintendoDsRomHashStrategy.hash(
            RomHashInput(
                fileName = "test.nds",
                fileSize = file.size.toLong(),
                openStream = { file.inputStream() },
                openDataSource = { ByteArrayRomDataSource(file) }
            )
        )

        assertNotNull(hash)
    }

    @Test
    fun n64NormalizeNintendo64Bytes_v64ConvertsToBigEndianOrder() {
        val bytes = byteArrayOf(
            0x37,
            0x80.toByte(),
            0x40,
            0x12,
            0x11,
            0x22,
            0x33,
            0x44
        )

        Nintendo64RomHashStrategy.normalizeNintendo64Bytes(bytes, bytes.size, N64ByteOrder.BYTE_SWAPPED)

        assertEquals(0x80.toByte(), bytes[0])
        assertEquals(0x37.toByte(), bytes[1])
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(0x40.toByte(), bytes[3])
        assertEquals(0x22.toByte(), bytes[4])
        assertEquals(0x11.toByte(), bytes[5])
        assertEquals(0x44.toByte(), bytes[6])
        assertEquals(0x33.toByte(), bytes[7])
    }

    @Test
    fun n64NormalizeNintendo64Bytes_n64ConvertsToBigEndianOrder() {
        val bytes = byteArrayOf(
            0x40,
            0x12,
            0x37,
            0x80.toByte(),
            0x44,
            0x33,
            0x22,
            0x11
        )

        Nintendo64RomHashStrategy.normalizeNintendo64Bytes(bytes, bytes.size, N64ByteOrder.LITTLE_ENDIAN)

        assertEquals(0x80.toByte(), bytes[0])
        assertEquals(0x37.toByte(), bytes[1])
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(0x40.toByte(), bytes[3])
        assertEquals(0x11.toByte(), bytes[4])
        assertEquals(0x22.toByte(), bytes[5])
        assertEquals(0x33.toByte(), bytes[6])
        assertEquals(0x44.toByte(), bytes[7])
    }

    @Test
    fun n64Hash_n64AndV64NormalizeToSameHashAsZ64() {
        val z64 = ByteArray(65536 + 16) { index -> ((index * 37) and 0xFF).toByte() }.also {
            it[0] = 0x80.toByte()
            it[1] = 0x37
            it[2] = 0x12
            it[3] = 0x40
        }
        val n64 = z64ToN64(z64)
        val v64 = z64ToV64(z64)

        val z64Hash = Nintendo64RomHashStrategy.hash(
            RomHashInput(
                fileName = "test.z64",
                fileSize = z64.size.toLong(),
                openStream = { z64.inputStream() }
            )
        )
        val n64Hash = Nintendo64RomHashStrategy.hash(
            RomHashInput(
                fileName = "test.n64",
                fileSize = n64.size.toLong(),
                openStream = { n64.inputStream() }
            )
        )
        val v64Hash = Nintendo64RomHashStrategy.hash(
            RomHashInput(
                fileName = "test.v64",
                fileSize = v64.size.toLong(),
                openStream = { v64.inputStream() }
            )
        )

        assertEquals(z64Hash, n64Hash)
        assertEquals(z64Hash, v64Hash)
    }

    @Test
    fun n64DetectByteOrder_usesFirstByteOnly() {
        assertEquals(N64ByteOrder.BIG_ENDIAN, Nintendo64RomHashStrategy.detectByteOrder(0x80.toByte()))
        assertEquals(N64ByteOrder.BYTE_SWAPPED, Nintendo64RomHashStrategy.detectByteOrder(0x37.toByte()))
        assertEquals(N64ByteOrder.LITTLE_ENDIAN, Nintendo64RomHashStrategy.detectByteOrder(0x40.toByte()))
    }

    @Test
    fun gameCubeStrategy_matchesIsoAndGcm() {
        assertTrue(GameCubeRomHashStrategy.matches("game.iso"))
        assertTrue(GameCubeRomHashStrategy.matches("game.gcm"))
    }

    @Test
    fun wiiDiscStrategy_matchesIso() {
        assertTrue(WiiDiscRomHashStrategy.matches("game.iso"))
    }

    @Test
    fun wiiWadStrategy_matchesWad() {
        assertTrue(WiiWadRomHashStrategy.matches("channel.wad"))
    }

    @Test
    fun rvzStrategy_matchesRvzAndReturnsNull() {
        assertTrue(RvzRomHashStrategy.matches("game.rvz"))
        assertNull(
            RvzRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.rvz",
                    fileSize = 4,
                    openStream = { byteArrayOf(1, 2, 3, 4).inputStream() }
                )
            )
        )
    }

    @Test
    fun hashRom_rvzDoesNotFallBackToGenericMd5() {
        assertNull(
            hashRom(
                RomHashInput(
                    fileName = "game.rvz",
                    fileSize = 4,
                    openStream = { byteArrayOf(1, 2, 3, 4).inputStream() }
                )
            )
        )
    }

    @Test
    fun hashRom_wadDoesNotFallBackToGenericMd5WhenWadHashFails() {
        val bytes = ByteArray(64)

        assertNull(
            hashRom(
                RomHashInput(
                    fileName = "channel.wad",
                    fileSize = bytes.size.toLong(),
                    openStream = { bytes.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(bytes) }
                )
            )
        )
    }

    @Test
    fun hashRom_gameCubeIsoDoesNotFallBackToGenericMd5WhenDiscHashFails() {
        val bytes = ByteArray(0x20)
        writeBigEndianInt(bytes, 0x1C, 0xC2339F3D.toInt())

        assertNull(
            hashRom(
                RomHashInput(
                    fileName = "game.iso",
                    fileSize = bytes.size.toLong(),
                    openStream = { bytes.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(bytes) }
                )
            )
        )
    }

    @Test
    fun hashRom_nonNintendoIsoCanStillFallBackToGenericMd5() {
        val bytes = "plain-iso".toByteArray(Charsets.US_ASCII)

        assertEquals(
            "ffcb189f984a79b18014c8f2ecd6a821",
            hashRom(
                RomHashInput(
                    fileName = "game.iso",
                    fileSize = bytes.size.toLong(),
                    openStream = { bytes.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(bytes) }
                )
            )
        )
    }

    @Test
    fun readBigEndianInt_readsExpectedValue() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12345678, readBigEndianInt(bytes))
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
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DirectoryEntry

            if (sector != other.sector) return false
            if (size != other.size) return false
            if (isDirectory != other.isDirectory) return false
            if (!content.contentEquals(other.content)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = sector
            result = 31 * result + size.hashCode()
            result = 31 * result + isDirectory.hashCode()
            result = 31 * result + content.contentHashCode()
            return result
        }
    }

    private fun z64ToN64(z64: ByteArray): ByteArray {
        val result = z64.copyOf()
        var index = 0
        while (index + 3 < result.size) {
            val b0 = result[index]
            val b1 = result[index + 1]
            val b2 = result[index + 2]
            val b3 = result[index + 3]
            result[index] = b3
            result[index + 1] = b2
            result[index + 2] = b1
            result[index + 3] = b0
            index += 4
        }
        return result
    }

    private fun z64ToV64(z64: ByteArray): ByteArray {
        val result = z64.copyOf()
        var index = 0
        while (index + 1 < result.size) {
            val first = result[index]
            result[index] = result[index + 1]
            result[index + 1] = first
            index += 2
        }
        return result
    }

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

    private fun writeDirectorySector(image: ByteArray, sector: Int, records: List<ByteArray>, sectorSize: Int = 2048) {
        val offset = sector * sectorSize
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

    private fun writeBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
