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
