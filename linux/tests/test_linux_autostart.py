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

    def test_autostart_command_uses_onion_headless_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/mnt/SDCARD/App/RAOfflineProxy/autostart-launch.sh",),
                )
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_onion_enable_autostart_writes_plain_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn("APP_DIR=/mnt/SDCARD/App/RAOfflineProxy", content)
                self.assertIn('sh "$APP_DIR/autostart-launch.sh"', content)
                self.assertTrue(platform.autostart_enabled(config_data))
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_onion_disable_autostart_removes_startup_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_onion_startup = platform.DEFAULT_ONION_STARTUP_SCRIPT
            try:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)
                platform.disable_autostart(config_data)

                self.assertFalse(startup_script.exists())
                self.assertFalse(platform.autostart_enabled(config_data))
            finally:
                platform.DEFAULT_ONION_STARTUP_SCRIPT = original_onion_startup

    def test_autostart_command_uses_muos_launcher(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                self.assertEqual(
                    platform.autostart_command(config_data),
                    ("/run/muos/storage/application/RAOfflineProxy/launch.sh",),
                )
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup

    def test_muos_enable_autostart_writes_plain_init_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)

                content = startup_script.read_text(encoding="utf-8")
                self.assertIn('"/run/muos/storage/application/RAOfflineProxy/launch.sh"', content)
                self.assertIn('start-proxy >/dev/null 2>&1 || true', content)
                self.assertTrue(platform.autostart_enabled(config_data))
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup

    def test_muos_disable_autostart_removes_init_script(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            startup_script = Path(temp_dir) / "raofflineproxy.sh"
            config_data = {"startup_script": str(startup_script)}

            original_muos_startup = platform.DEFAULT_MUOS_STARTUP_SCRIPT
            try:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = startup_script
                platform.enable_autostart(config_data)
                platform.disable_autostart(config_data)

                self.assertFalse(startup_script.exists())
                self.assertFalse(platform.autostart_enabled(config_data))
            finally:
                platform.DEFAULT_MUOS_STARTUP_SCRIPT = original_muos_startup


if __name__ == "__main__":
    unittest.main()
