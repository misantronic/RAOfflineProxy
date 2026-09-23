from __future__ import annotations

import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import cache_keys, last_played, menu_sdl, proxy_service, rom_browser, smart_cache, storage

CREDENTIALS = {"user": "misantronic", "token": "token"}
OLD = 1


class StorageTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = storage.Storage(database_path=Path(self._temp_dir.name) / "test.sqlite3")
        last_played.reset_last_played_throttle_for_tests()

    def tearDown(self) -> None:
        self.store.close()
        self._temp_dir.cleanup()
        last_played.reset_last_played_throttle_for_tests()


class LastPlayedKeyTests(unittest.TestCase):
    def test_builds_key(self) -> None:
        self.assertEqual("lastplayed:10701", cache_keys.last_played(10701))

    def test_parses_key(self) -> None:
        self.assertEqual(10701, cache_keys.parse_game_id_from_last_played_key("lastplayed:10701"))

    def test_rejects_malformed_keys(self) -> None:
        self.assertIsNone(cache_keys.parse_game_id_from_last_played_key("lastplayed:"))
        self.assertIsNone(cache_keys.parse_game_id_from_last_played_key("lastplayed:abc"))
        self.assertIsNone(cache_keys.parse_game_id_from_last_played_key("patch:10701:user"))


class RecordGamePlayedTests(StorageTestCase):
    def test_writes_timestamp(self) -> None:
        last_played.record_game_played(self.store, 10701, now=5_000)

        entry = self.store.get_cache("lastplayed:10701")
        self.assertEqual(5_000, entry["cachedAt"])

    def test_collapses_writes_inside_interval(self) -> None:
        last_played.record_game_played(self.store, 10701, now=5_000)
        last_played.record_game_played(self.store, 10701, now=5_000 + 59_999)

        self.assertEqual(5_000, self.store.get_cache("lastplayed:10701")["cachedAt"])

    def test_writes_again_after_interval(self) -> None:
        last_played.record_game_played(self.store, 10701, now=5_000)
        last_played.record_game_played(self.store, 10701, now=5_000 + 60_000)

        self.assertEqual(65_000, self.store.get_cache("lastplayed:10701")["cachedAt"])

    def test_ignores_non_positive_game_id(self) -> None:
        last_played.record_game_played(self.store, 0, now=5_000)

        self.assertEqual([], self.store.get_all_cache_by_prefix(cache_keys.PREFIX_LAST_PLAYED))


class RecentlyPlayedTests(unittest.TestCase):
    @staticmethod
    def entry(key: str, cached_at: int) -> dict:
        return {"cacheKey": key, "cachedAt": cached_at}

    def test_keeps_entries_inside_window(self) -> None:
        entries = [self.entry("lastplayed:10", 5_000), self.entry("lastplayed:20", 9_000)]
        self.assertEqual({10, 20}, last_played.recently_played_game_ids(entries, since=5_000))

    def test_drops_entries_older_than_window(self) -> None:
        entries = [self.entry("lastplayed:10", 4_999), self.entry("lastplayed:20", 9_000)]
        self.assertEqual({20}, last_played.recently_played_game_ids(entries, since=5_000))

    def test_ignores_unparseable_keys(self) -> None:
        entries = [self.entry("lastplayed:", 9_000), self.entry("lastplayed:abc", 9_000)]
        self.assertEqual(set(), last_played.recently_played_game_ids(entries, since=5_000))


class DueRefreshGameIdsTests(unittest.TestCase):
    def test_only_recently_played_games_are_due(self) -> None:
        patch_entries = [
            {"cacheKey": "patch:10:misantronic"},
            {"cacheKey": "patch:20:misantronic"},
            {"cacheKey": "patch:30:misantronic"},
        ]
        self.assertEqual([20], proxy_service.due_refresh_game_ids(patch_entries, {20, 99}))

    def test_deduplicates_games_cached_for_several_users(self) -> None:
        patch_entries = [{"cacheKey": "patch:10:alice"}, {"cacheKey": "patch:10:bob"}]
        self.assertEqual([10], proxy_service.due_refresh_game_ids(patch_entries, {10}))

    def test_nothing_due_when_nothing_played(self) -> None:
        self.assertEqual([], proxy_service.due_refresh_game_ids([{"cacheKey": "patch:10:alice"}], set()))


class RecordGameActivityTests(StorageTestCase):
    def test_records_game_from_g_param(self) -> None:
        server = types.SimpleNamespace(storage=self.store)
        proxy_service.ProxyRuntimeServer.record_game_activity(
            server, "/dorequest.php?r=ping&g=10701&u=misantronic", ""
        )

        self.assertIsNotNone(self.store.get_cache("lastplayed:10701"))

    def test_ignores_missing_or_non_numeric_game_id(self) -> None:
        server = types.SimpleNamespace(storage=self.store)
        proxy_service.ProxyRuntimeServer.record_game_activity(server, "/dorequest.php?r=ping", "")
        proxy_service.ProxyRuntimeServer.record_game_activity(server, "/dorequest.php?r=ping&g=abc", "")

        self.assertEqual([], self.store.get_all_cache_by_prefix(cache_keys.PREFIX_LAST_PLAYED))

    def test_only_ping_and_startsession_count_as_playing(self) -> None:
        self.assertEqual(frozenset({"ping", "startsession"}), last_played.LAST_PLAYED_ACTIONS)


class EvictionTests(unittest.TestCase):
    GAME_KEYS = (
        "patch:10701:misantronic",
        "achievementsets:abcd:misantronic",
        "unlocks:10701:misantronic:0",
        "startsession:10701:misantronic:0",
        "gameid:abcd",
    )

    def assert_eviction_keeps_game_data(self, store: storage.Storage) -> None:
        for key in self.GAME_KEYS:
            store.upsert_cache(key, "{}", cached_at=OLD)
        store.upsert_cache("lastplayed:10701", "1", cached_at=OLD)
        store.upsert_cache("achievements:10701:misantronic", "{}", cached_at=OLD)

        store.evict_cache_older_than(1_000)

        for key in self.GAME_KEYS:
            self.assertIsNotNone(store.get_cache(key), key)
        self.assertIsNone(store.get_cache("lastplayed:10701"))
        self.assertIsNone(store.get_cache("achievements:10701:misantronic"))

    def test_sqlite_eviction_keeps_game_data(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            try:
                self.assert_eviction_keeps_game_data(store)
            finally:
                store.close()

    def test_json_eviction_keeps_game_data(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with mock.patch.object(storage, "sqlite3", None):
                store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
                try:
                    self.assert_eviction_keeps_game_data(store)
                finally:
                    store.close()


class GameDeletionTests(StorageTestCase):
    def test_clear_cache_removes_last_played(self) -> None:
        self.store.upsert_cache("lastplayed:10701", "1")

        self.store.clear_cache()

        self.assertIsNone(self.store.get_cache("lastplayed:10701"))

    def test_remove_cached_game_removes_last_played(self) -> None:
        self.store.upsert_cache("patch:10701:misantronic", "{}")
        self.store.upsert_cache("lastplayed:10701", "1")
        self.store.upsert_cache("lastplayed:42", "1")

        with mock.patch.object(rom_browser, "delete_cached_images_for_game"):
            rom_browser.remove_cached_game(self.store, 10701)

        self.assertIsNone(self.store.get_cache("lastplayed:10701"))
        self.assertIsNotNone(self.store.get_cache("lastplayed:42"))


class CacheableGameIdResponseTests(unittest.TestCase):
    def test_caches_match(self) -> None:
        self.assertTrue(rom_browser.is_cacheable_game_id_response('{"Success":true,"GameID":10701}'))

    def test_caches_match_without_success_flag(self) -> None:
        self.assertTrue(rom_browser.is_cacheable_game_id_response('{"GameID":10701}'))

    def test_caches_genuine_no_match(self) -> None:
        self.assertTrue(rom_browser.is_cacheable_game_id_response('{"Success":true,"GameID":0}'))

    def test_skips_error_response(self) -> None:
        self.assertFalse(
            rom_browser.is_cacheable_game_id_response('{"Success":false,"Error":"Invalid token","GameID":0}')
        )

    def test_skips_no_match_without_success_flag(self) -> None:
        self.assertFalse(rom_browser.is_cacheable_game_id_response('{"GameID":0}'))

    def test_skips_non_json_and_empty_bodies(self) -> None:
        self.assertFalse(rom_browser.is_cacheable_game_id_response("<html>Maintenance</html>"))
        self.assertFalse(rom_browser.is_cacheable_game_id_response(""))
        self.assertFalse(rom_browser.is_cacheable_game_id_response("[]"))


class FetchGameIdCacheTests(StorageTestCase):
    def fetch(self, response_body: str | None = None) -> tuple[int | None, int]:
        calls = []

        def fake_http_get(_url: str, _ua: str) -> str:
            calls.append(_url)
            if response_body is None:
                raise AssertionError("unexpected network lookup")
            return response_body

        with mock.patch.object(rom_browser, "http_get", fake_http_get):
            game_id = rom_browser.fetch_game_id("abcd", CREDENTIALS, "RetroArch/1.20.0", {}, self.store)
        return game_id, len(calls)

    def test_cached_match_skips_lookup(self) -> None:
        self.store.upsert_cache("gameid:abcd", '{"Success":true,"GameID":10701}', cached_at=OLD)

        self.assertEqual((10701, 0), self.fetch())

    def test_fresh_no_match_skips_lookup(self) -> None:
        self.store.upsert_cache("gameid:abcd", '{"Success":true,"GameID":0}')

        self.assertEqual((None, 0), self.fetch())

    def test_expired_no_match_is_looked_up_again(self) -> None:
        self.store.upsert_cache("gameid:abcd", '{"Success":true,"GameID":0}', cached_at=OLD)

        self.assertEqual((10701, 1), self.fetch('{"Success":true,"GameID":10701}'))
        self.assertIn('"GameID":10701', self.store.get_cache("gameid:abcd")["responseBody"])

    def test_no_match_is_cached(self) -> None:
        self.assertEqual((None, 1), self.fetch('{"Success":true,"GameID":0}'))
        self.assertEqual((None, 0), self.fetch())

    def test_error_response_is_not_cached(self) -> None:
        self.assertEqual((None, 1), self.fetch('{"Success":false,"Error":"Invalid token","GameID":0}'))
        self.assertIsNone(self.store.get_cache("gameid:abcd"))


class AlreadyCachedTests(StorageTestCase):
    PATCH = '{"Success":true,"PatchData":{"Title":"Test Cart"}}'

    def add_rom(self, rom_path: Path) -> tuple[rom_browser.AddRomResult, list]:
        downloads = []

        def fake_cache_game(game_id, _hash, credentials, _ua, cache_store, _config, cache_images=True):
            downloads.append(game_id)
            cache_store.upsert_cache(cache_keys.patch(game_id, credentials["user"]), self.PATCH)

        with mock.patch.object(rom_browser, "hash_rom_candidates", lambda _path: ["abcd"]), \
                mock.patch.object(rom_browser, "resolve_credentials", lambda *_args: CREDENTIALS), \
                mock.patch.object(rom_browser, "fetch_game_id", lambda *_args: 10701), \
                mock.patch.object(rom_browser, "cache_game", fake_cache_game):
            return rom_browser.add_rom_to_cache(rom_path, self.store, {}), downloads

    def rom(self) -> Path:
        rom_path = Path(self._temp_dir.name) / "test.gb"
        rom_path.write_bytes(b"rom")
        return rom_path

    def test_new_game_is_downloaded(self) -> None:
        result, downloads = self.add_rom(self.rom())

        self.assertEqual([10701], downloads)
        self.assertTrue(result.success)
        self.assertFalse(result.already_cached)

    def test_already_cached_game_is_not_downloaded_again(self) -> None:
        self.store.upsert_cache("patch:10701:misantronic", self.PATCH)

        result, downloads = self.add_rom(self.rom())

        self.assertEqual([], downloads)
        self.assertTrue(result.success)
        self.assertTrue(result.already_cached)
        self.assertEqual("Already cached Test Cart", result.message)

    def test_already_cached_game_records_rom_path(self) -> None:
        self.store.upsert_cache("patch:10701:misantronic", self.PATCH)
        rom_path = self.rom()

        self.add_rom(rom_path)

        self.assertIn(rom_browser.normalize_cached_rom_path(rom_path), rom_browser.load_cached_rom_paths(self.store))

    def test_already_cached_game_is_accepted_at_cache_limit(self) -> None:
        self.store.upsert_cache("patch:10701:misantronic", self.PATCH)
        for game_id in range(1, rom_browser.MAX_CACHED_GAMES):
            self.store.upsert_cache(f"patch:{game_id}:misantronic", self.PATCH)

        result, downloads = self.add_rom(self.rom())

        self.assertEqual([], downloads)
        self.assertTrue(result.already_cached)


class BulkCacheCountingTests(StorageTestCase):
    def test_already_cached_games_count_as_skipped(self) -> None:
        results = iter(
            [
                rom_browser.AddRomResult(True, "Already cached A", already_cached=True),
                rom_browser.AddRomResult(True, "Cached B"),
                rom_browser.AddRomResult(False, "No RetroAchievements match"),
            ]
        )
        paths = [Path("a.gb"), Path("b.gb"), Path("c.gb")]

        with mock.patch.object(smart_cache, "add_rom_to_cache", lambda *_args: next(results)), \
                mock.patch.object(smart_cache, "apply_scan_batch_cooldown", lambda _scanned: True):
            result = smart_cache.run_cache_paths(self.store, {}, paths, limit=25)

        self.assertEqual(3, result.scanned)
        self.assertEqual(1, result.cached)
        self.assertEqual(2, result.skipped)


class SingleCacheMessageTests(unittest.TestCase):
    def test_new_game(self) -> None:
        result = rom_browser.AddRomResult(True, "Cached A")
        self.assertEqual("Scanned 1, cached 1, skipped 0", menu_sdl.single_cache_completion_message(result, False))

    def test_already_cached_game(self) -> None:
        result = rom_browser.AddRomResult(True, "Already cached A", already_cached=True)
        self.assertEqual("Scanned 1, cached 0, skipped 1", menu_sdl.single_cache_completion_message(result, False))

    def test_aborted(self) -> None:
        result = rom_browser.AddRomResult(True, "Cached A")
        self.assertEqual("Aborted: scanned 1, cached 1, skipped 0", menu_sdl.single_cache_completion_message(result, True))

    def test_failure_shows_reason(self) -> None:
        result = rom_browser.AddRomResult(False, "No RetroAchievements match")
        self.assertEqual("No RetroAchievements match", menu_sdl.single_cache_completion_message(result, False))


class FakeClock:
    def __init__(self) -> None:
        self.now = 1_000.0

    def __call__(self) -> float:
        return self.now


class GameActivityTrackerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock()
        self.tracker = proxy_service.GameActivityTracker(clock=self.clock)

    def test_idle_when_nothing_happened(self) -> None:
        self.assertEqual(0, self.tracker.idle_delay_seconds())

    def test_waits_full_delay_right_after_activity(self) -> None:
        self.tracker.note()
        self.assertEqual(300, self.tracker.idle_delay_seconds())

    def test_counts_down_and_reaches_idle(self) -> None:
        self.tracker.note()
        self.clock.now += 299
        self.assertEqual(1, self.tracker.idle_delay_seconds())
        self.clock.now += 1
        self.assertEqual(0, self.tracker.idle_delay_seconds())


class NoteGameRequestTests(unittest.TestCase):
    def note(self, path: str) -> float:
        server = types.SimpleNamespace(activity=proxy_service.GameActivityTracker())
        proxy_service.ProxyRuntimeServer.note_game_request(server, path, "")
        return server.activity.idle_delay_seconds()

    def test_request_with_game_id_is_activity(self) -> None:
        self.assertGreater(self.note("/dorequest.php?r=patch&g=10701"), 0)

    def test_request_with_leaderboard_id_is_activity(self) -> None:
        self.assertGreater(self.note("/dorequest.php?r=submitlbentry&i=42"), 0)

    def test_request_without_game_is_not_activity(self) -> None:
        self.assertEqual(0, self.note("/dorequest.php?r=login2&u=misantronic"))


class RefreshIdleGuardTests(StorageTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.clock = FakeClock()
        self.server = types.SimpleNamespace(
            activity=proxy_service.GameActivityTracker(clock=self.clock),
            is_online=lambda: True,
            storage=self.store,
            config_data={},
        )
        self.refresh = proxy_service.PeriodicRefresh(self.server)
        self.waits = []

    def fake_wait(self, on_wait=None):
        def wait(seconds):
            self.waits.append(seconds)
            if on_wait is not None:
                return on_wait()
            self.clock.now += seconds
            return False

        return wait

    def test_idle_proxy_refreshes_immediately(self) -> None:
        with mock.patch.object(self.refresh.stop_event, "wait", self.fake_wait()):
            self.assertTrue(self.refresh.wait_until_idle())
        self.assertEqual([], self.waits)

    def test_active_proxy_defers_until_idle(self) -> None:
        self.server.activity.note()
        self.clock.now += 60

        with mock.patch.object(self.refresh.stop_event, "wait", self.fake_wait()):
            self.assertTrue(self.refresh.wait_until_idle())
        self.assertEqual([240], self.waits)

    def test_activity_during_deferral_skips_cycle(self) -> None:
        self.server.activity.note()

        def play_on():
            self.clock.now += 300
            self.server.activity.note()
            return False

        with mock.patch.object(self.refresh.stop_event, "wait", self.fake_wait(play_on)):
            self.assertFalse(self.refresh.wait_until_idle())

    def test_stop_during_deferral_skips_cycle(self) -> None:
        self.server.activity.note()

        with mock.patch.object(self.refresh.stop_event, "wait", self.fake_wait(lambda: True)):
            self.assertFalse(self.refresh.wait_until_idle())

    def run_refresh(self, on_first_refresh=None) -> tuple[int, list]:
        refreshed = []

        def fake_refresh_game_patch(game_id, *_args, **_kwargs):
            refreshed.append(game_id)
            if on_first_refresh is not None and len(refreshed) == 1:
                on_first_refresh()

        with mock.patch.object(proxy_service, "refresh_game_patch", fake_refresh_game_patch), \
                mock.patch.object(proxy_service, "cache_unlocks", lambda *_args, **_kwargs: None), \
                mock.patch.object(proxy_service, "cache_session", lambda *_args, **_kwargs: None):
            count = self.refresh.refresh_games([10, 20, 30], CREDENTIALS, "RetroArch/1.20.0")
        return count, refreshed

    def test_refreshes_every_due_game_while_idle(self) -> None:
        self.assertEqual((3, [10, 20, 30]), self.run_refresh())

    def test_stops_when_play_resumes_mid_cycle(self) -> None:
        self.assertEqual((1, [10]), self.run_refresh(on_first_refresh=self.server.activity.note))


if __name__ == "__main__":
    unittest.main()
