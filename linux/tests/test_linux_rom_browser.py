import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import platform
from linux.raofflineproxy import rom_browser
from linux.raofflineproxy import storage


class LinuxRomBrowserTests(unittest.TestCase):
    def test_read_retroarch_cfg_values_parses_quoted_values(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            cfg_path.write_text(
                'rgui_browser_directory = "/userdata/roms"\ncontent_directory = "/games"\n',
                encoding="utf-8",
            )

            values = platform.read_retroarch_cfg_values(cfg_path)

            self.assertEqual(values["rgui_browser_directory"], "/userdata/roms")
            self.assertEqual(values["content_directory"], "/games")

    def test_list_browser_entries_filters_supported_roms(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "subdir").mkdir()
            (root / "pokemon.gba").write_bytes(b"gba")
            (root / "zelda.gbc").write_bytes(b"gbc")
            (root / "notes.txt").write_text("skip", encoding="utf-8")

            entries = rom_browser.list_browser_entries(root)

            self.assertEqual(
                [entry.name for entry in entries],
                ["pokemon.gba", "zelda.gbc"],
            )

    def test_list_browser_entries_hides_empty_directory_trees(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "empty-tree" / "nested").mkdir(parents=True)
            (root / "rom-tree" / "nested").mkdir(parents=True)
            (root / "rom-tree" / "nested" / "metroid.gba").write_bytes(b"gba")

            entries = rom_browser.list_browser_entries(root)

            self.assertEqual([entry.name for entry in entries], ["rom-tree"])

    def test_hash_rom_file_uses_md5(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rom_path = Path(temp_dir) / "test.gb"
            rom_path.write_bytes(b"abc")

            self.assertEqual(
                rom_browser.hash_rom(rom_path),
                "900150983cd24fb0d6963f7d28e17f72",
            )

    def test_fetch_game_id_persists_cache_entry(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            original_http_get = rom_browser.http_get
            try:
                rom_browser.http_get = lambda _url, _ua: '{"GameID": 10701}'

                game_id = rom_browser.fetch_game_id(
                    "abcd",
                    {"user": "misantronic", "token": "token"},
                    "RetroArch/1.20.0",
                    {},
                    store,
                )

                self.assertEqual(game_id, 10701)
                cache_entry = store.get_cache("gameid:abcd")
                self.assertIsNotNone(cache_entry)
            finally:
                rom_browser.http_get = original_http_get
                store.close()

    def test_remove_cached_game_deletes_related_cache_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache("patch:10701:misantronic", "patch")
                store.upsert_cache("unlocks:10701:misantronic:0", "unlocks")
                store.upsert_cache("startsession:10701:misantronic:0", "session")

                rom_browser.remove_cached_game(store, 10701)

                self.assertEqual(store.get_all_cache_by_prefix("patch:10701:"), [])
                self.assertEqual(store.get_all_cache_by_prefix("unlocks:10701:"), [])
                self.assertEqual(
                    store.get_all_cache_by_prefix("startsession:10701:"),
                    [],
                )
            finally:
                store.close()

    def test_normalize_preview_url_builds_absolute_url(self) -> None:
        self.assertEqual(
            rom_browser.normalize_preview_url("/Images/012345.png"),
            "https://retroachievements.org/Images/012345.png",
        )


if __name__ == "__main__":
    unittest.main()
