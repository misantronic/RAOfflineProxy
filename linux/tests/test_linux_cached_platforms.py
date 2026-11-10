from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import cache_keys, menu_sdl, rom_browser, storage
from linux.raofflineproxy.rom_browser import CachedGameEntry

USER = "misantronic"


def patch_body(game_id: int, title: str, console_id: int | None) -> str:
    patch_data = {"ID": game_id, "Title": title}
    if console_id is not None:
        patch_data["ConsoleID"] = console_id
    return json.dumps({"Success": True, "PatchData": patch_data})


def achievementsets_body(game_id: int, title: str, console_id: int) -> str:
    return json.dumps({"Success": True, "GameId": game_id, "Title": title, "ConsoleId": console_id, "Sets": []})


class ConsoleIdStorageTests(unittest.TestCase):
    def setUp(self) -> None:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        self.database_path = Path(temp_dir.name) / "test.sqlite3"
        self.store = storage.Storage(database_path=self.database_path)
        self.addCleanup(lambda: self.store.close())

    def console_ids(self) -> dict[int, int]:
        return {game.game_id: game.console_id for game in rom_browser.list_cached_games(self.store)}

    def test_patch_and_achievementsets_carry_the_console(self) -> None:
        self.store.upsert_cache(cache_keys.patch(10, USER), patch_body(10, "Tetris", 4))
        self.store.upsert_cache(cache_keys.achievementsets("abc", USER), achievementsets_body(20, "Crash", 12))
        self.store.upsert_cache(cache_keys.patch(30, USER), patch_body(30, "No Console", None))

        self.assertEqual({10: 4, 20: 12, 30: 0}, self.console_ids())

    def test_achievementsets_fill_in_a_patch_without_console(self) -> None:
        self.store.upsert_cache(cache_keys.patch(10, USER), patch_body(10, "Tetris", None))
        self.store.upsert_cache(cache_keys.achievementsets("abc", USER), achievementsets_body(10, "Tetris", 4))

        self.assertEqual({10: 4}, self.console_ids())

    def test_rows_indexed_before_the_column_existed_are_indexed_again(self) -> None:
        self.store.upsert_cache(cache_keys.patch(10, USER), patch_body(10, "Tetris", 4))
        self.store.upsert_cache(cache_keys.patch(30, USER), patch_body(30, "No Console", None))
        self.store._connection.execute("UPDATE cached_game_meta SET consoleId = NULL")
        self.store._connection.commit()
        self.store.close()

        self.store = storage.Storage(database_path=self.database_path)

        self.assertEqual({10: 4, 30: 0}, self.console_ids())
        unindexed = self.store._connection.execute(
            "SELECT COUNT(*) FROM cached_game_meta WHERE consoleId IS NULL"
        ).fetchone()[0]
        self.assertEqual(0, unindexed)

    def test_a_database_without_the_column_is_upgraded(self) -> None:
        self.store.upsert_cache(cache_keys.patch(10, USER), patch_body(10, "Tetris", 4))
        self.store._connection.executescript(
            """
            DROP TABLE cached_game_meta;
            CREATE TABLE cached_game_meta (cacheKey TEXT PRIMARY KEY, gameId INTEGER, title TEXT, imagePath TEXT);
            """
        )
        self.store._connection.execute(
            "INSERT INTO cached_game_meta VALUES(?, 10, 'Tetris', NULL)", (cache_keys.patch(10, USER),)
        )
        self.store._connection.commit()
        self.store.close()

        self.store = storage.Storage(database_path=self.database_path)

        self.assertEqual({10: 4}, self.console_ids())


def game(game_id: int, title: str, console_id: int) -> CachedGameEntry:
    return CachedGameEntry(game_id=game_id, title=title, console_id=console_id)


def menu_session(games: list) -> menu_sdl.MenuSdlSession:
    session = menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)
    session.view = "cached_games"
    session.cached_games = games
    session.platform_id = None
    session.main_online = False
    session.selected_index = 0
    session.scroll_offset = 0
    session.view_positions = {}
    session.active_game = None
    session.refresh_active_game_unlocks = lambda: None
    session.clear_active_game_unlocks = lambda: None
    return session


GAMES = [
    game(1, "Crash Bandicoot", 12),
    game(2, "Gran Turismo 2", 12),
    game(3, "Tetris", 4),
    game(4, "Mystery", 0),
    game(5, "Future Console Game", 9999),
]


class PlatformMenuTests(unittest.TestCase):
    def test_groups_are_sorted_by_name_with_unknown_last(self) -> None:
        self.assertEqual(
            [(4, ["Tetris"]), (12, ["Crash Bandicoot", "Gran Turismo 2"]), (0, ["Mystery", "Future Console Game"])],
            [
                (console_id, [entry.title for entry in games])
                for console_id, games in menu_sdl.group_games_by_platform(GAMES)
            ],
        )

    def test_cached_games_lists_platforms_with_counts(self) -> None:
        session = menu_session(GAMES)

        self.assertEqual(
            ["Game Boy (1)", "PlayStation (2)", "Unknown (2)", "Clear cache", "Back"],
            session.labels(running=False),
        )

        session.main_online = True
        self.assertEqual(
            ["Add ROMs", "Watch folders", "Start Smart Cache", "Game Boy (1)"],
            session.labels(running=False)[:4],
        )

    def test_selecting_a_platform_lists_its_games(self) -> None:
        session = menu_session(GAMES)
        session.main_online = True
        session.selected_index = 4

        session.activate_selected()

        self.assertEqual("platform_games", session.view)
        self.assertEqual("PlayStation", session.title_for_view())
        self.assertEqual("CACHED: 2", session.status_text(running=False))
        self.assertEqual(["Crash Bandicoot", "Gran Turismo 2", "Back"], session.labels(running=False))
        self.assertEqual(0, session.selected_index)
        self.assertEqual(1, session.preview_target_game().game_id)

    def test_back_returns_to_the_selected_platform(self) -> None:
        session = menu_session(GAMES)
        session.selected_index = 1
        session.activate_selected()

        session.go_back()

        self.assertEqual("cached_games", session.view)
        self.assertEqual(1, session.selected_index)
        self.assertIsNone(session.platform_id)

    def test_back_item_closes_the_platform(self) -> None:
        session = menu_session(GAMES)
        session.selected_index = 1
        session.activate_selected()
        session.selected_index = 2

        session.activate_selected()

        self.assertEqual("cached_games", session.view)

    def test_game_actions_return_to_the_platform(self) -> None:
        session = menu_session(GAMES)
        session.selected_index = 1
        session.activate_selected()
        session.selected_index = 1

        session.activate_selected()
        self.assertEqual("game_actions", session.view)
        self.assertEqual(2, session.active_game.game_id)

        session.go_back()
        self.assertEqual("platform_games", session.view)
        self.assertEqual(1, session.selected_index)

    def test_leaving_an_emptied_platform_returns_to_the_platform_list(self) -> None:
        session = menu_session(GAMES)
        session.selected_index = 0
        session.activate_selected()
        session.activate_selected()
        session.cached_games = [entry for entry in GAMES if entry.console_id != 4]

        session.go_back()

        self.assertEqual("cached_games", session.view)
        self.assertEqual(["PlayStation (2)", "Unknown (2)", "Clear cache", "Back"], session.labels(running=False))


if __name__ == "__main__":
    unittest.main()
