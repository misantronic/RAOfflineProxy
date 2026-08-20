"""Tests for the Linux ROM-hashing ctypes binding.

These exercise the real ``libraproxy_rchash`` shared library through
``rom_hashing``. The library is built from rcheevos' rc_hash + the vendored
libchdr CHD reader; see ``third_party/rcheevos_glue``. Point the binding at a
built library via the ``RAOFFLINEPROXY_RCHASH_LIB`` env var (the CI/build step
compiles it). If no library can be loaded, the format tests are skipped rather
than failing, so the suite stays green on machines without the native build.
"""

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from linux.raofflineproxy import rom_browser, rom_hashing

# The stdlib has no 7z writer, so these archives are committed rather than built
# at test time. Contents are documented in each test.
FIXTURES_DIR = Path(__file__).parent / "fixtures"


def _library_available() -> bool:
    # reset cached state so an env-provided lib path is picked up
    rom_hashing._LIBRCHASH = None
    rom_hashing._LIBRCHASH_ERROR = None
    return rom_hashing.load_rchash() is not None


REQUIRE_LIB = unittest.skipUnless(
    _library_available(),
    "libraproxy_rchash not available (set RAOFFLINEPROXY_RCHASH_LIB)",
)


@REQUIRE_LIB
class CartridgeHashingTests(unittest.TestCase):
    def _write_and_hash(self, name: str, data: bytes) -> str | None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / name
            rom_path.write_bytes(data)
            return rom_hashing.hash_rom(rom_path)

    def test_nes_hash_skips_16_byte_header(self) -> None:
        body = b"NESDATA" * 64
        result = self._write_and_hash("test.nes", b"NES\x1a" + b"\x00" * 12 + body)
        self.assertEqual(result, hashlib.md5(body).hexdigest())

    def test_snes_hash_skips_512_byte_copier_header(self) -> None:
        body = b"A" * 8192
        result = self._write_and_hash("test.smc", b"H" * 512 + body)
        self.assertEqual(result, hashlib.md5(body).hexdigest())

    def test_game_boy_hashes_whole_file(self) -> None:
        body = b"GBDATA" * 100
        result = self._write_and_hash("test.gb", body)
        self.assertEqual(result, hashlib.md5(body).hexdigest())

    def test_candidates_are_ordered_and_deduped(self) -> None:
        body = b"NESDATA" * 64
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.nes"
            rom_path.write_bytes(b"NES\x1a" + b"\x00" * 12 + body)
            candidates = rom_hashing.hash_rom_candidates(rom_path)
        self.assertEqual(candidates, [hashlib.md5(body).hexdigest()])

    def test_unknown_extension_falls_back_to_whole_file_md5(self) -> None:
        # rc_hash whole-file-hashes unknown extensions as a last resort,
        # matching the proxy's previous generic-MD5 fallback behaviour.
        body = b"MYSTERYDATA" * 32
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "mystery.xyz"
            rom_path.write_bytes(body)
            result = rom_hashing.hash_rom_candidates_result(rom_path)
        self.assertEqual(result.candidates, [hashlib.md5(body).hexdigest()])


@REQUIRE_LIB
class ArcadeZipTests(unittest.TestCase):
    def test_neogeo_set_hashes_by_filename(self) -> None:
        # Neo Geo / MAME sets have no console-extension files inside, so manual
        # caching must hash them by the zip's base filename (rc_hash arcade).
        with tempfile.TemporaryDirectory() as temp_dir:
            zip_path = Path(temp_dir) / "mslug.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("201-p1.p1", b"\x00" * 16)
                archive.writestr("201-c1.c1", b"\x00" * 16)

            candidates = rom_browser.hash_candidates_for_manual_cache(zip_path)

        self.assertEqual(candidates, [hashlib.md5(b"mslug").hexdigest()])

    def test_zipped_single_console_rom_hashes_by_content(self) -> None:
        body = b"NESDATA" * 64
        with tempfile.TemporaryDirectory() as temp_dir:
            zip_path = Path(temp_dir) / "game.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("game.nes", b"NES\x1a" + b"\x00" * 12 + body)

            candidates = rom_browser.hash_candidates_for_manual_cache(zip_path)

        self.assertEqual(candidates, [hashlib.md5(body).hexdigest()])

    def test_zipped_mega_drive_and_master_system_hash_by_content(self) -> None:
        # Sega systems hash as a plain whole-file MD5; the zipped inner ROM must
        # be hashed by content even though these extensions aren't enumerated by
        # name everywhere.
        for inner in ("Sonic.md", "Sonic.gen", "AlexKidd.sms"):
            body = (inner.encode() * 256)
            with tempfile.TemporaryDirectory() as temp_dir:
                zip_path = Path(temp_dir) / "game.zip"
                with zipfile.ZipFile(zip_path, "w") as archive:
                    archive.writestr(inner, body)

                candidates = rom_browser.hash_candidates_for_manual_cache(zip_path)

            self.assertEqual(
                candidates, [hashlib.md5(body).hexdigest()], f"failed for {inner}"
            )

    def test_single_file_archive_of_unenumerated_system_uses_content(self) -> None:
        # A single-file archive is treated as a ROM regardless of extension.
        body = b"WHATEVER" * 100
        with tempfile.TemporaryDirectory() as temp_dir:
            zip_path = Path(temp_dir) / "mystery.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("game.xyz", body)

            candidates = rom_browser.hash_candidates_for_manual_cache(zip_path)

        self.assertEqual(candidates, [hashlib.md5(body).hexdigest()])


@REQUIRE_LIB
class SevenZipTests(unittest.TestCase):
    """.7z goes through the LZMA SDK reader bundled into libraproxy_rchash.

    rc_hash has no 7z support of its own — it maps the extension to the arcade
    console, which hashes the filename — so an inner console ROM only gets its
    real content hash because the glue decompresses it first.
    """

    def test_single_console_rom_hashes_by_content(self) -> None:
        # pokemon.7z holds one file: game.nes, an iNES image whose 16-byte
        # header must be skipped, proving rc_hash applied console rules to the
        # decompressed bytes rather than hashing the archive.
        body = b"NESDATA" * 64

        candidates = rom_browser.hash_candidates_for_manual_cache(
            FIXTURES_DIR / "pokemon.7z"
        )

        self.assertEqual(candidates, [hashlib.md5(body).hexdigest()])

    def test_single_file_of_unenumerated_system_uses_content(self) -> None:
        # mystery.7z holds game.xyz — no recognized ROM extension, but a
        # single-file archive is treated as a ROM, same rule as for zip.
        body = b"MYSTERY" * 40

        candidates = rom_browser.hash_candidates_for_manual_cache(
            FIXTURES_DIR / "mystery.7z"
        )

        self.assertEqual(candidates, [hashlib.md5(body).hexdigest()])

    def test_arcade_set_hashes_by_filename(self) -> None:
        # mslug.7z holds 201-p1.p1 and 201-c1.c1: a Neo Geo set, no console
        # extension inside, so the arcade rule applies to the archive name.
        candidates = rom_browser.hash_candidates_for_manual_cache(
            FIXTURES_DIR / "mslug.7z"
        )

        self.assertEqual(candidates, [hashlib.md5(b"mslug").hexdigest()])

    def test_multiple_console_roms_are_rejected(self) -> None:
        # bundle.7z holds one.gb and two.gbc; as with zip we refuse to guess.
        with self.assertRaises(ValueError) as raised:
            rom_browser.hash_candidates_for_manual_cache(FIXTURES_DIR / "bundle.7z")

        self.assertEqual(str(raised.exception), "archive contains multiple supported ROMs")

    def test_list_entries_reads_names(self) -> None:
        self.assertEqual(
            rom_hashing.list_7z_entries(FIXTURES_DIR / "bundle.7z"),
            ["one.gb", "two.gbc"],
        )

    def test_unreadable_archive_yields_no_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            broken = Path(temp_dir) / "broken.7z"
            broken.write_bytes(b"7z\xbc\xaf\x27\x1c" + b"\x00" * 64)

            self.assertEqual(rom_hashing.list_7z_entries(broken), [])
            # Still hashable: it falls back to the arcade rule on the filename.
            self.assertEqual(
                rom_browser.hash_candidates_for_manual_cache(broken),
                [hashlib.md5(b"broken").hexdigest()],
            )


class SupportedExtensionsTests(unittest.TestCase):
    def test_includes_common_formats(self) -> None:
        extensions = rom_hashing.supported_rom_extensions()
        for ext in (".nes", ".sfc", ".gb", ".chd", ".cue", ".m3u", ".iso"):
            self.assertIn(ext, extensions)


class LibraryDiscoveryTests(unittest.TestCase):
    """Distro bundles nest the package at <base>/app/raofflineproxy/ with the
    native lib at <base>/lib/, and the device launchers add that lib dir to
    LD_LIBRARY_PATH. Discovery must not depend on a single hardcoded install
    path (regression: Onion PS1 hashing failed when installed off the canonical
    /mnt/SDCARD/App/RAOfflineProxy path)."""

    def test_searches_bundle_lib_two_levels_up(self) -> None:
        here = Path(rom_hashing.__file__).resolve().parent
        bundle_lib = str(here.parent.parent / "lib" / "libraproxy_rchash.so")
        self.assertIn(bundle_lib, rom_hashing._candidate_library_paths())

    def test_includes_bare_sonames_for_ld_library_path(self) -> None:
        candidates = rom_hashing._candidate_library_paths()
        self.assertIn("libraproxy_rchash.so", candidates)
        self.assertIn("libraproxy_rchash.dylib", candidates)


if __name__ == "__main__":
    unittest.main()
