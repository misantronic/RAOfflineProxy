from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import cache_keys, game_meta, last_played, pending_awards, rom_browser, rom_cache, storage

USER = "misantronic"


def patch_body(game_id: int, title: str, achievement_ids: list[int]) -> str:
    return json.dumps(
        {
            "Success": True,
            "PatchData": {
                "ID": game_id,
                "Title": title,
                "Achievements": [{"ID": achievement_id, "Title": f"A{achievement_id}"} for achievement_id in achievement_ids],
            },
        },
        separators=(",", ":"),
    )


def achievementsets_body(game_id: int, title: str, sets: dict[int, list[int]]) -> str:
    return json.dumps(
        {
            "Success": True,
            "GameId": game_id,
            "Title": title,
            "Sets": [
                {"GameId": set_game_id, "Achievements": [{"ID": achievement_id, "Title": f"A{achievement_id}"} for achievement_id in ids]}
                for set_game_id, ids in sets.items()
            ],
        },
        separators=(",", ":"),
    )


def pending_award(achievement_id: int, user: str = USER, hardcore: bool = False) -> dict:
    return {
        "achievementId": achievement_id,
        "queryString": f"/dorequest.php?r=awardachievement&u={user}&a={achievement_id}&h={1 if hardcore else 0}",
        "requestBody": "",
        "userAgent": "test",
        "queuedAt": 1,
        "status": "pending",
    }


class LargeCacheTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = storage.Storage(database_path=Path(self._temp_dir.name) / "test.sqlite3")
        last_played.reset_last_played_throttle_for_tests()
        self.store.upsert_cache(cache_keys.patch(10, USER), patch_body(10, "Zelda", [101, 102]), source_rom_path="/roms/zelda.zip")
        self.store.upsert_cache(cache_keys.patch(20, USER), patch_body(20, "Mario", [201]), source_rom_path="/roms/mario.zip")
        self.store.upsert_cache(
            cache_keys.achievementsets("abc", USER),
            achievementsets_body(30, "Metroid", {30: [301], 31: [311]}),
        )

    def tearDown(self) -> None:
        self.store.close()
        self._temp_dir.cleanup()


class StorageStreamingTests(LargeCacheTestCase):
    def test_iterates_every_entry_with_its_body(self) -> None:
        entries = list(self.store.iter_cache_by_prefix(cache_keys.PREFIX_PATCH))

        self.assertEqual(
            {cache_keys.patch(10, USER): patch_body(10, "Zelda", [101, 102]), cache_keys.patch(20, USER): patch_body(20, "Mario", [201])},
            {entry["cacheKey"]: entry["responseBody"] for entry in entries},
        )

    def test_skips_entries_deleted_while_iterating(self) -> None:
        keys = []
        for entry in self.store.iter_cache_by_prefix(cache_keys.PREFIX_PATCH):
            keys.append(entry["cacheKey"])
            self.store.delete_cache_by_prefix(cache_keys.PREFIX_PATCH)

        self.assertEqual(1, len(keys))

    def test_summaries_leave_out_the_body(self) -> None:
        summaries = self.store.cache_summaries_by_prefix(cache_keys.PREFIX_PATCH)

        self.assertEqual({"/roms/zelda.zip", "/roms/mario.zip"}, {summary["sourceRomPath"] for summary in summaries})
        self.assertTrue(all("responseBody" not in summary for summary in summaries))


class FindAchievementGameIdsTests(LargeCacheTestCase):
    def test_maps_patch_and_subset_achievements(self) -> None:
        self.assertEqual(
            {102: 10, 311: 31},
            rom_cache.find_achievement_game_ids(self.store, {102, 311}),
        )

    def test_leaves_out_unknown_achievements(self) -> None:
        self.assertEqual({201: 20}, rom_cache.find_achievement_game_ids(self.store, {201, 999}))

    def test_id_prefix_of_another_id_does_not_match(self) -> None:
        self.assertEqual({}, rom_cache.find_achievement_game_ids(self.store, {10, 20}))

    def test_reads_nothing_without_achievements(self) -> None:
        with mock.patch.object(self.store, "iter_cache_by_prefix", side_effect=AssertionError("read")):
            self.assertEqual({}, rom_cache.find_achievement_game_ids(self.store, set()))

    def test_stops_reading_once_everything_is_found(self) -> None:
        with mock.patch.object(self.store, "iter_cache_by_prefix", wraps=self.store.iter_cache_by_prefix) as iterate:
            rom_cache.find_achievement_game_ids(self.store, {101})

        self.assertEqual([mock.call(cache_keys.PREFIX_PATCH)], iterate.call_args_list)

    def test_checks_the_likely_game_before_the_library(self) -> None:
        with mock.patch.object(self.store, "iter_cache_by_prefix", wraps=self.store.iter_cache_by_prefix) as iterate:
            self.assertEqual({201: 20}, rom_cache.find_achievement_game_ids(self.store, {201}, likely_game_id=20))

        self.assertEqual([mock.call(cache_keys.patch_prefix(20))], iterate.call_args_list)

    def test_checks_recently_played_games_before_the_library(self) -> None:
        last_played.record_game_played(self.store, 20, now=5_000)
        with mock.patch.object(self.store, "iter_cache_by_prefix", wraps=self.store.iter_cache_by_prefix) as iterate:
            self.assertEqual({201: 20}, rom_cache.find_achievement_game_ids(self.store, {201}, likely_game_id=10))

        self.assertEqual(
            [mock.call(cache_keys.patch_prefix(10)), mock.call(cache_keys.patch_prefix(20))],
            iterate.call_args_list,
        )


class MergedUnlockIdsTests(LargeCacheTestCase):
    def test_adds_pending_softcore_awards_of_the_game_and_user(self) -> None:
        self.store.upsert_cache(cache_keys.unlocks(10, USER), json.dumps({"Success": True, "UserUnlocks": [101]}))
        for award in (pending_award(102), pending_award(201), pending_award(311)):
            self.store.upsert_pending_award(award)

        self.assertEqual([101, 102], rom_cache.merged_unlock_ids(self.store, 10, USER))

    def test_ignores_awards_of_other_users(self) -> None:
        self.store.upsert_pending_award(pending_award(102, user="other"))

        self.assertEqual([], rom_cache.merged_unlock_ids(self.store, 10, USER))

    def test_ignores_hardcore_awards(self) -> None:
        self.store.upsert_pending_award(pending_award(102, hardcore=True))

        self.assertEqual([], rom_cache.merged_unlock_ids(self.store, 10, USER))


class CachedGameMetaTests(LargeCacheTestCase):
    def meta_keys(self) -> set[str]:
        return {meta["cacheKey"] for meta in self.store.cached_game_meta()}

    def test_upsert_indexes_game_entries(self) -> None:
        self.assertEqual(
            [("Metroid", 30, None)],
            [(meta["title"], meta["gameId"], meta["imagePath"]) for meta in self.store.cached_game_meta(30)],
        )

    def test_deletes_and_renames_follow_the_cache(self) -> None:
        self.store.delete_cache(cache_keys.patch(20, USER))
        self.store.rename_cache_key(cache_keys.patch(10, USER), cache_keys.patch(10, "other"))

        self.assertEqual(
            {cache_keys.patch(10, "other"), cache_keys.achievementsets("abc", USER)},
            self.meta_keys(),
        )

    def test_clearing_the_cache_clears_the_index(self) -> None:
        self.store.clear_cache()

        self.assertEqual(set(), self.meta_keys())

    def test_entries_written_without_the_index_are_indexed_on_open(self) -> None:
        database_path = Path(self._temp_dir.name) / "test.sqlite3"
        self.store._connection.execute(
            "INSERT INTO api_cache(cacheKey, responseBody, cachedAt, firstCachedAt) VALUES(?, ?, 1, 1)",
            (cache_keys.patch(40, USER), patch_body(40, "Kirby", [401])),
        )
        self.store._connection.commit()
        self.store.close()

        self.store = storage.Storage(database_path=database_path)

        self.assertEqual("Kirby", rom_browser.find_cached_game(self.store, 40).title)

    def test_other_entries_are_not_indexed(self) -> None:
        self.store.upsert_cache(cache_keys.unlocks(10, USER), '{"Success":true,"UserUnlocks":[]}')

        self.assertNotIn(cache_keys.unlocks(10, USER), self.meta_keys())


class AchievementsetsGameIdTests(unittest.TestCase):
    def test_reads_the_leading_game_id(self) -> None:
        self.assertEqual(30, game_meta.achievementsets_game_id(achievementsets_body(30, "Metroid", {31: [311]})))

    def test_parses_bodies_in_another_key_order(self) -> None:
        self.assertEqual(30, game_meta.achievementsets_game_id('{"Title":"Metroid","GameId":30,"Success":true}'))

    def test_rejects_invalid_bodies(self) -> None:
        self.assertIsNone(game_meta.achievementsets_game_id("not json"))
        self.assertIsNone(game_meta.achievementsets_game_id('{"Success":true,"GameId":0}'))


class CachedGamesTests(LargeCacheTestCase):
    def test_lists_patch_and_achievementsets_games(self) -> None:
        self.assertEqual(
            ["Mario", "Metroid", "Zelda"],
            [game.title for game in rom_browser.list_cached_games(self.store)],
        )

    def test_finds_a_single_game(self) -> None:
        self.assertEqual("Zelda", rom_browser.find_cached_game(self.store, 10).title)
        self.assertEqual("Metroid", rom_browser.find_cached_game(self.store, 30).title)
        self.assertIsNone(rom_browser.find_cached_game(self.store, 99))

    def test_loads_achievements_of_one_game(self) -> None:
        self.assertEqual({301, 311}, set(rom_browser.cached_achievements_by_id(self.store, 30)))

    def test_listing_reads_no_response_bodies(self) -> None:
        with (
            mock.patch.object(self.store, "iter_cache_by_prefix", side_effect=AssertionError("read bodies")),
            mock.patch.object(self.store, "get_cache", side_effect=AssertionError("read bodies")),
        ):
            self.assertEqual(3, len(rom_browser.list_cached_games(self.store)))
            self.assertEqual("Zelda", rom_browser.find_cached_game(self.store, 10).title)

    def test_cached_rom_paths(self) -> None:
        self.assertEqual({"/roms/zelda.zip", "/roms/mario.zip"}, rom_browser.load_cached_rom_paths(self.store))

    def test_pending_award_titles_cover_only_pending_achievements(self) -> None:
        index = pending_awards.build_patch_index(self.store, {102, 311})

        self.assertEqual({102: "Zelda", 311: "Metroid"}, {achievement_id: info["game_title"] for achievement_id, info in index.items()})


if __name__ == "__main__":
    unittest.main()
