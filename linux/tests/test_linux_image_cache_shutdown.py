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
