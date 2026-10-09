from __future__ import annotations

import contextlib
import json
import tempfile
import threading
import time
import types
import unittest
from io import StringIO
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import (
    cache_budget,
    image_cache,
    cache_keys,
    cache_queue,
    main,
    proxy_service,
    rate_limit,
    rom_browser,
    smart_cache,
    storage,
)
from linux.raofflineproxy.cache_budget import (
    CACHE_BUDGET_LIMIT,
    CACHE_BUDGET_WINDOW_MS,
    BudgetWindow,
)
from linux.raofflineproxy.cache_queue import QueuedRom
from linux.raofflineproxy.rom_browser import DrainStop

CREDENTIALS = {"user": "misantronic", "token": "token"}
PATCH = '{"Success":true,"PatchData":{"Title":"%s"}}'
NOW = 1_800_000_000_000


class BudgetWindowTests(unittest.TestCase):
    def test_counts_games_until_the_limit(self) -> None:
        window = BudgetWindow().charge(NOW, games=CACHE_BUDGET_LIMIT - 1)

        self.assertEqual(1, window.remaining(NOW + 1))
        self.assertEqual(0, window.charge(NOW + 1, games=1).remaining(NOW + 2))

    def test_resets_after_the_window(self) -> None:
        window = BudgetWindow().charge(NOW, games=CACHE_BUDGET_LIMIT)

        self.assertEqual(NOW + CACHE_BUDGET_WINDOW_MS, window.next_available_at(NOW + 1))
        self.assertEqual(CACHE_BUDGET_LIMIT, window.remaining(NOW + CACHE_BUDGET_WINDOW_MS))

    def test_clock_jumping_backwards_starts_a_fresh_window(self) -> None:
        window = BudgetWindow().charge(NOW, games=CACHE_BUDGET_LIMIT)

        self.assertEqual(CACHE_BUDGET_LIMIT, window.remaining(NOW - 1))

    def test_pause_holds_an_open_window_and_outlives_it(self) -> None:
        paused_until = NOW + 2 * CACHE_BUDGET_WINDOW_MS
        window = BudgetWindow(window_start=NOW, used=1, paused_until=paused_until)

        self.assertEqual(0, window.remaining(NOW + 1))
        self.assertEqual(0, window.remaining(NOW + CACHE_BUDGET_WINDOW_MS + 1))
        self.assertEqual(paused_until, window.next_available_at(NOW + 1))
        self.assertEqual(CACHE_BUDGET_LIMIT, window.remaining(paused_until))

    def test_json_round_trip_and_garbage(self) -> None:
        window = BudgetWindow(window_start=NOW, used=7, paused_until=NOW + 5)

        self.assertEqual(window, BudgetWindow.from_json(window.to_json()))
        self.assertEqual(BudgetWindow(), BudgetWindow.from_json("not json"))
        self.assertEqual(BudgetWindow(), BudgetWindow.from_json(None))


class QueueModelTests(unittest.TestCase):
    def test_queued_rom_json_round_trip(self) -> None:
        rom = QueuedRom(["ABCD", "ef01"], "/nes/Mario.nes", "Mario.nes", NOW, attempts=1)

        self.assertEqual(rom, QueuedRom.from_json(rom.to_json()))
        self.assertEqual("cachequeue:abcd", rom.key)
        self.assertIsNone(QueuedRom.from_json('{"hashes":[]}'))

    def test_failed_attempts_drop_the_rom_after_three(self) -> None:
        rom = QueuedRom(["abcd"], None, "a", NOW)

        second = rom.after_failed_attempt()
        third = second.after_failed_attempt()

        self.assertEqual(2, third.attempts)
        self.assertIsNone(third.after_failed_attempt())

    def test_estimate_confirms_only_above_one_window(self) -> None:
        small = cache_queue.estimate_queue(candidates=150, already_known=40, budget_remaining=100, queued_now=0)
        large = cache_queue.estimate_queue(candidates=250, already_known=0, budget_remaining=100, queued_now=0)
        rescan = cache_queue.estimate_queue(candidates=250, already_known=250, budget_remaining=100, queued_now=500)

        self.assertFalse(small.needs_confirmation)
        self.assertEqual((100, 150, 150, 60), (large.cached_now, large.newly_queued, large.queued_after, large.eta_minutes))
        self.assertTrue(large.needs_confirmation)
        self.assertFalse(rescan.needs_confirmation)

    def test_confirm_message_counts_everything_left_to_the_background(self) -> None:
        estimate = cache_queue.estimate_queue(candidates=857, already_known=0, budget_remaining=100, queued_now=0)

        self.assertEqual(
            "Up to 857 games are cached in the background: 100 every 30 minutes while the proxy is running (about 4 h 0 min).",
            smart_cache.queue_confirm_message(estimate),
        )

    def test_eta_text(self) -> None:
        self.assertEqual("30 minutes", smart_cache.format_eta(30))
        self.assertEqual("2 h 30 min", smart_cache.format_eta(150))


class ImageDownloadTests(unittest.TestCase):
    def test_an_image_is_downloaded_once_while_pending(self) -> None:
        submitted = []

        with mock.patch.object(
            image_cache._image_download_executor,
            "submit",
            lambda fn, *args: submitted.append(args),
        ):
            image_cache.schedule_image_download("https://x/Images/1.png", "/Images/1.png", "ua")
            image_cache.schedule_image_download("https://x/Images/1.png", "/Images/1.png", "ua")

        self.assertEqual(1, len(submitted))
        with mock.patch.object(image_cache, "download_static_image", lambda *_args: None):
            image_cache._download_pending_image(*submitted[0])
        self.assertNotIn("/Images/1.png", image_cache._pending_downloads)


class QueueTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self._temp_dir.name)
        self.store = storage.Storage(database_path=self.root / "test.sqlite3")
        self.game_ids: dict[str, int | None] = {}
        self.lookups: list[str] = []
        self.cached: list[int] = []
        self.fail_cache_for: set[int] = set()
        rate_limit.reset_for_tests()
        self._patches = contextlib.ExitStack()
        self._patches.enter_context(mock.patch.object(rom_browser, "resolve_credentials", lambda *_args: CREDENTIALS))
        self._patches.enter_context(mock.patch.object(smart_cache, "resolve_credentials", lambda *_args: CREDENTIALS))
        self._patches.enter_context(
            mock.patch.object(rom_browser, "hash_candidates_for_manual_cache", lambda path: [path.stem])
        )
        self._patches.enter_context(mock.patch.object(rom_browser, "fetch_game_id", self.fake_fetch_game_id))
        self._patches.enter_context(mock.patch.object(rom_browser, "cache_game", self.fake_cache_game))
        self.pace_waits: list[float] = []
        self._patches.enter_context(mock.patch.object(cache_budget, "CACHE_BURST_GAMES", 0))
        self._patches.enter_context(mock.patch.object(rom_browser, "wait_for_pace", self.fake_wait_for_pace))

    def tearDown(self) -> None:
        self._patches.close()
        self.store.close()
        self._temp_dir.cleanup()
        rate_limit.reset_for_tests()

    def fake_wait_for_pace(self, ready_at, should_pause):
        self.pace_waits.append(ready_at)
        return not should_pause()

    def fake_fetch_game_id(self, hash_value, _credentials, _user_agent, _config_data, store):
        self.lookups.append(hash_value)
        game_id = self.game_ids.get(hash_value)
        store.upsert_cache(cache_keys.game_id(hash_value), json.dumps({"Success": True, "GameID": game_id or 0}))
        return game_id

    def fake_cache_game(self, game_id, _hash, credentials, _ua, store, _config, cache_images=True, source_rom_path=None):
        if game_id in self.fail_cache_for:
            raise RuntimeError("network down")
        self.cached.append(game_id)
        store.upsert_cache(
            cache_keys.patch(game_id, credentials["user"]),
            PATCH % f"Game {game_id}",
            source_rom_path=source_rom_path,
        )

    def roms(self, *names: str) -> list[Path]:
        paths = []
        for name in names:
            path = self.root / "roms" / f"{name}.nes"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
            paths.append(path)
        return paths

    def queue(self, *names: str) -> list[str]:
        keys = []
        for index, name in enumerate(names):
            rom = QueuedRom([name], f"/roms/{name}.nes", f"{name}.nes", NOW + index)
            self.store.upsert_cache(rom.key, rom.to_json(), cached_at=NOW + index, source_rom_path=rom.source_rom_path)
            keys.append(rom.key)
        return keys

    def use_budget(self, games: int) -> None:
        cache_budget.save(self.store, BudgetWindow(window_start=cache_budget.current_millis(), used=games))

    def drain(self, **kwargs) -> rom_browser.DrainResult:
        return rom_browser.drain_cache_queue(self.store, {}, CREDENTIALS, "ua", **kwargs)


class EstimateCacheCliTests(QueueTestCase):
    def run_cli(self, *args: str) -> str:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", "estimate-cache", *args]), \
                mock.patch.object(main, "load_config", return_value={}), \
                mock.patch.object(main, "Storage", return_value=self.store), \
                mock.patch.object(self.store, "close"), \
                mock.patch("sys.stdout", stdout):
            main.main()
        return stdout.getvalue().strip()

    def test_reports_the_estimate_as_json(self) -> None:
        self.roms("a", "b", "c")
        self.use_budget(CACHE_BUDGET_LIMIT - 1)

        payload = json.loads(self.run_cli("--path", str(self.root / "roms"), "--json"))

        self.assertEqual(
            {
                "candidates": 3,
                "cached_now": 1,
                "newly_queued": 2,
                "queued_after": 2,
                "eta_minutes": 30,
                "needs_confirmation": False,
            },
            payload,
        )

    def test_asks_for_confirmation_above_the_budget_limit(self) -> None:
        self.roms(*(f"rom{index}" for index in range(CACHE_BUDGET_LIMIT + 1)))
        self.use_budget(CACHE_BUDGET_LIMIT)

        payload = json.loads(self.run_cli("--path", str(self.root / "roms"), "--json"))

        self.assertEqual(CACHE_BUDGET_LIMIT + 1, payload["queued_after"])
        self.assertEqual(60, payload["eta_minutes"])
        self.assertTrue(payload["needs_confirmation"])

    def test_queued_roms_and_subfolders(self) -> None:
        self.roms("a", "b")
        nested = self.root / "roms" / "more" / "c.nes"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(b"c")
        self.queue("a")

        payload = json.loads(self.run_cli("--path", str(self.root / "roms"), "--json"))

        self.assertEqual((3, 2, 1), (payload["candidates"], payload["cached_now"], payload["queued_after"]))

    def test_prints_key_value_pairs_without_json(self) -> None:
        self.roms("a")

        output = self.run_cli("--path", str(self.root / "roms"))

        self.assertEqual(
            "candidates=1 cached_now=1 newly_queued=0 queued_after=0 eta_minutes=0 needs_confirmation=false",
            output,
        )

    def test_rejects_a_missing_or_invalid_path(self) -> None:
        with self.assertRaises(SystemExit):
            self.run_cli()
        with self.assertRaises(SystemExit):
            self.run_cli("--path", str(self.root / "nope"))


class DrainTests(QueueTestCase):
    def test_caches_oldest_first_and_charges_only_cached_games(self) -> None:
        self.game_ids.update({"b": 2, "a": 1})
        self.queue("a", "unknown", "b")

        result = self.drain()

        self.assertEqual(DrainStop.EMPTY, result.stop)
        self.assertEqual([1, 2], self.cached)
        self.assertEqual((2, 1), (result.cached, result.no_match))
        self.assertEqual(2, cache_budget.load(self.store).used)
        self.assertEqual(0, cache_queue.count(self.store))

    def test_the_rom_path_is_stored_with_the_game_data(self) -> None:
        self.game_ids["a"] = 1
        self.queue("a")

        with mock.patch.object(
            rom_browser, "remember_source_rom_path", side_effect=AssertionError("rewrote the game")
        ):
            self.drain()

        # Setting the path afterwards read and rewrote the whole stored game: 1 to 3 s each.
        self.assertEqual("/roms/a.nes", self.store.get_cache(cache_keys.patch(1, "misantronic"))["sourceRomPath"])

    def test_stops_when_the_window_is_used_up(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")
        self.use_budget(CACHE_BUDGET_LIMIT - 1)

        result = self.drain()

        self.assertEqual(DrainStop.BUDGET_EXHAUSTED, result.stop)
        self.assertEqual([1], self.cached)
        self.assertEqual(1, cache_queue.count(self.store))
        self.assertIsNotNone(result.next_attempt_at)

    def test_known_answers_cost_nothing(self) -> None:
        self.store.upsert_cache(cache_keys.game_id("miss"), '{"Success":true,"GameID":0}')
        self.store.upsert_cache(cache_keys.game_id("have"), '{"Success":true,"GameID":7}')
        self.store.upsert_cache(cache_keys.patch(7, "misantronic"), PATCH % "Seven")
        self.queue("miss", "have")
        self.use_budget(CACHE_BUDGET_LIMIT)

        result = self.drain()

        self.assertEqual(DrainStop.EMPTY, result.stop)
        self.assertEqual([], self.lookups)
        self.assertEqual(0, cache_queue.count(self.store))

    def test_batch_time_limit_ends_the_batch_without_a_pause(self) -> None:
        self.game_ids["a"] = 1
        self.queue("a")
        self.use_budget(50)
        clock = iter([NOW, NOW + cache_budget.CACHE_BATCH_MAX_MS, NOW + cache_budget.CACHE_BATCH_MAX_MS])

        with mock.patch.object(rom_browser, "current_millis", lambda: next(clock)):
            result = self.drain()

        self.assertEqual(DrainStop.BUDGET_EXHAUSTED, result.stop)
        self.assertTrue(result.time_limited)
        self.assertEqual([], self.cached)
        self.assertEqual(0, cache_budget.load(self.store).paused_until)
        self.assertLessEqual(result.next_attempt_at, cache_budget.current_millis())

    def test_rate_limit_stops_and_persists_the_pause(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")

        def rate_limited_cache_game(*_args, **_kwargs):
            rate_limit.on_rate_limited(None)
            raise RuntimeError("429")

        with mock.patch.object(rom_browser, "cache_game", rate_limited_cache_game):
            result = self.drain()

        self.assertEqual(DrainStop.RATE_LIMITED, result.stop)
        self.assertEqual(2, cache_queue.count(self.store))
        self.assertEqual(0, cache_queue.oldest(self.store).attempts)
        self.assertEqual(result.next_attempt_at, cache_budget.load(self.store).paused_until)

    def test_failure_keeps_the_place_and_counts_an_attempt(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.fail_cache_for.add(1)
        self.queue("a", "b")

        result = self.drain()

        self.assertEqual(DrainStop.FAILED, result.stop)
        oldest = cache_queue.oldest(self.store)
        self.assertEqual(("a", 1), (oldest.hashes[0], oldest.attempts))

    def test_auth_rejection_stops_without_counting_an_attempt(self) -> None:
        self.game_ids["a"] = 1
        self.queue("a")

        def rejected(*_args, **_kwargs):
            raise rom_browser.CacheGameAuthError("401")

        with mock.patch.object(rom_browser, "cache_game", rejected):
            result = self.drain()

        self.assertEqual(DrainStop.AUTH_REJECTED, result.stop)
        self.assertEqual(0, cache_queue.oldest(self.store).attempts)

    def test_keys_limit_the_drain_to_one_run(self) -> None:
        self.game_ids.update({"old": 1, "new": 2})
        self.queue("old")
        keys = self.queue("new")

        self.drain(keys=keys)

        self.assertEqual([2], self.cached)
        self.assertEqual(1, cache_queue.count(self.store))

    def test_spaces_the_roms_that_need_retroachievements(self) -> None:
        self.game_ids.update({"a": 1, "b": 2, "known": 3})
        self.queue("known")
        self.drain()
        self.queue("a", "known", "unknown", "b")

        with mock.patch.object(rom_browser.time, "monotonic", return_value=100.0):
            self.drain()

        expected = 100.0 + cache_budget.CACHE_PACE_SECONDS
        self.assertEqual([expected, expected], self.pace_waits)

    def test_a_single_rom_goes_out_at_once(self) -> None:
        self.game_ids["a"] = 1
        keys = self.queue("a")

        self.drain(keys=keys)

        self.assertEqual([1], self.cached)
        self.assertEqual([], self.pace_waits)

    def test_a_pause_while_waiting_keeps_the_rest_queued(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")

        with mock.patch.object(rom_browser, "wait_for_pace", lambda _ready_at, _should_pause: False):
            result = self.drain()

        self.assertEqual(DrainStop.PAUSED, result.stop)
        self.assertEqual([1], self.cached)
        self.assertEqual(1, cache_queue.count(self.store))

    def test_the_lock_is_free_while_waiting_for_the_pace(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")
        seen = []

        def wait(_ready_at, _should_pause):
            seen.append((cache_queue.drain_lock.held_elsewhere(), cache_queue.draining_elsewhere()))
            return True

        with mock.patch.object(rom_browser, "wait_for_pace", wait):
            result = self.drain()

        self.assertEqual(DrainStop.EMPTY, result.stop)
        self.assertEqual([(False, True)], seen)
        self.assertEqual([1, 2], self.cached)

    def test_a_game_cached_while_waiting_is_not_cached_again(self) -> None:
        self.game_ids.update({"a": 1, "b": 2, "b-alt": 2})
        self.queue("a", "b-alt")
        hand = self.roms("b")[0]

        def wait(_ready_at, _should_pause):
            self.assertTrue(rom_browser.add_rom_to_cache(hand, self.store, {}).success)
            return True

        with mock.patch.object(rom_browser, "wait_for_pace", wait):
            result = self.drain()

        self.assertEqual(DrainStop.EMPTY, result.stop)
        self.assertEqual([1, 2], self.cached)
        self.assertEqual(0, cache_queue.count(self.store))

    def test_a_pause_while_taking_the_lock_back_stops_the_drain(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")
        other = contextlib.ExitStack()
        paused = []

        def wait(_ready_at, _should_pause):
            other.enter_context(cache_queue.drain_lock.hold())
            paused.append(True)
            return True

        with other, mock.patch.object(rom_browser, "wait_for_pace", wait):
            result = self.drain(should_pause=lambda: bool(paused))

        self.assertEqual(DrainStop.PAUSED, result.stop)
        self.assertEqual([1], self.cached)

    def test_the_first_games_of_a_window_go_out_at_once(self) -> None:
        self.game_ids.update({"a": 1, "b": 2, "c": 3, "d": 4})
        self.queue("a", "b", "c", "d")

        with mock.patch.object(cache_budget, "CACHE_BURST_GAMES", 2):
            self.drain()

        self.assertEqual([1, 2, 3, 4], self.cached)
        self.assertEqual(2, len(self.pace_waits))

    def test_a_used_burst_paces_the_next_drain(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("a", "b")
        self.use_budget(2)

        with mock.patch.object(cache_budget, "CACHE_BURST_GAMES", 2):
            self.drain()

        self.assertEqual(1, len(self.pace_waits))

    def test_unknown_roms_count_toward_the_burst(self) -> None:
        self.queue("x", "y", "z")

        with mock.patch.object(cache_budget, "CACHE_BURST_GAMES", 2):
            self.drain()

        self.assertEqual(1, len(self.pace_waits))

    def test_a_paced_window_fits_the_budget(self) -> None:
        paced = (CACHE_BUDGET_LIMIT - cache_budget.CACHE_BURST_GAMES) * cache_budget.CACHE_PACE_SECONDS * 1000
        self.assertLessEqual(paced, CACHE_BUDGET_WINDOW_MS)
        self.assertGreaterEqual(cache_budget.CACHE_BATCH_MAX_MS, CACHE_BUDGET_WINDOW_MS)

    def test_busy_while_another_caller_drains(self) -> None:
        self.queue("a")

        with cache_queue.drain_lock.hold():
            result = self.drain()

        self.assertEqual(DrainStop.BUSY, result.stop)


class WaitForPaceTests(unittest.TestCase):
    def test_sleeps_until_ready_in_short_steps(self) -> None:
        clock = [10.0]
        sleeps: list[float] = []

        def sleep(seconds):
            sleeps.append(seconds)
            clock[0] += seconds

        with mock.patch.object(rom_browser.time, "monotonic", lambda: clock[0]), \
                mock.patch.object(rom_browser.time, "sleep", sleep):
            self.assertTrue(rom_browser.wait_for_pace(12.5, lambda: False))

        self.assertEqual([1.0, 1.0, 0.5], sleeps)

    def test_stops_waiting_when_asked_to_pause(self) -> None:
        calls = iter([False, True])

        with mock.patch.object(rom_browser.time, "monotonic", return_value=0.0), \
                mock.patch.object(rom_browser.time, "sleep") as sleep:
            self.assertFalse(rom_browser.wait_for_pace(60.0, lambda: next(calls)))

        sleep.assert_called_once_with(1.0)


class CoverTests(QueueTestCase):
    def cache_with_icon(self, config: dict) -> list:
        self.game_ids["a"] = 1
        self.queue("a")
        downloads = []

        def cache_game_with_icon(game_id, _hash, credentials, _ua, store, _config, cache_images=True, source_rom_path=None):
            store.upsert_cache(
                cache_keys.patch(game_id, credentials["user"]),
                '{"Success":true,"PatchData":{"Title":"A","ImageIcon":"/Images/000123.png"}}',
            )

        with mock.patch.object(rom_browser, "cache_game", cache_game_with_icon), \
                mock.patch.object(rom_browser, "resolve_cached_static_asset", lambda _path: None), \
                mock.patch.object(rom_browser, "download_static_image", lambda *args: downloads.append(args[1:])):
            rom_browser.drain_cache_queue(self.store, config, CREDENTIALS, "ua")
        return downloads

    def test_cover_is_saved_while_caching_when_images_are_off(self) -> None:
        downloads = self.cache_with_icon({"cache_images": False})

        self.assertEqual(1, len(downloads))
        self.assertEqual("/Images/000123.png", downloads[0][0])
        self.assertEqual(1, downloads[0][2])

    def test_cover_comes_with_the_images_when_image_caching_is_on(self) -> None:
        self.assertEqual([], self.cache_with_icon({"cache_images": True}))

    def test_cover_already_on_the_card_is_not_downloaded_again(self) -> None:
        with mock.patch.object(rom_browser, "resolve_cached_static_asset", lambda _path: Path("/x.png")), \
                mock.patch.object(rom_browser, "download_static_image", lambda *_args: self.fail("downloaded")):
            rom_browser.cache_game_icon(1, '{"PatchData":{"ImageIcon":"/Images/1.png"}}', "ua")


class AddRomTests(QueueTestCase):
    def test_caches_right_away_within_the_budget(self) -> None:
        self.game_ids["tetris"] = 10
        (rom,) = self.roms("tetris")

        result = rom_browser.add_rom_to_cache(rom, self.store, {})

        self.assertEqual((True, False, "Cached Game 10"), (result.success, result.queued, result.message))
        self.assertEqual(0, cache_queue.count(self.store))
        self.assertEqual(1, cache_budget.load(self.store).used)

    def test_queues_when_the_budget_is_used_up(self) -> None:
        self.game_ids["tetris"] = 10
        self.use_budget(CACHE_BUDGET_LIMIT)
        (rom,) = self.roms("tetris")

        result = rom_browser.add_rom_to_cache(rom, self.store, {})

        self.assertTrue(result.success)
        self.assertTrue(result.queued)
        self.assertTrue(result.message.startswith("Queued tetris.nes: caching continues at "))
        self.assertEqual(1, cache_queue.count(self.store))
        self.assertEqual([], self.lookups)

    def test_failure_leaves_nothing_queued(self) -> None:
        self.game_ids["tetris"] = 10
        self.fail_cache_for.add(10)
        (rom,) = self.roms("tetris")

        result = rom_browser.add_rom_to_cache(rom, self.store, {})

        self.assertFalse(result.success)
        self.assertEqual("Caching failed: network down", result.message)
        self.assertEqual(0, cache_queue.count(self.store))


class BulkRunTests(QueueTestCase):
    def test_hashes_everything_then_caches_the_window(self) -> None:
        self.game_ids.update({"a": 1, "b": 2, "c": 3})
        paths = self.roms("a", "b", "c", "unknown")
        self.use_budget(CACHE_BUDGET_LIMIT - 2)
        progress = []

        result = smart_cache.run_cache_paths(self.store, {}, paths, on_progress=progress.append)

        self.assertEqual((4, 2, 2, 0), (result.scanned, result.cached, result.queued, result.skipped))
        phases = [update.phase for update in progress]
        self.assertEqual([smart_cache.PHASE_HASHING] * 4, phases[:4])
        self.assertEqual({smart_cache.PHASE_CACHING}, set(phases[4:]))
        self.assertEqual("cachequeue:c", cache_queue.oldest(self.store).key)

    def test_already_cached_and_unknown_roms_count_as_skipped(self) -> None:
        self.store.upsert_cache(cache_keys.game_id("a"), '{"Success":true,"GameID":1}')
        self.store.upsert_cache(cache_keys.patch(1, "misantronic"), PATCH % "A")
        self.game_ids["b"] = 2
        paths = self.roms("a", "b", "c")

        result = smart_cache.run_cache_paths(self.store, {}, paths)

        self.assertEqual((3, 1, 0, 2), (result.scanned, result.cached, result.queued, result.skipped))

    def test_abort_removes_what_the_run_queued(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.queue("earlier")
        self.use_budget(CACHE_BUDGET_LIMIT)
        paths = self.roms("a", "b")
        hashed = []

        def abort() -> bool:
            return len(hashed) >= 2

        with mock.patch.object(
            rom_browser,
            "hash_candidates_for_manual_cache",
            lambda path: hashed.append(path) or [path.stem],
        ):
            smart_cache.run_cache_paths(self.store, {}, paths, should_abort=abort)

        self.assertEqual(1, cache_queue.count(self.store))
        self.assertEqual("cachequeue:earlier", cache_queue.oldest(self.store).key)

    def test_without_cache_now_everything_is_left_to_the_service(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.store.upsert_cache(cache_keys.game_id("known"), '{"Success":true,"GameID":0}')

        result = smart_cache.run_cache_paths(
            self.store, {}, self.roms("a", "b", "known"), cache_now=False
        )

        self.assertEqual((3, 0, 2, 1), (result.scanned, result.cached, result.queued, result.skipped))
        self.assertEqual([], self.lookups)
        self.assertEqual(0, cache_budget.load(self.store).used)

    def test_completion_message_tells_where_queued_games_go(self) -> None:
        result = smart_cache.SmartCacheResult(scanned=250, total=250, cached=0, skipped=10, queued=240)

        running = smart_cache.cache_completion_message(result, aborted=False)
        stopped = smart_cache.cache_completion_message(result, aborted=False, proxy_running=False)

        self.assertEqual(
            "Cached 0, queued 240, skipped 10\nQueued games are cached in the background, up to 100 every 30 minutes.",
            running,
        )
        self.assertEqual(
            "Cached 0, queued 240, skipped 10\nCaching starts when the proxy is running.",
            stopped,
        )

    def test_requires_a_login(self) -> None:
        with mock.patch.object(smart_cache, "resolve_credentials", lambda *_args: None):
            with self.assertRaises(RuntimeError):
                smart_cache.run_cache_paths(self.store, {}, self.roms("a"))

    def test_cache_roms_prints_a_line_per_rom(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        paths = self.roms("unknown", "a", "b")
        paths_file = self.root / "paths.txt"
        paths_file.write_text("\n".join([*map(str, paths), str(self.root / "missing.nes")]))
        self.use_budget(CACHE_BUDGET_LIMIT - 1)
        stdout = StringIO()

        with mock.patch("sys.argv", ["raofflineproxy", "cache-roms", "--paths-file", str(paths_file)]), \
                mock.patch.object(main, "load_config", return_value={}), \
                mock.patch.object(main, "Storage", return_value=self.store), \
                mock.patch.object(self.store, "close"), \
                mock.patch("sys.stdout", stdout):
            main.main()

        lines = stdout.getvalue().strip().splitlines()
        self.assertEqual(
            [
                "FAIL 4/4 missing.nes: not found",
                "FAIL 1/4 unknown.nes: No RetroAchievements match",
                "OK 2/4 a.nes",
                "QUEUED 3/4 b.nes",
                "DONE cached=1 failed=2 queued=1",
            ],
            lines,
        )


class StorageRulesTests(QueueTestCase):
    def test_clear_cache_drops_queue_and_budget(self) -> None:
        self.queue("a")
        self.use_budget(3)

        self.store.clear_cache()

        self.assertEqual(0, cache_queue.count(self.store))
        self.assertIsNone(self.store.get_cache(cache_keys.CACHE_BUDGET))

    def test_eviction_keeps_the_queue(self) -> None:
        self.queue("a")

        self.store.evict_cache_older_than(NOW + 10**12)

        self.assertEqual(1, cache_queue.count(self.store))

    def test_enqueue_is_deduplicated_by_hash(self) -> None:
        rom = QueuedRom(["abcd"], None, "a", NOW)

        self.assertTrue(cache_queue.enqueue(self.store, rom))
        self.assertFalse(cache_queue.enqueue(self.store, rom))
        self.assertEqual(1, cache_queue.count(self.store))


class WorkerTests(QueueTestCase):
    def worker(self, idle: bool = True, online: bool = True) -> proxy_service.CacheQueueWorker:
        server = types.SimpleNamespace(
            storage=self.store,
            config_data={},
            activity=types.SimpleNamespace(idle_delay_seconds=lambda: 0 if idle else 60),
            is_online=lambda: online,
        )
        return proxy_service.CacheQueueWorker(server)

    def run_once(self, worker: proxy_service.CacheQueueWorker):
        with mock.patch.object(proxy_service, "resolve_credentials", lambda *_args: CREDENTIALS):
            return worker.process_once()

    def test_drains_when_idle_and_online(self) -> None:
        self.game_ids["a"] = 1
        self.queue("a")

        result = self.run_once(self.worker())

        self.assertEqual(DrainStop.EMPTY, result.stop)
        self.assertEqual([1], self.cached)

    def test_waits_while_the_proxy_is_busy_or_offline(self) -> None:
        self.queue("a")

        self.assertIsNone(self.run_once(self.worker(idle=False)))
        self.assertIsNone(self.run_once(self.worker(online=False)))

    def test_waits_for_the_next_window(self) -> None:
        self.queue("a")
        self.use_budget(CACHE_BUDGET_LIMIT)

        self.assertIsNone(self.run_once(self.worker()))

    def test_stands_down_during_a_bulk_run(self) -> None:
        self.queue("a")

        with mock.patch.object(proxy_service.cache_queue, "bulk_run_active", lambda: True):
            self.assertIsNone(self.run_once(self.worker()))


class StopWorkersTests(unittest.TestCase):
    def test_waits_for_a_worker_to_finish_its_round(self) -> None:
        finished = []

        class Worker(threading.Thread):
            def __init__(self) -> None:
                super().__init__(daemon=True)
                self.stop_event = threading.Event()

            def stop(self) -> None:
                self.stop_event.set()

            def run(self) -> None:
                self.stop_event.wait()
                time.sleep(0.2)
                finished.append(True)

        worker = Worker()
        worker.start()

        proxy_service.stop_workers([worker, Worker()])

        self.assertEqual([True], finished)


class RefreshRateLimitTests(QueueTestCase):
    def test_refresh_stops_on_429(self) -> None:
        server = types.SimpleNamespace(
            storage=self.store,
            config_data={},
            activity=types.SimpleNamespace(idle_delay_seconds=lambda: 0),
        )
        refresh = proxy_service.PeriodicRefresh(server)
        calls = []

        def rate_limited_patch(game_id, *_args, **_kwargs):
            calls.append(game_id)
            rate_limit.on_rate_limited(None)
            raise RuntimeError("429")

        with mock.patch.object(proxy_service, "refresh_game_patch", rate_limited_patch):
            refreshed = refresh.refresh_games([1, 2, 3], CREDENTIALS, "ua")

        self.assertEqual(0, refreshed)
        self.assertEqual([1], calls)


if __name__ == "__main__":
    unittest.main()
