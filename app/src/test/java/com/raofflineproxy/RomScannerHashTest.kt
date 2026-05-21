package com.raofflineproxy

import com.github.luben.zstd.Zstd
import com.raofflineproxy.proxy.hash.FdsRomHashStrategy
import com.raofflineproxy.proxy.hash.GameCubeRomHashStrategy
import com.raofflineproxy.proxy.hash.N64ByteOrder
import com.raofflineproxy.proxy.hash.NesRomHashStrategy
import com.raofflineproxy.proxy.hash.NintendoDsRomHashStrategy
import com.raofflineproxy.proxy.hash.Nintendo64RomHashStrategy
import com.raofflineproxy.proxy.hash.RvzRomHashStrategy
import com.raofflineproxy.proxy.hash.decodeRvzPacked
import com.raofflineproxy.proxy.hash.generateRvzBytes
import com.raofflineproxy.proxy.hash.Atari7800RomHashStrategy
import com.raofflineproxy.proxy.hash.AtariLynxRomHashStrategy
import com.raofflineproxy.proxy.hash.PcEngineRomHashStrategy
import com.raofflineproxy.proxy.hash.detectPrimaryVolumeDescriptor
import com.raofflineproxy.proxy.hash.detectIsoSectorLayout
import com.raofflineproxy.proxy.hash.findFileRecord
import com.raofflineproxy.proxy.hash.hashZipRom
import com.raofflineproxy.proxy.hash.readBigEndianInt
import com.raofflineproxy.proxy.hash.hashWiiDisc
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        assertTrue(GameCubeRomHashStrategy.matches("game.ciso"))
        assertTrue(GameCubeRomHashStrategy.matches("game.gcz"))
    }

    @Test
    fun wiiDiscStrategy_matchesIso() {
        assertTrue(WiiDiscRomHashStrategy.matches("game.iso"))
        assertTrue(WiiDiscRomHashStrategy.matches("game.ciso"))
        assertTrue(WiiDiscRomHashStrategy.matches("game.gcz"))
        assertTrue(WiiDiscRomHashStrategy.matches("game.wbfs"))
    }

    @Test
    fun wiiWadStrategy_matchesWad() {
        assertTrue(WiiWadRomHashStrategy.matches("channel.wad"))
    }

    @Test
    fun rvzStrategy_matchesRvzAndHashesGameCubeDisc() {
        assertTrue(RvzRomHashStrategy.matches("game.rvz"))
        val disc = buildGameCubeDisc()
        val rvz = buildRvzImage(disc = disc, discType = 1, compressionType = 0)

        assertEquals(
            GameCubeRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.gcm",
                    fileSize = disc.size.toLong(),
                    openStream = { disc.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(disc) }
                )
            ),
            RvzRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.rvz",
                    fileSize = rvz.size.toLong(),
                    openStream = { rvz.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(rvz) }
                )
            )
        )
    }

    @Test
    fun rvzStrategy_hashesZstdCompressedGameCubeDisc() {
        val disc = buildGameCubeDisc()
        val rvz = buildRvzImage(disc = disc, discType = 1, compressionType = 5)

        assertEquals(
            GameCubeRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.gcm",
                    fileSize = disc.size.toLong(),
                    openStream = { disc.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(disc) }
                )
            ),
            hashRom(
                RomHashInput(
                    fileName = "game.rvz",
                    fileSize = rvz.size.toLong(),
                    openStream = { rvz.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(rvz) }
                )
            )
        )
    }

    @Test
    fun rvzStrategy_returnsNullForWiiRvz() {
        val disc = buildGameCubeDisc()
        val rvz = buildRvzImage(disc = disc, discType = 2, compressionType = 0)

        assertNull(
            hashRom(
                RomHashInput(
                    fileName = "game.rvz",
                    fileSize = rvz.size.toLong(),
                    openStream = { rvz.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(rvz) }
                )
            )
        )
    }

    @Test
    fun decodeRvzPacked_generatedRecordsRespectAbsoluteOffsetWithin32kWindow() {
        val seed = IntArray(17) { it + 1 }
        val packed = ByteArrayOutputStream().use { output ->
            writeBigEndianInt(output, Int.MIN_VALUE or 16)
            seed.forEach { writeBigEndianInt(output, it) }
            output.toByteArray()
        }

        val bytesAtZero = decodeRvzPacked(packed, logicalSize = 16, groupLogicalStart = 0L)
        val bytesAtOffset = decodeRvzPacked(packed, logicalSize = 16, groupLogicalStart = 0x80L)

        assertTrue(bytesAtZero.isNotEmpty())
        assertFalse(bytesAtZero.contentEquals(bytesAtOffset))
        assertArrayEquals(generateRvzBytes(seed, 16, 0L), bytesAtZero)
        assertArrayEquals(generateRvzBytes(seed, 16, 0x80L), bytesAtOffset)
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
        bytes[0x1C] = 0xC2.toByte()
        bytes[0x1D] = 0x33.toByte()
        bytes[0x1E] = 0x9F.toByte()
        bytes[0x1F] = 0x3D.toByte()

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
    fun hashRom_gameCubeCisoUsesNintendoDiscPathInsteadOfGenericMd5Fallback() {
        val disc = buildGameCubeDisc()
        val ciso = buildCisoImage(disc, blockSize = 0x8000)

        assertEquals(
            GameCubeRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.gcm",
                    fileSize = disc.size.toLong(),
                    openStream = { disc.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(disc) }
                )
            ),
            hashRom(
                RomHashInput(
                    fileName = "game.ciso",
                    fileSize = ciso.size.toLong(),
                    openStream = { ciso.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(ciso) }
                )
            )
        )
    }

    @Test
    fun hashRom_gameCubeGczUsesNintendoDiscPath() {
        val disc = buildGameCubeDisc()
        val gcz = buildGczImage(disc, blockSize = 0x8000)

        assertEquals(
            GameCubeRomHashStrategy.hash(
                RomHashInput(
                    fileName = "game.gcm",
                    fileSize = disc.size.toLong(),
                    openStream = { disc.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(disc) }
                )
            ),
            hashRom(
                RomHashInput(
                    fileName = "game.gcz",
                    fileSize = gcz.size.toLong(),
                    openStream = { gcz.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(gcz) }
                )
            )
        )
    }

    @Test
    fun hashRom_wiiWbfsUsesNintendoDiscPath() {
        val disc = buildWiiDisc()
        val wbfs = buildWbfsImage(disc, wbfsSectorShift = 17)

        assertNotNull(
            hashRom(
                RomHashInput(
                    fileName = "game.wbfs",
                    fileSize = wbfs.size.toLong(),
                    openStream = { wbfs.inputStream() },
                    openDataSource = { ByteArrayRomDataSource(wbfs) }
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
    fun hashZipRom_singleSupportedEntryHashesExtractedRom() {
        val zipBytes = createZip(
            ".DS_Store" to "ignored".toByteArray(Charsets.US_ASCII),
            "games/tetris.gb" to "rom".toByteArray(Charsets.US_ASCII)
        )

        val hash = hashZipRom(
            tempDir = tempDir(),
            openArchiveStream = { zipBytes.inputStream() }
        )

        assertEquals("5f397a1e588cfe96b4aa4bab7a5b1d44", hash)
    }

    @Test
    fun hashZipRom_returnsNullWhenArchiveHasNoSupportedRom() {
        val zipBytes = createZip(
            "notes.txt" to "hello".toByteArray(Charsets.US_ASCII)
        )

        val hash = hashZipRom(
            tempDir = tempDir(),
            openArchiveStream = { zipBytes.inputStream() }
        )

        assertNull(hash)
    }

    @Test
    fun hashZipRom_returnsNullWhenArchiveHasMultipleSupportedRoms() {
        val zipBytes = createZip(
            "one.gb" to "one".toByteArray(Charsets.US_ASCII),
            "two.gbc" to "two".toByteArray(Charsets.US_ASCII)
        )

        val hash = hashZipRom(
            tempDir = tempDir(),
            openArchiveStream = { zipBytes.inputStream() }
        )

        assertNull(hash)
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

    private fun buildCisoImage(disc: ByteArray, blockSize: Int): ByteArray {
        require(blockSize > 0)
        val blockCount = (disc.size + blockSize - 1) / blockSize
        require(blockCount <= 0x7FF8)

        val image = ByteArray(0x8000 + blockCount * blockSize)
        image[0] = 'C'.code.toByte()
        image[1] = 'I'.code.toByte()
        image[2] = 'S'.code.toByte()
        image[3] = 'O'.code.toByte()
        image[4] = (blockSize and 0xFF).toByte()
        image[5] = ((blockSize shr 8) and 0xFF).toByte()
        image[6] = ((blockSize shr 16) and 0xFF).toByte()
        image[7] = ((blockSize shr 24) and 0xFF).toByte()

        repeat(blockCount) { index ->
            image[8 + index] = 1
            val sourceStart = index * blockSize
            val sourceEnd = minOf(sourceStart + blockSize, disc.size)
            disc.copyInto(
                destination = image,
                destinationOffset = 0x8000 + index * blockSize,
                startIndex = sourceStart,
                endIndex = sourceEnd
            )
        }

        return image
    }

    private fun buildGczImage(disc: ByteArray, blockSize: Int): ByteArray {
        val blockCount = (disc.size + blockSize - 1) / blockSize
        val pointers = LongArray(blockCount)
        val hashes = IntArray(blockCount)
        val blocks = ArrayList<ByteArray>(blockCount)
        var compressedOffset = 0L

        repeat(blockCount) { index ->
            val start = index * blockSize
            val end = minOf(start + blockSize, disc.size)
            val block = ByteArray(blockSize)
            disc.copyInto(block, endIndex = end, destinationOffset = 0, startIndex = start)
            pointers[index] = compressedOffset or Long.MIN_VALUE
            hashes[index] = adler32(block)
            blocks += block
            compressedOffset += block.size.toLong()
        }

        val header = ByteArray(32).apply {
            writeLittleEndianInt(this, 0, 0xB10BC001.toInt())
            writeLittleEndianInt(this, 4, 0)
            writeLittleEndianLong(this, 8, compressedOffset)
            writeLittleEndianLong(this, 16, disc.size.toLong())
            writeLittleEndianInt(this, 24, blockSize)
            writeLittleEndianInt(this, 28, blockCount)
        }

        return ByteArrayOutputStream().use { output ->
            output.write(header)
            pointers.forEach { writeLittleEndianLong(output, it) }
            hashes.forEach {
                output.write(it and 0xFF)
                output.write((it shr 8) and 0xFF)
                output.write((it shr 16) and 0xFF)
                output.write((it shr 24) and 0xFF)
            }
            blocks.forEach(output::write)
            output.toByteArray()
        }
    }

    private fun buildWiiDisc(): ByteArray {
        val disc = ByteArray(0x60000)
        disc[0x18] = 0x5D.toByte()
        disc[0x19] = 0x1C.toByte()
        disc[0x1A] = 0x9E.toByte()
        disc[0x1B] = 0xA3.toByte()
        disc[0x61] = 1
        disc[0x4E000] = 0x12
        disc[0x4E001] = 0x34
        disc[0x4E002] = 0x56
        disc[0x4E003] = 0x78

        writeBigEndianInt(disc, 0x40000, 1)
        writeBigEndianInt(disc, 0x40004, 0x10000)
        writeBigEndianInt(disc, 0x10000, 0x20000 shr 2)
        writeBigEndianInt(disc, 0x10004, 0)
        writeBigEndianInt(disc, 0x102A4, 0x200)
        writeBigEndianInt(disc, 0x102A8, 0x400 shr 2)
        writeBigEndianInt(disc, 0x102B8, 0x30000 shr 2)
        writeBigEndianInt(disc, 0x102BC, 0x8000 shr 2)

        for (index in 0 until 0x200) {
            disc[0x10400 + index] = (index and 0xFF).toByte()
        }

        writeBigEndianInt(disc, 0x30420, 0x40000 shr 2)
        writeBigEndianInt(disc, 0x40000, 0x41000 shr 2)
        writeBigEndianInt(disc, 0x40090, 4 shr 2)
        "WII!".toByteArray(Charsets.US_ASCII).copyInto(disc, 0x41000)
        return disc
    }

    private fun buildWbfsImage(disc: ByteArray, wbfsSectorShift: Int): ByteArray {
        val hdSectorShift = 9
        val hdSectorSize = 1 shl hdSectorShift
        val wbfsSectorSize = 1 shl wbfsSectorShift
        val blocksPerDisc = (disc.size + wbfsSectorSize - 1) / wbfsSectorSize
        val discInfoSize = alignUp(256 + blocksPerDisc * 2, hdSectorSize)
        val totalSize = hdSectorSize + discInfoSize + blocksPerDisc * wbfsSectorSize
        val image = ByteArray(totalSize)

        image[0] = 'W'.code.toByte()
        image[1] = 'B'.code.toByte()
        image[2] = 'F'.code.toByte()
        image[3] = 'S'.code.toByte()
        writeBigEndianInt(image, 4, totalSize / hdSectorSize)
        image[8] = hdSectorShift.toByte()
        image[9] = wbfsSectorShift.toByte()
        image[12] = 1

        repeat(blocksPerDisc) { index ->
            val tableOffset = hdSectorSize + 256 + index * 2
            val blockValue = index + 1
            image[tableOffset] = ((blockValue shr 8) and 0xFF).toByte()
            image[tableOffset + 1] = (blockValue and 0xFF).toByte()
            val sourceStart = index * wbfsSectorSize
            val sourceEnd = minOf(sourceStart + wbfsSectorSize, disc.size)
            disc.copyInto(
                destination = image,
                destinationOffset = hdSectorSize + discInfoSize + index * wbfsSectorSize,
                startIndex = sourceStart,
                endIndex = sourceEnd
            )
        }

        return image
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
    }

    private fun writeLittleEndianLong(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { index ->
            target[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun writeLittleEndianLong(output: ByteArrayOutputStream, value: Long) {
        repeat(8) { index ->
            output.write(((value shr (index * 8)) and 0xFF).toInt())
        }
    }

    private fun adler32(bytes: ByteArray): Int {
        var a = 1
        var b = 0
        bytes.forEach { byte ->
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    private fun alignUp(value: Int, alignment: Int): Int = ((value + alignment - 1) / alignment) * alignment

    private fun createZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, bytes) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun buildGameCubeDisc(): ByteArray {
        val disc = ByteArray(0x4000)
        disc[0x1C] = 0xC2.toByte()
        disc[0x1D] = 0x33.toByte()
        disc[0x1E] = 0x9F.toByte()
        disc[0x1F] = 0x3D.toByte()

        writeBigEndianInt(disc, 0x2454, 0)
        writeBigEndianInt(disc, 0x2458, 0)
        writeBigEndianInt(disc, 0x420, 0x3000)

        writeBigEndianInt(disc, 0x3000, 0x3100)
        writeBigEndianInt(disc, 0x3090, 4)
        "DOL!".toByteArray(Charsets.US_ASCII).copyInto(disc, 0x3100)
        return disc
    }

    private fun buildRvzImage(disc: ByteArray, discType: Int, compressionType: Int): ByteArray {
        val chunkSize = 0x8000
        val header1Size = 0x48
        val header2Size = 0xDC
        val rawEntries = ByteArray(0x18).apply {
            writeBigEndianLong(this, 0, 0x80)
            writeBigEndianLong(this, 8, disc.size.toLong() - 0x80)
            writeBigEndianInt(this, 16, 0)
            writeBigEndianInt(this, 20, 1)
        }
        val groupEntries = ByteArray(0x0C)
        val encodedRawEntries = when (compressionType) {
            0 -> rawEntries
            5 -> zstdCompress(rawEntries)
            else -> error("Unsupported test compression type")
        }
        val rawEntriesOffset = header1Size + header2Size
        val groupEntriesOffset = rawEntriesOffset + encodedRawEntries.size
        val groupData = when (compressionType) {
            0 -> disc
            5 -> zstdCompress(disc)
            else -> error("Unsupported test compression type")
        }
        var encodedGroupEntries = ByteArray(0)
        while (true) {
            val groupDataOffset = alignTo4(groupEntriesOffset + (if (encodedGroupEntries.isNotEmpty()) encodedGroupEntries.size else groupEntries.size))
            val dataSize = if (compressionType == 0) {
                groupData.size
            } else {
                groupData.size or Int.MIN_VALUE
            }
            writeBigEndianInt(groupEntries, 0, groupDataOffset / 4)
            writeBigEndianInt(groupEntries, 4, dataSize)
            writeBigEndianInt(groupEntries, 8, 0)
            val candidate = when (compressionType) {
                0 -> groupEntries
                5 -> zstdCompress(groupEntries)
                else -> error("Unsupported test compression type")
            }
            if (encodedGroupEntries.isNotEmpty() && candidate.size == encodedGroupEntries.size) {
                encodedGroupEntries = candidate
                break
            }
            encodedGroupEntries = candidate
            if (compressionType == 0) break
        }
        val groupDataOffset = alignTo4(groupEntriesOffset + encodedGroupEntries.size)
        writeBigEndianInt(groupEntries, 0, groupDataOffset / 4)
        encodedGroupEntries = when (compressionType) {
            0 -> groupEntries
            5 -> zstdCompress(groupEntries)
            else -> error("Unsupported test compression type")
        }

        val header2 = ByteArray(header2Size)
        writeBigEndianInt(header2, 0x00, discType)
        writeBigEndianInt(header2, 0x04, compressionType)
        writeBigEndianInt(header2, 0x0C, chunkSize)
        disc.copyOfRange(0, 0x80).copyInto(header2, 0x10)
        writeBigEndianInt(header2, 0xB4, 1)
        writeBigEndianLong(header2, 0xB8, rawEntriesOffset.toLong())
        writeBigEndianInt(header2, 0xC0, encodedRawEntries.size)
        writeBigEndianInt(header2, 0xC4, 1)
        writeBigEndianLong(header2, 0xC8, groupEntriesOffset.toLong())
        writeBigEndianInt(header2, 0xD0, encodedGroupEntries.size)
        header2[0xD4] = 0

        val header2Sha1 = MessageDigest.getInstance("SHA-1").digest(header2)
        val fileSize = groupDataOffset + groupData.size
        val header1 = ByteArray(header1Size)
        byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 0x01).copyInto(header1, 0)
        writeBigEndianInt(header1, 0x04, 0x01000000)
        writeBigEndianInt(header1, 0x08, 0x00030000)
        writeBigEndianInt(header1, 0x0C, header2Size)
        header2Sha1.copyInto(header1, 0x10)
        writeBigEndianLong(header1, 0x24, disc.size.toLong())
        writeBigEndianLong(header1, 0x2C, fileSize.toLong())
        val header1Sha1 = MessageDigest.getInstance("SHA-1").digest(header1.copyOfRange(0, 0x34))
        header1Sha1.copyInto(header1, 0x34)

        return ByteArrayOutputStream().use { output ->
            output.write(header1)
            output.write(header2)
            output.write(encodedRawEntries)
            output.write(encodedGroupEntries)
            repeat(groupDataOffset - (groupEntriesOffset + encodedGroupEntries.size)) {
                output.write(0)
            }
            output.write(groupData)
            output.toByteArray()
        }
    }

    private fun writeBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeBigEndianInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeBigEndianLong(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 56) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 48) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 40) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 32) and 0xFF).toByte()
        target[offset + 4] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 5] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 6] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 7] = (value and 0xFF).toByte()
    }

    private fun alignTo4(value: Int): Int = (value + 3) and 3.inv()

    private fun zstdCompress(input: ByteArray): ByteArray {
        return Zstd.compress(input)
    }

    private fun tempDir(): File = File(requireNotNull(System.getProperty("java.io.tmpdir")))

}
