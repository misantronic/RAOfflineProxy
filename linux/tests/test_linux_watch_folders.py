from __future__ import annotations

import json
import types
import unittest
from io import StringIO
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import cache_keys, cache_queue, main, proxy_service, rom_browser, watch_folders
from linux.raofflineproxy.storage import current_millis
from linux.tests.test_linux_caching_queue import CREDENTIALS, QueueTestCase


class WatchFolderTestCase(QueueTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.folder = str(self.root / "roms")
        self.hashed: list[str] = []
        self._patches.enter_context(
            mock.patch.object(
                rom_browser,
                "hash_candidates_for_manual_cache",
                lambda path: self.hashed.append(path.name) or [path.stem],
            )
        )
        self._patches.enter_context(mock.patch.object(watch_folders, "save_config"))

    def scan(self, **kwargs) -> watch_folders.FolderScan:
        return watch_folders.scan_folder(self.store, {}, self.folder, **kwargs)

    def seen(self) -> set[str]:
        return {Path(path).name for path in watch_folders.seen_paths(self.store, self.folder)}


class WatchConfigTests(WatchFolderTestCase):
    def test_watching_is_idempotent_and_normalizes_the_path(self) -> None:
        config: dict = {}

        self.assertTrue(watch_folders.watch_folder(config, self.folder + "/"))
        self.assertFalse(watch_folders.watch_folder(config, self.folder))

        self.assertEqual([self.folder], watch_folders.watched_folders(config))

    def test_ignores_garbage_in_the_config(self) -> None:
        config = {"watched_folders": [self.folder, "", 3, None]}

        self.assertEqual([self.folder], watch_folders.watched_folders(config))
        self.assertEqual([], watch_folders.watched_folders({"watched_folders": "nope"}))

    def test_unwatching_forgets_markers_and_scan_time(self) -> None:
        self.roms("a")
        config = {"watched_folders": [self.folder]}
        self.scan()

        self.assertTrue(watch_folders.unwatch_folder(config, self.store, self.folder))
        self.assertFalse(watch_folders.unwatch_folder(config, self.store, self.folder))

        self.assertEqual([], watch_folders.watched_folders(config))
        self.assertEqual(set(), self.seen())
        self.assertEqual({}, watch_folders.last_scans(self.store))


class ConfiguredFoldersTests(WatchFolderTestCase):
    def read(self, content: str | None) -> list[str]:
        config_file = self.root / "config.json"
        if content is not None:
            config_file.write_text(content, encoding="utf-8")
        with mock.patch.object(watch_folders, "CONFIG_FILE", config_file):
            return watch_folders.configured_watched_folders()

    def test_reads_the_watched_folders(self) -> None:
        self.assertEqual([self.folder], self.read(json.dumps({"watched_folders": [self.folder]})))

    def test_a_missing_or_broken_config_means_nothing_is_watched(self) -> None:
        self.assertEqual([], self.read(None))
        self.assertEqual([], self.read("{not json"))
        self.assertEqual([], self.read("[1, 2]"))

    def test_a_broken_config_is_not_logged(self) -> None:
        with self.assertNoLogs("raofflineproxy", level="WARNING"):
            self.read("{not json")


class ScanTests(WatchFolderTestCase):
    def test_first_pass_queues_every_new_file(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.roms("a", "b")

        scan = self.scan()

        self.assertEqual((2, 2, 2, 2), (scan.files, scan.new, scan.handled, scan.queued))
        self.assertTrue(scan.complete)
        self.assertEqual({"a.nes", "b.nes"}, self.seen())
        self.assertEqual(2, cache_queue.count(self.store))
        self.assertIn(self.folder, watch_folders.last_scans(self.store))

    def test_a_second_pass_hashes_nothing(self) -> None:
        self.game_ids.update({"a": 1})
        self.roms("a")
        self.scan()
        self.hashed.clear()

        scan = self.scan()

        self.assertEqual(0, scan.new)
        self.assertEqual([], self.hashed)

    def test_an_unknown_rom_is_not_hashed_again(self) -> None:
        self.roms("unknown")
        self.scan()
        self.drain()
        self.hashed.clear()

        self.scan()

        self.assertEqual(0, cache_queue.count(self.store))
        self.assertEqual([], self.hashed)

    def test_files_known_by_path_are_marked_without_hashing(self) -> None:
        self.roms("a")
        self.queue("a")

        scan = self.scan()

        self.assertEqual(0, scan.new)
        self.assertEqual([], self.hashed)
        self.assertEqual({"a.nes"}, self.seen())

    def test_new_files_added_later_are_picked_up(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.roms("a")
        self.scan()
        self.hashed.clear()
        self.roms("b")

        scan = self.scan()

        self.assertEqual(1, scan.new)
        self.assertEqual(["b.nes"], self.hashed)

    def test_subfolders_are_included(self) -> None:
        nested = self.root / "roms" / "more" / "c.nes"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(b"c")

        self.scan()

        self.assertEqual(["c.nes"], self.hashed)

    def test_a_deleted_file_loses_its_marker_but_stays_cached(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        a, b = self.roms("a", "b")
        self.scan()
        self.drain()
        b.unlink()

        self.scan()

        self.assertEqual({"a.nes"}, self.seen())
        self.assertEqual([1, 2], self.cached)
        self.assertIn(2, rom_browser.cached_game_ids(self.store))

    def test_a_capped_listing_keeps_the_markers_it_did_not_see(self) -> None:
        self.roms("a", "b", "c")
        watch_folders.mark_seen(self.store, [str(self.root / "roms" / "c.nes")])

        with mock.patch.object(rom_browser, "MAX_SCAN_ENTRIES", 2), \
                mock.patch.object(watch_folders, "MAX_SCAN_ENTRIES", 2):
            self.scan()

        self.assertIn("c.nes", self.seen())

    def test_an_aborted_pass_leaves_the_rest_for_the_next_one(self) -> None:
        self.game_ids.update({"a": 1, "b": 2})
        self.roms("a", "b")

        scan = self.scan(should_abort=lambda: len(self.hashed) >= 1)

        self.assertFalse(scan.complete)
        self.assertEqual(set(), self.seen())
        self.assertEqual(0, cache_queue.count(self.store))
        self.assertNotIn(self.folder, watch_folders.last_scans(self.store))

    def test_markers_survive_cache_eviction(self) -> None:
        self.game_ids.update({"a": 1})
        self.roms("a")
        self.scan()

        self.store.evict_cache_older_than(current_millis() + 1)

        self.assertEqual({"a.nes"}, self.seen())

    def test_markers_of_a_similarly_named_folder_are_not_mixed_in(self) -> None:
        self.roms("a")
        watch_folders.mark_seen(self.store, [str(self.root / "romsX" / "z.nes")])

        self.scan()

        self.assertEqual({"a.nes"}, self.seen())


class DueFolderTests(WatchFolderTestCase):
    def test_due_until_scanned_then_again_after_a_window(self) -> None:
        self.roms("a")
        now = current_millis()

        self.assertEqual([self.folder], watch_folders.due_folders(self.store, [self.folder], now))
        watch_folders.record_scan(self.store, self.folder, now)
        self.assertEqual([], watch_folders.due_folders(self.store, [self.folder], now + 1))
        later = now + watch_folders.RESCAN_INTERVAL_MS
        self.assertEqual([self.folder], watch_folders.due_folders(self.store, [self.folder], later))

    def test_a_missing_folder_is_skipped_not_unwatched(self) -> None:
        missing = str(self.root / "unplugged")

        self.assertEqual([], watch_folders.due_folders(self.store, [missing], current_millis()))


class FolderWatcherTests(WatchFolderTestCase):
    def watcher(self, idle_delay: float = 0) -> proxy_service.FolderWatcher:
        server = types.SimpleNamespace(
            storage=self.store,
            config_data={},
            activity=types.SimpleNamespace(idle_delay_seconds=lambda: idle_delay),
        )
        return proxy_service.FolderWatcher(server, poll_seconds=0.01)

    def run_once(self, watcher, credentials=CREDENTIALS) -> list:
        with mock.patch.object(watch_folders, "configured_watched_folders", return_value=[self.folder]), \
                mock.patch.object(proxy_service, "resolve_credentials", return_value=credentials):
            return watcher.process_once()

    def test_scans_due_folders_while_idle(self) -> None:
        self.game_ids.update({"a": 1})
        self.roms("a")

        scans = self.run_once(self.watcher())

        self.assertEqual([self.folder], [scan.folder for scan in scans])
        self.assertEqual(1, cache_queue.count(self.store))

    def test_waits_while_the_proxy_is_busy(self) -> None:
        self.roms("a")

        self.assertEqual([], self.run_once(self.watcher(idle_delay=30)))
        self.assertEqual([], self.hashed)

    def test_waits_while_another_bulk_run_is_active(self) -> None:
        self.roms("a")

        with cache_queue.bulk_run_lock.hold(shared=True):
            self.assertEqual([], self.run_once(self.watcher()))
        self.assertEqual([], self.hashed)

    def test_waits_without_a_login(self) -> None:
        self.roms("a")

        self.assertEqual([], self.run_once(self.watcher(), credentials=None))
        self.assertEqual([], self.hashed)

    def test_does_nothing_without_watched_folders(self) -> None:
        with mock.patch.object(watch_folders, "configured_watched_folders", return_value=[]), \
                mock.patch.object(watch_folders, "due_folders", side_effect=AssertionError("read the store")):
            self.assertEqual([], self.watcher().process_once())

    def test_skips_folders_that_are_not_due(self) -> None:
        self.roms("a")
        watch_folders.record_scan(self.store, self.folder, current_millis())

        self.assertEqual([], self.run_once(self.watcher()))


class WatchCliTests(WatchFolderTestCase):
    def run_cli(self, *args: str, config: dict | None = None) -> str:
        stdout = StringIO()
        with mock.patch("sys.argv", ["raofflineproxy", *args]), \
                mock.patch.object(main, "load_config", return_value={} if config is None else config), \
                mock.patch.object(main, "Storage", return_value=self.store), \
                mock.patch.object(self.store, "close"), \
                mock.patch("sys.stdout", stdout):
            main.main()
        return stdout.getvalue().strip()

    def test_watch_folder(self) -> None:
        self.roms("a")
        config: dict = {}

        first = json.loads(self.run_cli("watch-folder", "--path", self.folder, "--json", config=config))
        again = json.loads(self.run_cli("watch-folder", "--path", self.folder, "--json", config=config))

        self.assertEqual({"path": self.folder, "watched": True, "added": True}, first)
        self.assertFalse(again["added"])
        self.assertEqual([self.folder], config["watched_folders"])

    def test_watch_folder_rejects_a_missing_folder(self) -> None:
        with self.assertRaises(SystemExit):
            self.run_cli("watch-folder", "--path", str(self.root / "nope"))

    def test_unwatch_folder(self) -> None:
        config = {"watched_folders": [self.folder]}

        payload = json.loads(self.run_cli("unwatch-folder", "--path", self.folder, "--json", config=config))

        self.assertEqual({"path": self.folder, "watched": False, "removed": True}, payload)
        self.assertEqual([], config["watched_folders"])

    def test_watched_folders_lists_status(self) -> None:
        self.roms("a")
        missing = str(self.root / "unplugged")
        watch_folders.record_scan(self.store, self.folder, 1234)

        payload = json.loads(
            self.run_cli("watched-folders", "--json", config={"watched_folders": [self.folder, missing]})
        )

        self.assertEqual(
            [
                {"path": self.folder, "exists": True, "last_scan_at": 1234},
                {"path": missing, "exists": False, "last_scan_at": None},
            ],
            payload,
        )


if __name__ == "__main__":
    unittest.main()
