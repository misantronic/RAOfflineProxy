import subprocess
import tempfile
import unittest
from pathlib import Path


COMMON_SH = Path(__file__).resolve().parents[1] / "spruce" / "app" / "RAOfflineProxy" / "common.sh"


class SpruceSdlDriverSelectionTests(unittest.TestCase):
    """select_sdl_video_driver decides which SDL2 the menu talks to. The arm64 bundle's
    stock SDL2 speaks only x11/wayland/offscreen/dummy, and the Anbernic H700 boards have
    none of those, so without the mali preload the menu initialises the dummy driver and
    renders to nothing."""

    def _select(self, platform: str, mali_sdl2: Path, baseos: str = "") -> dict[str, str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            script = f"""
            . {COMMON_SH}
            RUNTIME_DETECT_LOG={temp_dir}/runtime-detect.log
            SPRUCE_MALI_SDL2={mali_sdl2}
            APP_SPRUCE_PLATFORM={platform}
            APP_SPRUCE_BASEOS={baseos}
            select_sdl_video_driver
            printf 'driver=%s\\n' "${{SDL_VIDEODRIVER-}}"
            printf 'preload=%s\\n' "${{SPRUCE_SDL_PRELOAD-}}"
            printf 'no_udev=%s\\n' "${{SDL_JOYSTICK_DISABLE_UDEV-}}"
            """
            result = subprocess.run(
                ["sh", "-c", script], capture_output=True, text=True, check=True
            )

        return dict(
            line.split("=", 1) for line in result.stdout.strip().splitlines() if "=" in line
        )

    def test_miyoo_mini_keeps_the_vendored_mini_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            values = self._select("MiyooMini", Path(temp_dir) / "libSDL2-2.0.so.0")

        self.assertEqual(values["driver"], "Mini")
        self.assertEqual(values["preload"], "")

    def test_anbernic_h700_preloads_spruces_mali_sdl2(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            mali_sdl2 = Path(temp_dir) / "libSDL2-2.0.so.0"
            mali_sdl2.write_bytes(b"")
            values = self._select("AnbernicXX640480", mali_sdl2, baseos="1")

        self.assertEqual(values["driver"], "mali")
        self.assertEqual(values["preload"], str(mali_sdl2))
        self.assertEqual(values["no_udev"], "1")

    def test_anbernic_h700_without_the_mali_build_leaves_the_driver_unset(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            values = self._select(
                "AnbernicXX640480", Path(temp_dir) / "absent", baseos="1"
            )

        self.assertEqual(values["driver"], "")
        self.assertEqual(values["preload"], "")

    def test_other_aarch64_devices_get_no_preload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            mali_sdl2 = Path(temp_dir) / "libSDL2-2.0.so.0"
            mali_sdl2.write_bytes(b"")
            values = self._select("Brick", mali_sdl2)

        self.assertEqual(values["driver"], "")
        self.assertEqual(values["preload"], "")


if __name__ == "__main__":
    unittest.main()
