import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import rom_browser
from linux.raofflineproxy import storage


class LinuxCachedGamesTests(unittest.TestCase):
    def test_list_cached_games_includes_achievementsets_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            try:
                store.upsert_cache(
                    "achievementsets:0e5f788550ca1fad8d4e5034d9964307:misantronic",
                    '{"Success":true,"GameId":10701,"Title":"SNES Burn-in Test Cartridge","Achievements":{"52113":{"ID":52113,"Title":"Test"}}}',
                )
                store.upsert_cache(
                    "achievementsets:02a11cc95bf9f62c601a4909e9b22e95:misantronic",
                    '{"Success":true,"GameId":993,"Title":"Another Game","Achievements":{"11":{"ID":11,"Title":"Intro"}}}',
                )

                games = rom_browser.list_cached_games(store)

                self.assertEqual(
                    [(game.title, game.game_id) for game in games],
                    [("Another Game", 993), ("SNES Burn-in Test Cartridge", 10701)],
                )
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
