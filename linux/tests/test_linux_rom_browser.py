import json
import tempfile
import unittest
import zipfile
from io import StringIO
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import platform
from linux.raofflineproxy import image_cache
from linux.raofflineproxy import main
from linux.raofflineproxy import proxy_service
from linux.raofflineproxy import rom_browser
from linux.raofflineproxy import rom_cache
from linux.raofflineproxy import storage
from linux.raofflineproxy import smart_cache
from linux.raofflineproxy import cache_keys


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

    def test_list_browser_entries_includes_zip_with_supported_rom(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            zip_path = root / "pokemon.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("pokemon.gba", b"gba")

            entries = rom_browser.list_browser_entries(root)

            self.assertEqual([entry.name for entry in entries], ["pokemon.zip"])

    def test_list_browser_entries_ignores_zip_without_supported_rom(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            zip_path = root / "notes.zip"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("notes.txt", b"skip")

            entries = rom_browser.list_browser_entries(root)

            self.assertEqual(entries, [])

    def test_list_browser_entries_hides_empty_directory_trees(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "empty-tree" / "nested").mkdir(parents=True)
            (root / "rom-tree" / "nested").mkdir(parents=True)
            (root / "rom-tree" / "nested" / "metroid.gba").write_bytes(b"gba")

            entries = rom_browser.list_browser_entries(root)

            self.assertEqual([entry.name for entry in entries], ["rom-tree"])

    def test_list_browser_entries_fast_keeps_directories_without_recursive_checks(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "empty-tree").mkdir()
            (root / "rom-tree" / "nested").mkdir(parents=True)
            (root / "rom-tree" / "nested" / "metroid.gba").write_bytes(b"gba")

            entries = rom_browser.list_browser_entries_fast(root)

            self.assertEqual(
                [entry.name for entry in entries],
                ["empty-tree", "rom-tree"],
            )

    def test_list_browser_entries_fast_excludes_imgs_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "Imgs").mkdir()
            (root / "GBA").mkdir()

            entries = rom_browser.list_browser_entries_fast(root)

            self.assertEqual([entry.name for entry in entries], ["GBA"])

    def test_list_browser_files_fast_returns_only_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "GBA").mkdir()
            (root / "tetris.gb").write_bytes(b"gb")
            (root / "mario.gba").write_bytes(b"gba")

            entries = rom_browser.list_browser_files_fast(root)

            self.assertEqual(
                [entry.name for entry in entries], ["mario.gba", "tetris.gb"]
            )

    def test_describe_browser_entries_marks_cached_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "tetris.gb"
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_hash_candidates = rom_browser.hash_rom_candidates
            try:
                rom_browser.hash_rom_candidates = lambda _path: ["cached-hash"]
                store.upsert_cache(
                    cache_keys.game_id("cached-hash"),
                    '{"GameID":10701}',
                )
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                )

                entries = rom_browser.describe_browser_entries(root, store)

                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0].name, "tetris.gb")
                self.assertTrue(entries[0].is_cached)
            finally:
                rom_browser.hash_rom_candidates = original_hash_candidates
                store.close()

    def test_cached_unlock_counts_merges_unlocks_and_startsession_once(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Title": "Tetris",
                                "Achievements": [
                                    {"ID": 1, "Title": "A"},
                                    {"ID": 2, "Title": "B"},
                                    {"ID": 3, "Title": "C"},
                                ],
                            },
                        },
                        separators=(",", ":"),
                    ),
                )
                store.upsert_cache(
                    cache_keys.patch(204, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Title": "Metroid",
                                "Achievements": [
                                    {"ID": 7, "Title": "X"},
                                ],
                            },
                        },
                        separators=(",", ":"),
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1,2]}',
                )
                store.upsert_cache(
                    cache_keys.start_session(204, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":7,"When":1}]}',
                )

                counts = rom_browser.cached_unlock_counts(store)

                self.assertEqual(counts[10701], 2)
                self.assertEqual(counts[204], 1)
            finally:
                store.close()

    def test_resolve_rom_root_falls_back_to_onion_roms_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            onion_roms = root / "Roms"
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            onion_roms.mkdir()
            original_onion_root = platform.DEFAULT_ONION_ROMS_ROOT
            try:
                platform.DEFAULT_ONION_ROMS_ROOT = onion_roms

                resolved = platform.resolve_rom_root({"retroarch_cfg": str(cfg_path)})

                self.assertEqual(resolved, onion_roms)
            finally:
                platform.DEFAULT_ONION_ROMS_ROOT = original_onion_root

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

    def test_add_rom_to_cache_uses_retroarch_cfg_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            rom_path = root / "tetris.gb"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_token = "token"\n',
                encoding="utf-8",
            )
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_hash_rom_candidates = rom_browser.hash_rom_candidates
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom_candidates = lambda _path: ["abcd"]
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }

                def fake_fetch_game_id(
                    _hash, credentials, _user_agent, _config_data, cache_store
                ):
                    self.assertEqual(
                        credentials, {"user": "misantronic", "token": "token"}
                    )
                    cache_store.upsert_cache(
                        cache_keys.game_id("abcd"),
                        '{"GameID":10701}',
                    )
                    return 10701

                def fake_cache_game(
                    game_id,
                    hash_value,
                    credentials,
                    _user_agent,
                    cache_store,
                    _config_data,
                ):
                    self.assertEqual(hash_value, "abcd")
                    cache_store.upsert_cache(
                        cache_keys.patch(game_id, credentials["user"]),
                        '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                    )

                rom_browser.fetch_game_id = fake_fetch_game_id
                rom_browser.cache_game = fake_cache_game

                result = rom_browser.add_rom_to_cache(
                    rom_path,
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                )

                self.assertTrue(result.success)
                cached_patch = store.get_cache("patch:10701:misantronic")
                self.assertIsNotNone(cached_patch)
                self.assertEqual(
                    cached_patch["sourceRomPath"],
                    f"/{root.name}/tetris.gb",
                )
            finally:
                rom_browser.hash_rom_candidates = original_hash_rom_candidates
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_normalize_cached_rom_path_keeps_last_two_segments(self) -> None:
        self.assertEqual(
            rom_browser.normalize_cached_rom_path("/mnt/roms/gb/Tetris.gb"),
            "/gb/Tetris.gb",
        )
        self.assertEqual(
            rom_browser.normalize_cached_rom_path(r"C:\ROMs\GBA\Metroid Fusion.gba"),
            "/GBA/Metroid Fusion.gba",
        )
        self.assertEqual(
            rom_browser.normalize_cached_rom_path("Tetris.gb"),
            "/Tetris.gb",
        )

    def test_add_rom_to_cache_fails_when_patch_entry_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            rom_path = root / "tetris.gb"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_token = "token"\n',
                encoding="utf-8",
            )
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_hash_rom_candidates = rom_browser.hash_rom_candidates
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom_candidates = lambda _path: ["abcd"]
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )
                rom_browser.cache_game = lambda *_args: None

                result = rom_browser.add_rom_to_cache(
                    rom_path,
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                )

                self.assertFalse(result.success)
                self.assertEqual(
                    result.message,
                    "Caching failed: patch data was not stored",
                )
            finally:
                rom_browser.hash_rom_candidates = original_hash_rom_candidates
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_add_rom_to_cache_reports_patch_api_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            rom_path = root / "tetris.gb"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_token = "bad-token"\n',
                encoding="utf-8",
            )
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_hash_rom_candidates = rom_browser.hash_rom_candidates
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom_candidates = lambda _path: ["abcd"]
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "bad-token",
                }
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )
                rom_browser.cache_game = lambda *args: (_ for _ in ()).throw(
                    rom_cache.CacheGameError("patch failed: invalid credentials")
                )

                result = rom_browser.add_rom_to_cache(
                    rom_path,
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                )

                self.assertFalse(result.success)
                self.assertEqual(
                    result.message,
                    "Caching failed: patch failed: invalid credentials",
                )
            finally:
                rom_browser.hash_rom_candidates = original_hash_rom_candidates
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_add_zip_rom_to_cache_uses_single_supported_entry(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "tetris.zip"
            with zipfile.ZipFile(rom_path, "w") as archive:
                archive.writestr("tetris.gb", b"rom")
            store = storage.Storage(database_path=db_path)
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }

                def fake_fetch_game_id(
                    hash_value, credentials, _user_agent, _config_data, cache_store
                ):
                    self.assertEqual(hash_value, "5f397a1e588cfe96b4aa4bab7a5b1d44")
                    self.assertEqual(
                        credentials, {"user": "misantronic", "token": "token"}
                    )
                    cache_store.upsert_cache(
                        cache_keys.game_id(hash_value),
                        '{"GameID":10701}',
                    )
                    return 10701

                def fake_cache_game(
                    game_id,
                    hash_value,
                    credentials,
                    _user_agent,
                    cache_store,
                    _config_data,
                ):
                    self.assertEqual(hash_value, "5f397a1e588cfe96b4aa4bab7a5b1d44")
                    cache_store.upsert_cache(
                        cache_keys.patch(game_id, credentials["user"]),
                        '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                    )

                rom_browser.fetch_game_id = fake_fetch_game_id
                rom_browser.cache_game = fake_cache_game

                result = rom_browser.add_rom_to_cache(rom_path, store, {})

                self.assertTrue(result.success)
                self.assertEqual(result.message, "Cached Tetris")
            finally:
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_add_zip_rom_to_cache_fails_for_multiple_supported_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "bundle.zip"
            with zipfile.ZipFile(rom_path, "w") as archive:
                archive.writestr("one.gb", b"one")
                archive.writestr("two.gbc", b"two")
            store = storage.Storage(database_path=db_path)
            original_resolve_credentials = rom_browser.resolve_credentials
            try:
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }

                result = rom_browser.add_rom_to_cache(rom_path, store, {})

                self.assertFalse(result.success)
                self.assertEqual(
                    result.message,
                    "Hash failed: archive contains multiple supported ROMs",
                )
            finally:
                rom_browser.resolve_credentials = original_resolve_credentials
                store.close()

    def test_cached_unlock_count_includes_pending_awards(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"UserUnlocks":[1]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 0,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 0,
                    }
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 2)
            finally:
                store.close()

    def test_cached_unlock_count_falls_back_to_start_session_unlocks(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":1,"When":1}]}',
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 1)
            finally:
                store.close()

    def test_cached_unlock_count_ignores_warning_achievement(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 101000001, "Title": "Warning: Softcore Only"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1,101000001]}',
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 1)
                self.assertEqual(rom_browser.cached_unlock_titles(store, 10701), ["First Steps"])
            finally:
                store.close()

    def test_cached_unlock_titles_include_pending_awards(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"UserUnlocks":[1]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 0,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 0,
                    }
                )

                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps", "Commander"],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_include_pending_awards_with_dict_achievements(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": {
                                    "1": {"ID": 1, "Title": "First Steps"},
                                    "2": {"ID": 2, "Title": "Commander"},
                                }
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"UserUnlocks":[1]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 0,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 0,
                    }
                )

                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps", "Commander"],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_fall_back_to_start_session_unlocks(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":1,"When":1}]}',
                )

                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps"],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_merge_pending_awards_with_start_session_fallback(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":1,"When":1}]}',
                )
                store.upsert_pending_award(
                    {
                        "achievementId": 2,
                        "queryString": "/dorequest.php?r=awardachievement",
                        "requestBody": "a=2&u=misantronic&h=0",
                        "userAgent": "RetroArch/1.20.0",
                        "queuedAt": 0,
                        "retryCount": 0,
                        "lastError": None,
                        "status": "pending",
                        "payloadHash": "hash-2",
                        "prevHash": "hash-1",
                        "signature": "sig",
                        "signedAt": 0,
                    }
                )

                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps", "Commander"],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_use_achievementsets_when_patch_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.achievementsets(
                        "3e399fdc568d0a0e140a5a277a5c32f3", "misantronic"
                    ),
                    json.dumps(
                        {
                            "Success": True,
                            "GameId": 10701,
                            "Title": "Tetris",
                            "Achievements": {
                                "1": {"ID": 1, "Title": "First Steps"},
                                "2": {"ID": 2, "Title": "Commander"},
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":1,"When":1},{"ID":2,"When":2}]}',
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 2)
                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps", "Commander"],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_use_nested_achievementsets_sets_when_patch_missing(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.achievementsets(
                        "0e5f788550ca1fad8d4e5034d9964307", "misantronic"
                    ),
                    json.dumps(
                        {
                            "Success": True,
                            "GameId": 10701,
                            "Title": "~Test Kit~ SNES Burn-in Test Cartridge",
                            "Sets": [
                                {
                                    "Type": "core",
                                    "AchievementSetId": 4479,
                                    "GameId": 10701,
                                    "Achievements": [
                                        {
                                            "ID": 52112,
                                            "Title": "I Can Hear!",
                                            "BadgeName": "53693",
                                        },
                                        {
                                            "ID": 52115,
                                            "Title": "WORK RAM PASS",
                                            "BadgeName": "53696",
                                        },
                                        {
                                            "ID": 52116,
                                            "Title": 'Say "Aaaaaaaah"',
                                            "BadgeName": "53697",
                                        },
                                        {
                                            "ID": 52117,
                                            "Title": "Official RetroAchievements License",
                                            "BadgeName": "53703",
                                        },
                                    ],
                                }
                            ],
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "ServerNow": 1779230395,
                            "HardcoreUnlocks": [],
                            "Unlocks": [
                                {"ID": 52112, "When": 1778445265},
                                {"ID": 52115, "When": 1778426162},
                                {"ID": 52116, "When": 1778426236},
                                {"ID": 52117, "When": 1778445300},
                            ],
                        }
                    ),
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 4)
                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    [
                        "I Can Hear!",
                        "WORK RAM PASS",
                        'Say "Aaaaaaaah"',
                        "Official RetroAchievements License",
                    ],
                )
            finally:
                store.close()

    def test_cached_unlock_titles_prefer_unlocks_over_start_session(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    json.dumps(
                        {
                            "Success": True,
                            "PatchData": {
                                "Achievements": [
                                    {"ID": 1, "Title": "First Steps"},
                                    {"ID": 2, "Title": "Commander"},
                                    {"ID": 3, "Title": "Champion"},
                                ]
                            },
                        }
                    ),
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1]}',
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":2,"When":1},{"ID":3,"When":2}]}',
                )

                self.assertEqual(rom_browser.cached_unlock_count(store, 10701), 1)
                self.assertEqual(
                    rom_browser.cached_unlock_titles(store, 10701),
                    ["First Steps"],
                )
            finally:
                store.close()

    def test_remove_cached_game_deletes_all_related_game_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.game_id("abc123"),
                    '{"Success":true,"GameID":10701}',
                )
                store.upsert_cache(
                    cache_keys.patch(10701, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                )
                store.upsert_cache(
                    cache_keys.unlocks(10701, "misantronic"),
                    '{"Success":true,"UserUnlocks":[1]}',
                )
                store.upsert_cache(
                    cache_keys.start_session(10701, "misantronic"),
                    '{"Success":true,"Unlocks":[{"ID":1,"When":1}]}',
                )
                store.upsert_cache(
                    cache_keys.achievementsets("abc123", "misantronic"),
                    '{"Success":true,"GameId":10701,"Title":"Tetris"}',
                )
                store.upsert_cache(
                    cache_keys.patch(506, "misantronic"),
                    '{"Success":true,"PatchData":{"Title":"Advance Wars"}}',
                )

                rom_browser.remove_cached_game(store, 10701)

                remaining_keys = {
                    entry["cacheKey"] for entry in store.get_all_cache_by_prefix("")
                }
                self.assertNotIn(cache_keys.game_id("abc123"), remaining_keys)
                self.assertNotIn(cache_keys.patch(10701, "misantronic"), remaining_keys)
                self.assertNotIn(
                    cache_keys.unlocks(10701, "misantronic"), remaining_keys
                )
                self.assertNotIn(
                    cache_keys.start_session(10701, "misantronic"), remaining_keys
                )
                self.assertNotIn(
                    cache_keys.achievementsets("abc123", "misantronic"),
                    remaining_keys,
                )
                self.assertIn(cache_keys.patch(506, "misantronic"), remaining_keys)
            finally:
                store.close()

    def test_add_rom_to_cache_persists_hash_aliases_for_offline_gameid(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "tetris.gb"
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_resolve_credentials = rom_browser.resolve_credentials
            original_hash_candidates = rom_browser.hash_rom_candidates
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }
                rom_browser.hash_rom_candidates = lambda _path: ["primary", "retroarch"]
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )

                def fake_cache_game(
                    game_id,
                    hash_value,
                    credentials,
                    _user_agent,
                    cache_store,
                    _config_data,
                ):
                    self.assertEqual(hash_value, "primary")
                    cache_store.upsert_cache(
                        cache_keys.patch(game_id, credentials["user"]),
                        '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                    )

                rom_browser.cache_game = fake_cache_game

                result = rom_browser.add_rom_to_cache(rom_path, store, {})
                runtime = object.__new__(proxy_service.ProxyRuntimeServer)
                runtime.storage = store
                response = runtime.handle_offline_request(
                    "/dorequest.php?r=gameid&m=retroarch&u=misantronic&t=token",
                    "",
                    "gameid",
                )

                self.assertTrue(result.success)
                self.assertIn(b'"GameID":10701', response)
            finally:
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.hash_rom_candidates = original_hash_candidates
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_add_rom_to_cache_stores_hash_scoped_achievementsets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "tetris.gb"
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_resolve_credentials = rom_browser.resolve_credentials
            original_hash_candidates = rom_browser.hash_rom_candidates
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }
                rom_browser.hash_rom_candidates = lambda _path: [
                    "3e399fdc568d0a0e140a5a277a5c32f3"
                ]
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )

                def fake_cache_game(
                    game_id,
                    hash_value,
                    credentials,
                    _user_agent,
                    cache_store,
                    _config_data,
                ):
                    self.assertEqual(game_id, 10701)
                    self.assertEqual(hash_value, "3e399fdc568d0a0e140a5a277a5c32f3")
                    cache_store.upsert_cache(
                        cache_keys.patch(game_id, credentials["user"]),
                        '{"Success":true,"PatchData":{"Title":"Tetris"}}',
                    )
                    cache_store.upsert_cache(
                        cache_keys.achievementsets(hash_value, credentials["user"]),
                        '{"Success":true,"GameId":10701,"Title":"Tetris"}',
                    )

                rom_browser.cache_game = fake_cache_game

                result = rom_browser.add_rom_to_cache(rom_path, store, {})

                self.assertTrue(result.success)
                self.assertIsNotNone(
                    store.get_cache(
                        cache_keys.achievementsets(
                            "3e399fdc568d0a0e140a5a277a5c32f3", "misantronic"
                        )
                    )
                )
            finally:
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.hash_rom_candidates = original_hash_candidates
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

    def test_add_rom_to_cache_respects_hundred_game_limit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            rom_path = root / "tetris.gb"
            rom_path.write_bytes(b"rom")
            store = storage.Storage(database_path=db_path)
            original_resolve_credentials = rom_browser.resolve_credentials
            original_hash_rom_candidates = rom_browser.hash_rom_candidates
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                for game_id in range(1, 101):
                    store.upsert_cache(
                        cache_keys.patch(game_id, "misantronic"),
                        json.dumps(
                            {
                                "Success": True,
                                "PatchData": {"Title": f"Game {game_id}"},
                            },
                            separators=(",", ":"),
                        ),
                    )

                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }
                rom_browser.hash_rom_candidates = lambda _path: ["abcd"]
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )
                rom_browser.cache_game = lambda *args: None

                result = rom_browser.add_rom_to_cache(rom_path, store, {})

                self.assertFalse(result.success)
                self.assertEqual(result.message, "Cache limit reached: 100 / 100")
            finally:
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.hash_rom_candidates = original_hash_rom_candidates
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
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

    def test_remove_cached_game_deletes_cached_images(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            image_dir = image_cache.game_image_dir(10701)
            image_dir.mkdir(parents=True, exist_ok=True)
            (image_dir / "icon.png").write_bytes(b"png")
            store = storage.Storage(database_path=db_path)
            try:
                rom_browser.remove_cached_game(store, 10701)

                self.assertFalse(image_dir.exists())
            finally:
                store.close()

    def test_clear_cached_games_deletes_cached_images(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "test.sqlite3"
            static_dir = image_cache.STATIC_DIR / "Badge"
            static_dir.mkdir(parents=True, exist_ok=True)
            (static_dir / "test.png").write_bytes(b"png")
            store = storage.Storage(database_path=db_path)
            try:
                rom_browser.clear_cached_games(store)

                self.assertFalse(image_cache.IMAGE_CACHE_DIR.exists())
            finally:
                store.close()

    def test_normalize_preview_url_builds_absolute_url(self) -> None:
        self.assertEqual(
            rom_browser.normalize_preview_url("/Images/012345.png"),
            "https://retroachievements.org/Images/012345.png",
        )

    def test_main_browser_root_outputs_resolved_root(self) -> None:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", "browser-root"]):
            with mock.patch.object(main, "load_config", return_value={}):
                with mock.patch.object(
                    main, "resolve_rom_root", return_value=Path("/tmp/Roms")
                ):
                    with mock.patch("sys.stdout", stdout):
                        main.main()

        self.assertEqual(stdout.getvalue().strip(), "/tmp/Roms")

    def test_main_browser_list_outputs_entry_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            stdout = StringIO()
            with mock.patch(
                "sys.argv", ["raofflineproxy", "browser-list", "--path", str(root)]
            ):
                with mock.patch.object(main, "load_config", return_value={}):
                    with mock.patch.object(
                        main,
                        "describe_browser_entries",
                        return_value=[
                            rom_browser.BrowserEntry(
                                path=root / "Games",
                                name="Games",
                                is_dir=True,
                                is_cached=False,
                            ),
                            rom_browser.BrowserEntry(
                                path=root / "tetris.gb",
                                name="tetris.gb",
                                is_dir=False,
                                is_cached=True,
                            ),
                        ],
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

            self.assertEqual(
                stdout.getvalue().strip().splitlines(),
                [
                    f"1\t0\t{root / 'Games'}\tGames",
                    f"0\t1\t{root / 'tetris.gb'}\ttetris.gb",
                ],
            )

    def test_main_browser_list_fast_outputs_entry_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            stdout = StringIO()
            with mock.patch(
                "sys.argv", ["raofflineproxy", "browser-list-fast", "--path", str(root)]
            ):
                with mock.patch.object(main, "load_config", return_value={}):
                    with mock.patch.object(
                        main,
                        "describe_browser_entries_fast",
                        return_value=[
                            rom_browser.BrowserEntry(
                                path=root / "GBA",
                                name="GBA",
                                is_dir=True,
                                is_cached=False,
                            ),
                            rom_browser.BrowserEntry(
                                path=root / "tetris.gba",
                                name="tetris.gba",
                                is_dir=False,
                                is_cached=False,
                            ),
                        ],
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

            self.assertEqual(
                stdout.getvalue().strip().splitlines(),
                [
                    f"1\t0\t{root / 'GBA'}\tGBA",
                    f"0\t0\t{root / 'tetris.gba'}\ttetris.gba",
                ],
            )

    def test_main_cached_games_outputs_unlock_counts(self) -> None:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", "cached-games"]):
            with mock.patch.object(main, "load_config", return_value={}):
                with mock.patch.object(
                    main,
                    "list_cached_games",
                    return_value=[
                        rom_browser.CachedGameEntry(game_id=10701, title="Tetris"),
                        rom_browser.CachedGameEntry(game_id=204, title="Metroid"),
                    ],
                ):
                    with mock.patch.object(
                        main,
                        "cached_unlock_counts",
                        return_value={10701: 3},
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

        self.assertEqual(
            stdout.getvalue().strip().splitlines(),
            [
                "Tetris (3 unlocks) ##GAMEID:10701",
                "Metroid ##GAMEID:204",
            ],
        )

    def test_main_remove_cached_game_prints_result_message(self) -> None:
        stdout = StringIO()
        with mock.patch(
            "sys.argv", ["raofflineproxy", "remove-cached-game", "--game-id", "10701"]
        ):
            with mock.patch.object(main, "load_config", return_value={}):
                with mock.patch.object(main, "remove_cached_game"):
                    with mock.patch("sys.stdout", stdout):
                        main.main()

        self.assertEqual(stdout.getvalue().strip(), "Removed cached game 10701")

    def test_run_folder_cache_caches_only_listed_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            (root / "GBA").mkdir()
            (root / "tetris.gb").write_bytes(b"gb")
            (root / "mario.gba").write_bytes(b"gba")
            store = storage.Storage(database_path=db_path)
            original_add_rom_to_cache = smart_cache.add_rom_to_cache
            try:
                cached_paths = []

                def fake_add_rom_to_cache(path, _storage, _config_data):
                    cached_paths.append(path.name)
                    return rom_browser.AddRomResult(True, f"Cached {path.name}")

                smart_cache.add_rom_to_cache = fake_add_rom_to_cache

                result = smart_cache.run_folder_cache(store, {}, root)

                self.assertEqual(result.total, 2)
                self.assertEqual(result.scanned, 2)
                self.assertEqual(result.cached, 2)
                self.assertEqual(result.skipped, 0)
                self.assertEqual(cached_paths, ["mario.gba", "tetris.gb"])
            finally:
                smart_cache.add_rom_to_cache = original_add_rom_to_cache
                store.close()

    def test_run_folder_cache_returns_empty_result_for_no_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            (root / "GBA").mkdir()
            store = storage.Storage(database_path=db_path)
            try:
                result = smart_cache.run_folder_cache(store, {}, root)

                self.assertEqual(result.total, 0)
                self.assertEqual(result.scanned, 0)
                self.assertEqual(result.cached, 0)
            finally:
                store.close()

    def test_main_cache_rom_prints_result_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            rom_path = root / "tetris.gb"
            cfg_path = root / "retroarch.cfg"
            rom_path.write_bytes(b"rom")
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            stdout = StringIO()
            with mock.patch(
                "sys.argv", ["raofflineproxy", "cache-rom", "--path", str(rom_path)]
            ):
                with mock.patch.object(
                    main,
                    "load_config",
                    return_value={"retroarch_cfg": str(cfg_path)},
                ):
                    with mock.patch.object(
                        main,
                        "add_rom_to_cache",
                        return_value=rom_browser.AddRomResult(True, "Cached Tetris"),
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

            self.assertEqual(stdout.getvalue().strip(), "Cached Tetris")

    def test_main_cache_folder_listing_prints_result_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg_path = root / "retroarch.cfg"
            cfg_path.write_text("# cfg\n", encoding="utf-8")
            stdout = StringIO()
            with mock.patch(
                "sys.argv",
                ["raofflineproxy", "cache-folder-listing", "--path", str(root)],
            ):
                with mock.patch.object(
                    main,
                    "load_config",
                    return_value={"retroarch_cfg": str(cfg_path)},
                ):
                    with mock.patch.object(
                        main,
                        "run_folder_cache",
                        return_value=smart_cache.SmartCacheResult(
                            2,
                            2,
                            2,
                            0,
                            False,
                        ),
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

            self.assertEqual(
                stdout.getvalue().strip(),
                '{"type":"result","scanned":2,"total":2,"cached":2,"skipped":0,"limit_reached":false}',
            )

    def test_main_service_status_prints_service_line(self) -> None:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", "service-status"]):
            with mock.patch.object(main, "load_config", return_value={}):
                with mock.patch.object(
                    main,
                    "service_status",
                    return_value={"running": True, "pid": 1234},
                ):
                    with mock.patch("sys.stdout", stdout):
                        main.main()

        self.assertEqual(stdout.getvalue().strip(), "Service running: yes | PID: 1234")

    def test_main_initializes_logging_for_non_service_commands(self) -> None:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", "service-status"]):
            with mock.patch.object(main, "configure_logging") as configure_logging:
                with mock.patch.object(main, "load_config", return_value={}):
                    with mock.patch.object(
                        main,
                        "service_status",
                        return_value={"running": False, "pid": None},
                    ):
                        with mock.patch("sys.stdout", stdout):
                            main.main()

        configure_logging.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
