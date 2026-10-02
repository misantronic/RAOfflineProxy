import os
import subprocess
import sys
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class ImageDownloadShutdownTests(unittest.TestCase):
    def test_queued_downloads_do_not_hold_up_interpreter_exit(self) -> None:
        # ThreadPoolExecutor workers are not daemon threads and concurrent.futures
        # joins them during interpreter shutdown, so a first run that queued a
        # game's badges used to keep the menu process alive after its window had
        # closed until every download finished. Run it for real in a subprocess:
        # mocking the executor would assert the call, not the behaviour.
        script = (
            "import time\n"
            "from linux.raofflineproxy import image_cache\n"
            "for _ in range(12):\n"
            "    image_cache._image_download_executor.submit(time.sleep, 2.0)\n"
            "image_cache.shutdown_image_downloads()\n"
        )

        started = time.monotonic()
        subprocess.run(
            [sys.executable, "-c", script], cwd=REPO_ROOT, check=True, timeout=60
        )
        elapsed = time.monotonic() - started

        # 12 tasks over 4 workers would be ~6s if the queue were drained; only the
        # 4 already running are allowed to finish.
        self.assertLess(elapsed, 5.0, "queued downloads delayed interpreter exit")

    def test_shutdown_is_safe_to_call_when_nothing_was_queued(self) -> None:
        script = (
            "from linux.raofflineproxy import image_cache\n"
            "image_cache.shutdown_image_downloads()\n"
        )

        subprocess.run(
            [sys.executable, "-c", script], cwd=REPO_ROOT, check=True, timeout=60
        )


if __name__ == "__main__":
    unittest.main()


class ImageConnectionReuseTests(unittest.TestCase):
    def setUp(self) -> None:
        from linux.raofflineproxy import image_cache

        self.image_cache = image_cache
        image_cache._thread_connections.by_host = {}

    def tearDown(self) -> None:
        self.image_cache._thread_connections.by_host = {}

    def fake_connection_class(self, statuses: list, opened: list):
        class FakeResponse:
            def __init__(self, status: int) -> None:
                self.status = status
                self.will_close = False

            def read(self) -> bytes:
                return b"png"

        class FakeConnection:
            def __init__(self, host, timeout=None, context=None) -> None:
                opened.append(host)

            def request(self, *_args, **_kwargs) -> None:
                status = statuses.pop(0)
                if isinstance(status, Exception):
                    raise status
                self.status = status

            def getresponse(self):
                return FakeResponse(self.status)

            def close(self) -> None:
                pass

        return FakeConnection

    def test_images_share_one_connection_per_thread(self) -> None:
        from unittest import mock

        opened: list = []
        connection = self.fake_connection_class([200, 200, 200], opened)
        with mock.patch.object(self.image_cache.http.client, "HTTPSConnection", connection):
            for number in range(3):
                self.assertEqual(
                    b"png",
                    self.image_cache._fetch_image(f"https://media.example/Badge/{number}.png", "ua"),
                )

        self.assertEqual(["media.example"], opened)

    def test_a_connection_the_server_closed_is_replaced(self) -> None:
        from unittest import mock

        opened: list = []
        connection = self.fake_connection_class([200, ConnectionResetError(), 200], opened)
        with mock.patch.object(self.image_cache.http.client, "HTTPSConnection", connection):
            self.image_cache._fetch_image("https://media.example/Badge/1.png", "ua")
            self.assertEqual(b"png", self.image_cache._fetch_image("https://media.example/Badge/2.png", "ua"))

        self.assertEqual(["media.example", "media.example"], opened)

    def test_anything_but_200_goes_through_urllib(self) -> None:
        from unittest import mock

        opened: list = []
        connection = self.fake_connection_class([302], opened)
        with mock.patch.object(self.image_cache.http.client, "HTTPSConnection", connection), \
                mock.patch.object(self.image_cache, "_fetch_with_urllib", return_value=b"redirected") as urllib_fetch:
            self.assertEqual(b"redirected", self.image_cache._fetch_image("https://media.example/Badge/1.png", "ua"))

        urllib_fetch.assert_called_once()


@unittest.skipUnless(hasattr(os, "getpriority") and sys.platform.startswith("linux"), "needs Linux thread priorities")
class ImageDownloadPriorityTests(unittest.TestCase):
    @staticmethod
    def thread_priority() -> int:
        import threading

        return os.getpriority(os.PRIO_PROCESS, threading.get_native_id())

    def test_batch_downloads_run_at_normal_priority_and_lazy_ones_at_the_lowest(self) -> None:
        from linux.raofflineproxy import image_cache

        main_priority = self.thread_priority()
        batch = image_cache._inline_download_executor.submit(self.thread_priority).result(timeout=10)
        lazy = image_cache._image_download_executor.submit(self.thread_priority).result(timeout=10)

        # A batch waits for its images, so lowest-priority threads made bulk caching 4x slower
        # whenever the menu was redrawing; only the menu's own fire-and-forget covers yield.
        self.assertEqual(main_priority, batch)
        self.assertGreater(lazy, main_priority)


class ShardedStaticImagesTests(unittest.TestCase):
    def setUp(self) -> None:
        import tempfile

        from linux.raofflineproxy import image_cache

        self.image_cache = image_cache
        self._temp_dir = tempfile.TemporaryDirectory()
        self.static = Path(self._temp_dir.name) / "static"
        self._original = image_cache.STATIC_DIR
        image_cache.STATIC_DIR = self.static

    def tearDown(self) -> None:
        self.image_cache.STATIC_DIR = self._original
        self._temp_dir.cleanup()

    def test_badges_spread_over_many_folders(self) -> None:
        folders = {
            self.image_cache.sharded_static_path(f"Badge/{number}.png").parent.name
            for number in range(250_000, 251_000)
        }

        # One folder per kind made a batch slower with every cached game on an SD card.
        self.assertGreater(len(folders), 200)
        for folder in folders:
            self.assertEqual(2, len(folder))

    def test_a_download_lands_in_its_shard_folder(self) -> None:
        from unittest import mock

        with mock.patch.object(self.image_cache, "_fetch_image", return_value=b"png"):
            self.image_cache.download_static_image("https://media/Badge/123456.png", "/Badge/123456.png", "ua")

        expected = self.image_cache.sharded_static_path("Badge/123456.png")
        self.assertEqual(b"png", expected.read_bytes())
        self.assertEqual(expected, self.image_cache.resolve_cached_static_asset("/Badge/123456.png"))
        self.assertFalse((self.static / "Badge" / "123456.png").exists())

    def test_images_cached_before_sharding_are_still_found_and_not_downloaded_again(self) -> None:
        from unittest import mock

        legacy = self.static / "Badge" / "777.png"
        legacy.parent.mkdir(parents=True)
        legacy.write_bytes(b"old")

        with mock.patch.object(self.image_cache, "_fetch_image", side_effect=AssertionError("downloaded")):
            self.image_cache.download_static_image("https://media/Badge/777.png", "/Badge/777.png", "ua")

        self.assertEqual(legacy, self.image_cache.resolve_cached_static_asset("/Badge/777.png"))

    def test_a_missing_image_is_not_found(self) -> None:
        self.assertIsNone(self.image_cache.resolve_cached_static_asset("/Badge/404.png"))
