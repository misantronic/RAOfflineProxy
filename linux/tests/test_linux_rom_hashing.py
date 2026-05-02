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
        self.assertIn(".pbp", extensions)

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


if __name__ == "__main__":
    unittest.main()
