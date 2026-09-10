import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import platform


class DarkosRomRootTests(unittest.TestCase):
    def _roots(self, temp_dir: str) -> tuple[Path, Path, Path]:
        root = Path(temp_dir)
        sd1 = root / "roms"
        sd2 = root / "roms2"
        (sd1 / "tools").mkdir(parents=True)
        (sd2 / "tools").mkdir(parents=True)
        return sd1, sd2, root / "Tools"

    def test_uses_the_first_card_when_tools_is_bound_from_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            sd1, sd2, tools_mount = self._roots(temp_dir)
            with patch.object(platform, "DEFAULT_DARKOS_ROMS_ROOT", sd1), patch.object(
                platform, "DARKOS_SD2_ROMS_ROOT", sd2
            ), patch.object(platform, "DARKOS_TOOLS_MOUNT", sd1 / "tools"):
                self.assertEqual(platform.darkos_roms_root(), sd1)

    def test_uses_the_second_card_when_tools_is_bound_from_it(self) -> None:
        # "Switch to SD2 for Roms" rebinds /opt/system/Tools from /roms2/tools
        # and rewrites every path to /roms2, but /roms stays mounted -- so
        # picking the first card that exists lands on the wrong library.
        with tempfile.TemporaryDirectory() as temp_dir:
            sd1, sd2, _ = self._roots(temp_dir)
            with patch.object(platform, "DEFAULT_DARKOS_ROMS_ROOT", sd1), patch.object(
                platform, "DARKOS_SD2_ROMS_ROOT", sd2
            ), patch.object(platform, "DARKOS_TOOLS_MOUNT", sd2 / "tools"):
                self.assertEqual(platform.darkos_roms_root(), sd2)

    def test_returns_none_off_darkos(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "absent"
            with patch.object(
                platform, "DEFAULT_DARKOS_ROMS_ROOT", missing
            ), patch.object(
                platform, "DARKOS_SD2_ROMS_ROOT", missing
            ), patch.object(platform, "DARKOS_TOOLS_MOUNT", missing):
                self.assertIsNone(platform.darkos_roms_root())


if __name__ == "__main__":
    unittest.main()
