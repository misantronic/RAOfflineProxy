import json
import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import platform
from linux.raofflineproxy import proxy_service
from linux.raofflineproxy import rom_browser
from linux.raofflineproxy import rom_cache
from linux.raofflineproxy import storage
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
            original_hash_rom = rom_browser.hash_rom
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom = lambda _path: "abcd"
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
                    game_id, credentials, _user_agent, cache_store, _config_data
                ):
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
                self.assertIsNotNone(store.get_cache("patch:10701:misantronic"))
            finally:
                rom_browser.hash_rom = original_hash_rom
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
                store.close()

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
            original_hash_rom = rom_browser.hash_rom
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom = lambda _path: "abcd"
                rom_browser.resolve_credentials = lambda _store, _config, _ua: {
                    "user": "misantronic",
                    "token": "token",
                }
                rom_browser.fetch_game_id = (
                    lambda _hash, _credentials, _user_agent, _config_data, _store: 10701
                )
                rom_browser.cache_game = (
                    lambda _game_id, _credentials, _user_agent, _store, _config_data: (
                        None
                    )
                )

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
                rom_browser.hash_rom = original_hash_rom
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
            original_hash_rom = rom_browser.hash_rom
            original_resolve_credentials = rom_browser.resolve_credentials
            original_fetch_game_id = rom_browser.fetch_game_id
            original_cache_game = rom_browser.cache_game
            try:
                rom_browser.hash_rom = lambda _path: "abcd"
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
                rom_browser.hash_rom = original_hash_rom
                rom_browser.resolve_credentials = original_resolve_credentials
                rom_browser.fetch_game_id = original_fetch_game_id
                rom_browser.cache_game = original_cache_game
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
                    game_id, credentials, _user_agent, cache_store, _config_data
                ):
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

    def test_add_rom_to_cache_respects_fifty_game_limit(self) -> None:
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
                for game_id in range(1, 51):
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
                self.assertEqual(result.message, "Cache limit reached: 50 / 50")
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

    def test_normalize_preview_url_builds_absolute_url(self) -> None:
        self.assertEqual(
            rom_browser.normalize_preview_url("/Images/012345.png"),
            "https://retroachievements.org/Images/012345.png",
        )


if __name__ == "__main__":
    unittest.main()
