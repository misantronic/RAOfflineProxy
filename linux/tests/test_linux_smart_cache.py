import json
import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import smart_cache
from linux.raofflineproxy import storage


class LinuxSmartCacheTests(unittest.TestCase):
    def test_load_content_history_paths_parses_existing_unique_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            history_path = root / "playlists" / "content_history.lpl"
            rom_one = root / "roms" / "tetris.gb"
            rom_two = root / "roms" / "zelda.gbc"
            rom_one.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rom_one.write_bytes(b"one")
            rom_two.write_bytes(b"two")
            history_path.write_text(
                json.dumps(
                    {
                        "items": [
                            {"path": str(rom_one)},
                            {"path": str(rom_two)},
                            {"path": str(rom_one)},
                            {"path": str(root / "missing.gb")},
                        ]
                    }
                ),
                encoding="utf-8",
            )

            paths = smart_cache.load_content_history_paths(
                {"retroarch_cfg": str(cfg_path)}
            )

            self.assertEqual(paths, [rom_one, rom_two])

    def test_should_offer_smart_cache_false_when_cached_games_exist(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            store = storage.Storage(database_path=db_path)
            try:
                cfg_path.write_text("# cfg\n", encoding="utf-8")
                store.upsert_cache(
                    "patch:10701:misantronic",
                    '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                )

                status = smart_cache.should_offer_smart_cache(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    is_online=True,
                    has_credentials=True,
                )

                self.assertFalse(status.found_history)
                self.assertEqual(status.total_candidates, 0)
            finally:
                store.close()

    def test_should_offer_smart_cache_false_when_offline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            history_path = root / "playlists" / "content_history.lpl"
            rom_path = root / "roms" / "tetris.gb"
            rom_path.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rom_path.write_bytes(b"one")
            history_path.write_text(
                json.dumps({"items": [{"path": str(rom_path)}]}),
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            try:
                status = smart_cache.should_offer_smart_cache(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    is_online=False,
                    has_credentials=True,
                )

                self.assertFalse(status.found_history)
                self.assertEqual(status.total_candidates, 0)
            finally:
                store.close()

    def test_run_smart_cache_paces_between_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            history_path = root / "playlists" / "content_history.lpl"
            rom_one = root / "roms" / "tetris.gb"
            rom_two = root / "roms" / "zelda.gbc"
            rom_one.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rom_one.write_bytes(b"one")
            rom_two.write_bytes(b"two")
            history_path.write_text(
                json.dumps({"items": [{"path": str(rom_one)}, {"path": str(rom_two)}]}),
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            original_add_rom_to_cache = smart_cache.add_rom_to_cache
            original_sleep = smart_cache.time.sleep
            sleeps = []
            try:
                smart_cache.add_rom_to_cache = lambda _path, _store, _config: type(
                    "Result", (), {"success": True}
                )()
                smart_cache.time.sleep = lambda seconds: sleeps.append(seconds)

                result = smart_cache.run_smart_cache(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    limit=25,
                )

                self.assertEqual(result.cached, 2)
                self.assertEqual(sleeps, [smart_cache.SMART_CACHE_DELAY_SECONDS])
            finally:
                smart_cache.add_rom_to_cache = original_add_rom_to_cache
                smart_cache.time.sleep = original_sleep
                store.close()


if __name__ == "__main__":
    unittest.main()
