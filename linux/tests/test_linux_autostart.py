import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import platform


class LinuxAutostartTests(unittest.TestCase):
    def test_enable_autostart_writes_wrapped_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}

            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertIn(platform.AUTOSTART_SENTINEL_START, content)
            self.assertIn("raofflineproxy/bin/raofflineproxy", content)
            self.assertTrue(platform.autostart_enabled(config_data))

    def test_enable_autostart_replaces_existing_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            startup_script.write_text(
                "line1\n"
                f"{platform.AUTOSTART_SENTINEL_START}\nold\n{platform.AUTOSTART_SENTINEL_END}\n"
                "line2\n",
                encoding="utf-8",
            )

            platform.enable_autostart(config_data)

            content = startup_script.read_text(encoding="utf-8")
            self.assertEqual(content.count(platform.AUTOSTART_SENTINEL_START), 1)
            self.assertIn("line1", content)
            self.assertIn("line2", content)

    def test_disable_autostart_removes_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "custom.sh"
            config_data = {"startup_script": str(startup_script)}
            platform.enable_autostart(config_data)

            platform.disable_autostart(config_data)

            self.assertFalse(platform.autostart_enabled(config_data))
            self.assertNotIn(
                platform.AUTOSTART_SENTINEL_START,
                startup_script.read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
