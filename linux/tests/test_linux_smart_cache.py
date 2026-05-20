import json
import tempfile
import unittest
from io import StringIO
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import main
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

    def test_find_content_history_lpl_supports_onion_current_profile_lists(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "RetroArch" / ".retroarch" / "retroarch.cfg"
            history_path = (
                root / "Saves" / "CurrentProfile" / "lists" / "content_history.lpl"
            )
            cfg_path.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            history_path.write_text('{"items":[]}', encoding="utf-8")

            result = smart_cache.find_content_history_lpl(
                {"retroarch_cfg": str(cfg_path)}
            )

            self.assertEqual(result, history_path)

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

    def test_main_smart_cache_status_outputs_json(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            rom_path = root / "roms" / "tetris.gb"
            history_path = root / "playlists" / "content_history.lpl"
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rom_path.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            rom_path.write_bytes(b"one")
            history_path.write_text(
                json.dumps({"items": [{"path": str(rom_path)}]}),
                encoding="utf-8",
            )

            stdout = StringIO()
            with mock.patch("sys.argv", ["raofflineproxy", "smart-cache-status"]):
                with mock.patch.object(
                    main, "load_config", return_value={"retroarch_cfg": str(cfg_path)}
                ):
                    with mock.patch("sys.stdout", stdout):
                        main.main()

            self.assertEqual(
                stdout.getvalue().strip(),
                '{"found_history":true,"total_candidates":1}',
            )

    def test_main_run_smart_cache_outputs_progress_and_result_json(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            cfg_path.write_text("# cfg\n", encoding="utf-8")

            stdout = StringIO()

            def fake_run_smart_cache(_storage, _config_data, _limit, on_progress=None):
                if on_progress is not None:
                    on_progress(
                        smart_cache.SmartCacheProgress(
                            scanned=1,
                            total=2,
                            cached=1,
                            current_label="tetris.gb",
                        )
                    )
                return smart_cache.SmartCacheResult(
                    scanned=2,
                    total=2,
                    cached=1,
                    skipped=1,
                    limit_reached=False,
                )

            with mock.patch("sys.argv", ["raofflineproxy", "run-smart-cache"]):
                with mock.patch.object(
                    main, "load_config", return_value={"retroarch_cfg": str(cfg_path)}
                ):
                    with mock.patch.object(
                        main,
                        "resolve_credentials",
                        return_value={"user": "u", "token": "t"},
                    ):
                        with mock.patch.object(main, "online_check", return_value=True):
                            with mock.patch.object(
                                main,
                                "run_smart_cache",
                                side_effect=fake_run_smart_cache,
                            ):
                                with mock.patch("sys.stdout", stdout):
                                    main.main()

            lines = stdout.getvalue().strip().splitlines()
            self.assertEqual(
                lines[0],
                '{"type":"progress","scanned":1,"total":2,"cached":1,"current_label":"tetris.gb"}',
            )
            self.assertEqual(
                lines[1],
                '{"type":"result","scanned":2,"total":2,"cached":1,"skipped":1,"limit_reached":false}',
            )

    def test_run_smart_cache_progress_reports_updated_cached_total(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            history_path = root / "playlists" / "content_history.lpl"
            rom_one = root / "roms" / "tetris.gb"
            rom_one.parent.mkdir(parents=True)
            history_path.parent.mkdir(parents=True)
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            rom_one.write_bytes(b"one")
            history_path.write_text(
                json.dumps({"items": [{"path": str(rom_one)}]}),
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            original_add_rom_to_cache = smart_cache.add_rom_to_cache
            progress_updates = []
            try:
                smart_cache.add_rom_to_cache = lambda _path, _store, _config: type(
                    "Result", (), {"success": True}
                )()

                smart_cache.run_smart_cache(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    limit=25,
                    on_progress=lambda progress: progress_updates.append(progress),
                )

                self.assertEqual(len(progress_updates), 1)
                self.assertEqual(progress_updates[0].cached, 1)
                self.assertEqual(progress_updates[0].scanned, 1)
            finally:
                smart_cache.add_rom_to_cache = original_add_rom_to_cache
                store.close()


if __name__ == "__main__":
    unittest.main()
