import hashlib
import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import rom_hashing


class LinuxRomHashingTests(unittest.TestCase):
    def test_nes_hash_skips_16_byte_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.nes"
            body = b"NESDATA"
            rom_path.write_bytes(b"NES\x1a" + b"\x00" * 12 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_fds_hash_skips_16_byte_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.fds"
            body = b"FDSDATA"
            rom_path.write_bytes(b"FDS\x1a" + b"\x00" * 12 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_snes_hash_skips_512_byte_copier_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.smc"
            body = b"A" * 8192
            rom_path.write_bytes(b"H" * 512 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_pce_hash_skips_512_byte_header_when_file_size_has_512_bit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.pce"
            body = b"B" * 1024
            rom_path.write_bytes(b"H" * 512 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_atari_7800_hash_skips_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.a78"
            header = bytearray(128)
            header[1:10] = b"ATARI7800"
            body = b"ROMDATA"
            rom_path.write_bytes(bytes(header) + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_atari_lynx_hash_skips_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.lnx"
            body = b"LYNXDATA"
            rom_path.write_bytes(b"LYNX" + b"\x00" * 60 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_super_cassette_vision_hash_skips_header(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.cart"
            body = b"SCVDATA"
            rom_path.write_bytes(b"EmuSCV" + b"\x00" * 26 + body)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path), hashlib.md5(body).hexdigest()
            )

    def test_n64_little_endian_hash_normalizes_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.n64"
            little_endian = bytes([0x40, 0x12, 0x37, 0x80, 0xDD, 0xCC, 0xBB, 0xAA])
            normalized = bytes([0x80, 0x37, 0x12, 0x40, 0xAA, 0xBB, 0xCC, 0xDD])
            rom_path.write_bytes(little_endian)

            self.assertEqual(
                rom_hashing.hash_rom(rom_path),
                hashlib.md5(normalized).hexdigest(),
            )

    def test_supported_extensions_include_new_formats(self) -> None:
        extensions = rom_hashing.supported_rom_extensions()

        self.assertIn(".nes", extensions)
        self.assertIn(".fds", extensions)
        self.assertIn(".smc", extensions)
        self.assertIn(".pce", extensions)
        self.assertIn(".a78", extensions)
        self.assertIn(".lnx", extensions)
        self.assertIn(".cart", extensions)
        self.assertIn(".z64", extensions)
        self.assertIn(".nds", extensions)
        self.assertIn(".iso", extensions)
        self.assertIn(".bin", extensions)
        self.assertIn(".chd", extensions)
        self.assertIn(".pbp", extensions)

    def test_psx_strategy_matches_chd(self) -> None:
        self.assertTrue(rom_hashing.PsxRomHashStrategy().matches("game.chd"))

    def test_hash_rom_candidates_chd_does_not_fall_back_to_generic_md5(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.chd"
            rom_path.write_bytes(b"plain-chd")

            original_open = rom_hashing.open_direct_chd_reader
            try:
                rom_hashing.open_direct_chd_reader = lambda _path: rom_hashing.ChdOpenResult(
                    None,
                    None,
                )
                self.assertEqual(rom_hashing.hash_rom_candidates(rom_path), [])
            finally:
                rom_hashing.open_direct_chd_reader = original_open

    def test_hash_rom_candidates_result_reports_missing_libchdr(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.chd"
            rom_path.write_bytes(b"plain-chd")

            original_open = rom_hashing.open_direct_chd_reader
            try:
                rom_hashing.open_direct_chd_reader = lambda _path: rom_hashing.ChdOpenResult(
                    None,
                    "libchdr shared library not found",
                )

                result = rom_hashing.hash_rom_candidates_result(rom_path)

                self.assertEqual(result.candidates, [])
                self.assertEqual(result.error, "libchdr shared library not found")
            finally:
                rom_hashing.open_direct_chd_reader = original_open

    def test_load_libchdr_keeps_first_real_error(self) -> None:
        original_cdll = rom_hashing.ctypes.CDLL
        original_find_library = rom_hashing.ctypes.util.find_library
        original_isfile = rom_hashing.os.path.isfile
        original_lib = rom_hashing._LIBCHDR
        original_error = rom_hashing._LIBCHDR_ERROR
        try:
            rom_hashing._LIBCHDR = None
            rom_hashing._LIBCHDR_ERROR = None
            rom_hashing.ctypes.util.find_library = lambda _name: None
            rom_hashing.os.path.isfile = lambda path: path == "/mnt/SDCARD/App/RAOfflineProxy/lib/libchdr.so"

            def fake_cdll(candidate):
                raise OSError(f"bad elf for {candidate}")

            rom_hashing.ctypes.CDLL = fake_cdll

            library = rom_hashing.load_libchdr()

            self.assertIsNone(library)
            self.assertEqual(
                rom_hashing._LIBCHDR_ERROR,
                "libchdr load failed: bad elf for /mnt/SDCARD/App/RAOfflineProxy/lib/libchdr.so",
            )
        finally:
            rom_hashing.ctypes.CDLL = original_cdll
            rom_hashing.ctypes.util.find_library = original_find_library
            rom_hashing.os.path.isfile = original_isfile
            rom_hashing._LIBCHDR = original_lib
            rom_hashing._LIBCHDR_ERROR = original_error

    def test_has_supercard_header_detects_known_pattern(self) -> None:
        header = bytearray(512)
        header[0] = 0x2E
        header[1] = 0x00
        header[2] = 0x00
        header[3] = 0xEA
        header[0xB0] = 0x44
        header[0xB1] = 0x46
        header[0xB2] = 0x96
        header[0xB3] = 0x00

        self.assertTrue(rom_hashing.has_supercard_header(bytes(header)))

    def test_parse_psx_boot_path_extracts_cdrom_path(self) -> None:
        system_cnf = "BOOT = cdrom:\\SLUS_123.45;1\n"

        self.assertEqual(
            rom_hashing.parse_psx_boot_path(system_cnf),
            "SLUS_123.45",
        )

    def test_names_equal_ignores_case_and_iso_version(self) -> None:
        self.assertTrue(rom_hashing.names_equal("SYSTEM.CNF", "system.cnf;1"))

    def test_pbp_hash_falls_back_to_generic_md5(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.pbp"
            rom_path.write_bytes(b"pbpdata")

            self.assertEqual(
                rom_hashing.hash_rom(rom_path),
                hashlib.md5(b"pbpdata").hexdigest(),
            )

    def test_gba_hash_uses_generic_md5_strategy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.gba"
            rom_path.write_bytes(b"gbadata")

            self.assertEqual(
                rom_hashing.hash_rom(rom_path),
                hashlib.md5(b"gbadata").hexdigest(),
            )

def build_test_psx_disc() -> bytes:
    sector_count = 32
    image = bytearray(sector_count * rom_hashing.RAW_2352_SECTOR)

    def write_sector(sector_index: int, payload: bytes) -> None:
        offset = sector_index * rom_hashing.RAW_2352_SECTOR + 24
        image[offset : offset + len(payload)] = payload

    pvd = bytearray(rom_hashing.ISO_SECTOR)
    pvd[0] = 1
    pvd[1:6] = b"CD001"
    pvd[128] = rom_hashing.ISO_SECTOR & 0xFF
    pvd[129] = (rom_hashing.ISO_SECTOR >> 8) & 0xFF
    root_record = make_directory_record(20, rom_hashing.ISO_SECTOR, "\x00", True, raw_name=b"\x00")
    pvd[156 : 156 + len(root_record)] = root_record
    write_sector(16, bytes(pvd))

    root_dir = bytearray(rom_hashing.ISO_SECTOR)
    offset = 0
    for record in (
        make_directory_record(20, rom_hashing.ISO_SECTOR, ".", True, raw_name=b"\x00"),
        make_directory_record(20, rom_hashing.ISO_SECTOR, "..", True, raw_name=b"\x01"),
        make_directory_record(21, 32, "SYSTEM.CNF;1", False),
        make_directory_record(22, len(build_psx_exe()), "PSX.EXE;1", False),
    ):
        root_dir[offset : offset + len(record)] = record
        offset += len(record)
    write_sector(20, bytes(root_dir))

    write_sector(21, b"BOOT = cdrom:\\PSX.EXE;1\n")
    psx_exe = build_psx_exe()
    write_sector(22, psx_exe[: rom_hashing.ISO_SECTOR])
    write_sector(23, psx_exe[rom_hashing.ISO_SECTOR :])
    return bytes(image)


def build_psx_exe() -> bytes:
    payload = b"EXE-DATA"
    header = bytearray(rom_hashing.PSX_EXE_HEADER_SIZE)
    header[: len(rom_hashing.PSX_EXE_MAGIC)] = rom_hashing.PSX_EXE_MAGIC
    header[rom_hashing.PSX_EXE_BODY_SIZE_OFFSET : rom_hashing.PSX_EXE_BODY_SIZE_OFFSET + 4] = len(payload).to_bytes(4, "little")
    return bytes(header) + payload


def make_directory_record(
    sector: int,
    size: int,
    name: str,
    is_directory: bool,
    *,
    raw_name: bytes | None = None,
) -> bytes:
    name_bytes = raw_name if raw_name is not None else name.encode("ascii")
    length = 33 + len(name_bytes)
    if len(name_bytes) % 2 == 0:
        length += 1
    record = bytearray(length)
    record[0] = length
    record[2:6] = sector.to_bytes(4, "little")
    record[6:10] = sector.to_bytes(4, "big")
    record[10:14] = size.to_bytes(4, "little")
    record[14:18] = size.to_bytes(4, "big")
    record[25] = 0x02 if is_directory else 0x00
    record[32] = len(name_bytes)
    record[33 : 33 + len(name_bytes)] = name_bytes
    return bytes(record)


class CueAndM3uHashingTests(unittest.TestCase):
    def test_cue_resolves_to_data_bin_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            bin_path = Path(temp_dir) / "game (Track 1).bin"
            bin_data = b"BINDATA"
            bin_path.write_bytes(bin_data)

            cue_path = Path(temp_dir) / "game.cue"
            cue_path.write_text(
                'FILE "game (Track 1).bin" BINARY\n'
                "  TRACK 01 MODE2/2352\n"
                "    INDEX 01 00:00:00\n"
            )

            result = rom_hashing.hash_rom_candidates_result(cue_path)
            self.assertIn(hashlib.md5(bin_data).hexdigest(), result.candidates)

    def test_cue_skips_audio_tracks_before_data_track(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            data_bin = Path(temp_dir) / "data.bin"
            data_bin.write_bytes(b"DATATRACK")

            cue_path = Path(temp_dir) / "game.cue"
            cue_path.write_text(
                'FILE "audio.bin" BINARY\n'
                "  TRACK 01 AUDIO\n"
                "    INDEX 01 00:00:00\n"
                'FILE "data.bin" BINARY\n'
                "  TRACK 02 MODE2/2352\n"
                "    INDEX 01 00:00:00\n"
            )

            result = rom_hashing.hash_rom_candidates_result(cue_path)
            self.assertIn(hashlib.md5(b"DATATRACK").hexdigest(), result.candidates)

    def test_cue_missing_bin_returns_empty_with_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cue_path = Path(temp_dir) / "game.cue"
            cue_path.write_text(
                'FILE "missing.bin" BINARY\n'
                "  TRACK 01 MODE2/2352\n"
                "    INDEX 01 00:00:00\n"
            )

            result = rom_hashing.hash_rom_candidates_result(cue_path)
            self.assertEqual(result.candidates, [])
            self.assertIsNotNone(result.error)

    def test_m3u_relative_path_resolves_to_cue_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            bin_path = Path(temp_dir) / "disc1 (Track 1).bin"
            bin_path.write_bytes(b"DISC1DATA")

            cue_path = Path(temp_dir) / "disc1.cue"
            cue_path.write_text(
                'FILE "disc1 (Track 1).bin" BINARY\n'
                "  TRACK 01 MODE2/2352\n"
                "    INDEX 01 00:00:00\n"
            )

            m3u_path = Path(temp_dir) / "game.m3u"
            m3u_path.write_text("disc1.cue\n")

            result = rom_hashing.hash_rom_candidates_result(m3u_path)
            self.assertIn(hashlib.md5(b"DISC1DATA").hexdigest(), result.candidates)

    def test_m3u_skips_comments_and_blank_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            bin_path = Path(temp_dir) / "game (Track 1).bin"
            bin_path.write_bytes(b"GAMEDATA")

            cue_path = Path(temp_dir) / "game.cue"
            cue_path.write_text(
                'FILE "game (Track 1).bin" BINARY\n'
                "  TRACK 01 MODE2/2352\n"
                "    INDEX 01 00:00:00\n"
            )

            m3u_path = Path(temp_dir) / "game.m3u"
            m3u_path.write_text(
                "# EXTM3U\n"
                "\n"
                "# disc 1\n"
                "game.cue\n"
            )

            result = rom_hashing.hash_rom_candidates_result(m3u_path)
            self.assertIn(hashlib.md5(b"GAMEDATA").hexdigest(), result.candidates)

    def test_m3u_empty_returns_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            m3u_path = Path(temp_dir) / "game.m3u"
            m3u_path.write_text("# only comments\n\n")

            result = rom_hashing.hash_rom_candidates_result(m3u_path)
            self.assertEqual(result.candidates, [])
            self.assertIsNotNone(result.error)


if __name__ == "__main__":
    unittest.main()
