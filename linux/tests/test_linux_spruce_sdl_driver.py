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

    def _select(
        self,
        platform: str,
        mali_sdl2: Path,
        baseos: str = "",
        flip_sdl2: Path | None = None,
        trimui_sdl2: tuple[Path, ...] = (),
    ) -> dict[str, str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            flip = flip_sdl2 or Path(temp_dir) / "absent-flip"
            trimui = " ".join(str(path) for path in trimui_sdl2) or f"{temp_dir}/absent-trimui"
            script = f"""
            . {COMMON_SH}
            RUNTIME_DETECT_LOG={temp_dir}/runtime-detect.log
            SPRUCE_MALI_SDL2={mali_sdl2}
            SPRUCE_FLIP_SDL2={flip}
            TRIMUI_FIRMWARE_SDL2="{trimui}"
            APP_SPRUCE_PLATFORM={platform}
            APP_SPRUCE_BASEOS={baseos}
            LD_LIBRARY_PATH=/base/lib
            select_sdl_video_driver
            printf 'driver=%s\\n' "${{SDL_VIDEODRIVER-}}"
            printf 'preload=%s\\n' "${{SPRUCE_SDL_PRELOAD-}}"
            printf 'no_udev=%s\\n' "${{SDL_JOYSTICK_DISABLE_UDEV-}}"
            printf 'menu_path=%s\\n' "$(menu_library_path)"
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

    def test_flip_preloads_the_sdl2_spruce_ships_for_its_own_ui(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            flip_sdl2 = Path(temp_dir) / "libSDL2-2.0.so"
            flip_sdl2.write_bytes(b"")
            values = self._select(
                "Flip", Path(temp_dir) / "absent-mali", flip_sdl2=flip_sdl2
            )

        self.assertEqual(values["driver"], "")
        self.assertEqual(values["preload"], str(flip_sdl2))
        self.assertEqual(values["no_udev"], "")

    def test_flip_without_spruces_sdl2_keeps_the_bundled_one(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            values = self._select("Flip", Path(temp_dir) / "absent-mali")

        self.assertEqual(values["preload"], "")

    def test_trimui_line_preloads_the_first_firmware_sdl2_found(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "libSDL2-2.0.so.0"
            present = Path(temp_dir) / "libSDL2.so"
            present.write_bytes(b"")
            for platform in ("Brick", "BrickPro", "SmartPro", "SmartProS"):
                with self.subTest(platform=platform):
                    values = self._select(
                        platform,
                        Path(temp_dir) / "absent-mali",
                        trimui_sdl2=(missing, present),
                    )
                    self.assertEqual(values["driver"], "")
                    self.assertEqual(values["preload"], str(present))

    def test_other_aarch64_devices_get_no_preload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            present = Path(temp_dir) / "libSDL2-2.0.so.0"
            present.write_bytes(b"")
            for platform in ("Pixel2", "Zero28"):
                with self.subTest(platform=platform):
                    values = self._select(
                        platform, present, flip_sdl2=present, trimui_sdl2=(present,)
                    )
                    self.assertEqual(values["driver"], "")
                    self.assertEqual(values["preload"], "")


if __name__ == "__main__":
    unittest.main()


class MenuLibraryPathTests(unittest.TestCase):
    """A preloaded SDL2 resolves its own NEEDED libraries from the directory it came from:
    spruce's mali build needs libsamplerate.so.0, which ships only in dll-mali. Without
    that directory on the path the preload works solely because spruce's launcher happens
    to have exported it, and running the app from a terminal fails with
    "libsamplerate.so.0: cannot open shared object file"."""

    def _select(self, **kwargs) -> dict[str, str]:
        return SpruceSdlDriverSelectionTests._select(
            SpruceSdlDriverSelectionTests("run"), **kwargs
        )

    def test_mali_preload_exposes_its_own_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            mali_dir = Path(temp_dir) / "dll-mali"
            mali_dir.mkdir()
            mali_sdl2 = mali_dir / "libSDL2-2.0.so.0"
            mali_sdl2.write_bytes(b"")
            values = self._select(
                platform="AnbernicXX640480", mali_sdl2=mali_sdl2, baseos="1"
            )

        self.assertEqual(values["preload"], str(mali_sdl2))
        self.assertEqual(values["menu_path"], f"{mali_dir}:/base/lib")

    def test_flip_preload_exposes_its_own_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            flip_dir = Path(temp_dir) / "dll"
            flip_dir.mkdir()
            flip_sdl2 = flip_dir / "libSDL2-2.0.so"
            flip_sdl2.write_bytes(b"")
            values = self._select(
                platform="Flip",
                mali_sdl2=Path(temp_dir) / "absent",
                flip_sdl2=flip_sdl2,
            )

        self.assertEqual(values["preload"], str(flip_sdl2))
        self.assertEqual(values["menu_path"], f"{flip_dir}:/base/lib")

    def test_without_a_preload_the_path_is_left_alone(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            values = self._select(
                platform="AnbernicXX640480",
                mali_sdl2=Path(temp_dir) / "absent",
                baseos="1",
            )

        self.assertEqual(values["preload"], "")
        self.assertEqual(values["menu_path"], "/base/lib")

    def test_miyoo_mini_keeps_the_bundled_stack_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            values = self._select(
                platform="MiyooMini", mali_sdl2=Path(temp_dir) / "absent"
            )

        self.assertEqual(values["driver"], "Mini")
        self.assertEqual(values["preload"], "")
        self.assertEqual(values["menu_path"], "/base/lib")
