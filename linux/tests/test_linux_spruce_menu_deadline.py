import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import menu_sdl


COMMON_SH = Path(__file__).resolve().parents[1] / "spruce" / "app" / "RAOfflineProxy" / "common.sh"


class WaitWithDeadlineTests(unittest.TestCase):
    """An SDL driver that blocks during init never returns, and inside a spruce app
    nothing else can close it, so launch.sh bounds both the menu and the driver probe."""

    def _run(self, command: str, seconds: int, ready_file: str = "") -> tuple[int, float]:
        script = f"""
        . {COMMON_SH}
        {command} &
        status=0
        wait_with_deadline "$!" {seconds} "{ready_file}" || status=$?
        printf '%s\\n' "$status"
        """
        started = time.monotonic()
        result = subprocess.run(
            ["sh", "-c", script], capture_output=True, text=True, check=True, timeout=30
        )
        return int(result.stdout.strip()), time.monotonic() - started

    def test_a_process_that_never_finishes_is_killed_at_the_deadline(self) -> None:
        status, elapsed = self._run("sleep 30", 1)

        self.assertEqual(status, 124)
        self.assertLess(elapsed, 10)

    def test_a_process_that_exits_in_time_keeps_its_own_status(self) -> None:
        self.assertEqual(self._run("true", 5)[0], 0)
        self.assertEqual(self._run("sh -c 'exit 3'", 5)[0], 3)

    def test_reaching_the_ready_file_lifts_the_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            ready = Path(temp_dir) / "ready"
            status, elapsed = self._run(f"sh -c 'touch {ready}; sleep 3; exit 7'", 1, str(ready))

        self.assertEqual(status, 7)
        self.assertGreaterEqual(elapsed, 3)


class HeadlessDriverTests(unittest.TestCase):
    def test_a_fallback_to_a_headless_driver_is_refused(self) -> None:
        for driver in menu_sdl.HEADLESS_SDL_DRIVERS:
            with self.subTest(driver=driver), mock.patch.dict(os.environ, {}, clear=False):
                os.environ.pop("SDL_VIDEODRIVER", None)
                with self.assertRaises(RuntimeError):
                    menu_sdl.refuse_headless_driver(driver)

    def test_a_headless_driver_asked_for_explicitly_is_allowed(self) -> None:
        with mock.patch.dict(os.environ, {"SDL_VIDEODRIVER": "dummy"}):
            menu_sdl.refuse_headless_driver("dummy")

    def test_real_display_drivers_are_allowed(self) -> None:
        for driver in ("kmsdrm", "mali", "Mini", "wayland"):
            with self.subTest(driver=driver):
                menu_sdl.refuse_headless_driver(driver)


class MenuReadySignalTests(unittest.TestCase):
    def test_the_ready_file_is_touched_when_requested(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            ready = Path(temp_dir) / "ready"
            with mock.patch.dict(os.environ, {menu_sdl.MENU_READY_FILE_ENV: str(ready)}):
                menu_sdl.signal_menu_ready()

            self.assertTrue(ready.exists())

    def test_nothing_happens_without_a_ready_file(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(menu_sdl.MENU_READY_FILE_ENV, None)
            menu_sdl.signal_menu_ready()


if __name__ == "__main__":
    unittest.main()
